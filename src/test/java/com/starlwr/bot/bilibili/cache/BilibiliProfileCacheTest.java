package com.starlwr.bot.bilibili.cache;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.cache.StarBotCacheService;
import com.starlwr.bot.core.cache.StarBotCaffeineCacheFactory;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.live.base.StarBotLiveUserEvent;
import com.starlwr.bot.core.event.model.LivePlatform;
import com.starlwr.bot.core.event.model.LiveStreamerInfo;
import com.starlwr.bot.core.event.model.UserInfo;
import com.starlwr.bot.core.http.HttpService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Bilibili 用户资料缓存测试
 */
class BilibiliProfileCacheTest {
    /**
     * StarBotBilibili 配置类
     */
    private final StarBotBilibiliProperties properties = new StarBotBilibiliProperties();

    /**
     * StarBot 缓存服务
     */
    private final StarBotCacheService cacheService = new StarBotCacheService(new StarBotCoreProperties(),
            List.of(new StarBotCaffeineCacheFactory()));

    /**
     * HTTP 请求服务
     */
    private final HttpService http = mock(HttpService.class);

    /**
     * Bilibili API 请求服务
     */
    private final BilibiliApiService bilibili = new BilibiliApiService(properties, http, cacheService);

    /**
     * Bilibili 用户资料缓存
     */
    private final BilibiliProfileCache profileCache = new BilibiliProfileCache(cacheService, properties, bilibili);

    /**
     * 验证仅补查缺失的用户资料，并回填接口返回的昵称和头像
     */
    @Test
    void queriesOnlyMissingUsersAndCachesReturnedFields() {
        BilibiliProfileCacheListener listener = new BilibiliProfileCacheListener(profileCache, properties);
        listener.onExternalEvent(new StarBotLiveUserEvent(LivePlatform.BILIBILI,
                new LiveStreamerInfo("1", "主播", "123", "主播头像"), new UserInfo("2", "观众")));
        listener.onExternalEvent(new StarBotLiveUserEvent(LivePlatform.DOU_YIN,
                new LiveStreamerInfo("3", "其他平台", "456", "其他头像"), new UserInfo("2", "其他平台观众", "其他头像")));
        String api = "https://api.vc.bilibili.com/account/v1/user/cards?uids=2";
        when(http.getJson(eq(api), anyMap())).thenReturn(JSON.parseObject("""
                {"code":0,"data":[{"mid":"2","name":"接口观众","face":"观众头像"}]}
                """));

        Set<String> uids = new HashSet<>(Set.of("1", "2"));
        uids.add(null);
        assertEquals(Map.of("1", "主播头像", "2", "观众头像"), profileCache.getFaces(uids));
        assertEquals(Map.of("1", "主播", "2", "接口观众"), profileCache.getUnames(uids));
        assertTrue(profileCache.getUname(null).isEmpty());
        verify(http).getJson(eq(api), anyMap());
        verifyNoMoreInteractions(http);
    }

    /**
     * 验证关闭事件缓存用户资料后，仍可以查询并缓存接口返回的资料
     */
    @Test
    void keepsReportLookupWhenEventCachingIsDisabled() {
        properties.getCache().setCacheProfileFromEvents(false);
        BilibiliProfileCacheListener listener = new BilibiliProfileCacheListener(profileCache, properties);
        listener.onExternalEvent(new StarBotLiveUserEvent(LivePlatform.BILIBILI,
                new LiveStreamerInfo("1", "主播", "123", "主播头像"), new UserInfo("2", "事件观众", "事件头像")));
        String api = "https://api.vc.bilibili.com/account/v1/user/cards?uids=2";
        when(http.getJson(eq(api), anyMap())).thenReturn(JSON.parseObject("""
                {"code":0,"data":[{"mid":"2","name":"接口观众","face":"接口头像"}]}
                """));

        assertEquals("接口观众", profileCache.getUname("2").orElseThrow());
        assertEquals("接口头像", profileCache.getFace("2").orElseThrow());
        verify(http).getJson(eq(api), anyMap());
        verifyNoMoreInteractions(http);
    }

    /**
     * 验证头像补查返回的昵称可供后续批量查询复用
     */
    @Test
    void cachesUnamesReturnedByFaceLookup() {
        String api = "https://api.vc.bilibili.com/account/v1/user/cards?uids=2";
        when(http.getJson(eq(api), anyMap())).thenReturn(JSON.parseObject("""
                {"code":0,"data":[{"mid":"2","name":"观众","face":"头像"}]}
                """));
        Set<String> uids = Set.of("2");

        assertEquals(Map.of("2", "头像"), profileCache.getFaces(uids));
        assertEquals(Map.of("2", "观众"), profileCache.getUnames(uids));
        verify(http).getJson(eq(api), anyMap());
        verifyNoMoreInteractions(http);
    }

    /**
     * 验证事件可更新已有资料并续期
     */
    @Test
    void replacesAndRenewsCachedProfile() {
        AtomicLong ticks = new AtomicLong();
        properties.getCache().setProfileCacheExpireMinutes(1);
        StarBotCacheService timedCacheService = new StarBotCacheService(new StarBotCoreProperties(),
                List.of(new StarBotCaffeineCacheFactory()));
        BilibiliProfileCache timedProfileCache;
        try (MockedStatic<?> caffeine = mockStatic(Caffeine.class, CALLS_REAL_METHODS)) {
            caffeine.when(Caffeine::newBuilder).thenAnswer(invocation -> {
                Caffeine<?, ?> builder = (Caffeine<?, ?>) invocation.callRealMethod();
                return builder.ticker(ticks::get);
            });
            timedProfileCache = new BilibiliProfileCache(timedCacheService, properties, bilibili);
        }

        timedProfileCache.cacheUserProfile(new UserInfo("2", "缓存昵称", "缓存头像"));
        ticks.addAndGet(Duration.ofSeconds(40).toNanos());
        timedProfileCache.cacheUserProfile(new UserInfo("2", "事件昵称", "事件头像"));
        ticks.addAndGet(Duration.ofSeconds(40).toNanos());

        assertEquals("事件昵称", timedProfileCache.getUname("2").orElseThrow());
        assertEquals("事件头像", timedProfileCache.getFace("2").orElseThrow());
        verifyNoInteractions(http);
    }
}
