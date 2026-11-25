package com.starlwr.bot.bilibili.api;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.api.model.Gift;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.cache.StarBotCacheSerializer;
import com.starlwr.bot.core.cache.StarBotCacheService;
import com.starlwr.bot.core.cache.StarBotCaffeineCacheFactory;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.http.HttpService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bilibili API 请求服务测试
 */
class BilibiliApiServiceTest {
    /**
     * 礼物与大航海资料接口
     */
    private static final String GIFT_CONFIG_API = "https://api.live.bilibili.com/xlive/web-room/v1/giftPanel/roomGiftConfig?platform=pc";

    /**
     * 验证按礼物 ID 和大航海类型读取批量写入的缓存
     */
    @Test
    void cachesGiftsAndGuardIconsByKey() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        HttpService http = mock(HttpService.class);
        when(http.getJson(eq(GIFT_CONFIG_API), anyMap())).thenReturn(JSON.parseObject("""
                {"code":0,"data":{
                  "global_gift":{"list":[{"id":1,"name":"礼物","price":1000,"img_basic":"礼物图片"},{"id":2,"name":"另一份礼物","price":2000}]},
                  "guard_resources":[{"name":"舰长","img":"舰长图标"},{"name":"提督","img":"提督图标"}]
                }}
                """));
        BilibiliApiService bilibili = createApiService(properties, http);

        assertEquals("礼物", bilibili.getGiftInfo("1").orElseThrow().getName());
        assertEquals("另一份礼物", bilibili.getGiftInfo("2").orElseThrow().getName());
        verify(http).getJson(eq(GIFT_CONFIG_API), anyMap());

        assertEquals("舰长图标", bilibili.getGuardIcon("舰长").orElseThrow());
        assertEquals("提督图标", bilibili.getGuardIcon("提督").orElseThrow());
        verify(http, times(2)).getJson(eq(GIFT_CONFIG_API), anyMap());
    }

    /**
     * 验证未命中的礼物 ID 会再次查询接口
     */
    @Test
    void queriesGiftApiOnCacheMiss() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        HttpService http = mock(HttpService.class);
        when(http.getJson(eq(GIFT_CONFIG_API), anyMap())).thenReturn(
                JSON.parseObject("""
                        {"code":0,"data":{"global_gift":{"list":[{"id":1,"name":"礼物","price":1000}]}}}
                        """),
                JSON.parseObject("""
                        {"code":0,"data":{"global_gift":{"list":[{"id":2,"name":"新礼物","price":2000}]}}}
                        """));
        BilibiliApiService bilibili = createApiService(properties, http);

        assertTrue(bilibili.getGiftInfo("2").isEmpty());
        assertEquals("新礼物", bilibili.getGiftInfo("2").orElseThrow().getName());
        assertEquals("新礼物", bilibili.getGiftInfo("2").orElseThrow().getName());
        verify(http, times(2)).getJson(eq(GIFT_CONFIG_API), anyMap());
    }

    /**
     * 验证 UID 和房间号分别缓存主播资料，直播状态仍实时查询
     */
    @Test
    void cachesUpQueriesByUidAndRoomId() {
        HttpService http = mock(HttpService.class);
        String uidApi = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=1";
        String ownerApi = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=2";
        String roomApi = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=1";
        when(http.getJson(eq(uidApi), anyMap())).thenReturn(JSON.parseObject("""
                {"code":0,"data":{"info":{"uname":"用户"},"room_id":0}}
                """));
        when(http.getJson(eq(ownerApi), anyMap())).thenReturn(JSON.parseObject("""
                {"code":0,"data":{"info":{"uname":"主播","face":"头像"},"room_id":123}}
                """));
        when(http.getJson(eq(roomApi), anyMap())).thenReturn(
                JSON.parseObject("""
                        {"code":0,"data":{"uid":"2","room_id":"123","live_status":0}}
                        """),
                JSON.parseObject("""
                        {"code":0,"data":{"uid":"2","room_id":"123","live_status":0,"title":"新标题"}}
                        """));
        BilibiliApiService bilibili = createApiService(new StarBotBilibiliProperties(), http);

        assertTrue(bilibili.getRoomIdByUid("1").isEmpty());
        assertEquals("用户", bilibili.getUnameByUid("1").orElseThrow());
        assertEquals("2", bilibili.getUpInfoByRoomId("1").getUid());
        assertEquals("123", bilibili.getUpInfoByRoomId("1").getRoomId());
        assertEquals("头像", bilibili.getFaceByUid("2").orElseThrow());
        verify(http).getJson(eq(roomApi), anyMap());

        assertEquals("新标题", bilibili.getLiveInfoByRoomId("1").getTitle());
        verify(http, times(2)).getJson(eq(roomApi), anyMap());
        verify(http).getJson(eq(uidApi), anyMap());
        verify(http).getJson(eq(ownerApi), anyMap());
    }

    /**
     * 验证礼物和 UP 主资料可以通过 JSON 序列化器还原
     */
    @Test
    void restoresGiftAndUpFromJson() {
        StarBotCacheSerializer<Gift> giftSerializer = StarBotCacheSerializer.json(Gift.class);
        Gift gift = new Gift("1", "礼物", 2.5, "礼物图片");
        assertEquals(gift, giftSerializer.decode(giftSerializer.encode(gift)));

        StarBotCacheSerializer<Up> upSerializer = StarBotCacheSerializer.json(Up.class);
        Up up = new Up("2", "主播", "123", "主播头像");
        Up decoded = upSerializer.decode(upSerializer.encode(up));
        assertEquals(up.getUid(), decoded.getUid());
        assertEquals(up.getUname(), decoded.getUname());
        assertEquals(up.getRoomId(), decoded.getRoomId());
        assertEquals(up.getFace(), decoded.getFace());
    }

    /**
     * 创建使用指定配置和 HTTP 请求服务的 Bilibili API 请求服务
     * @param properties StarBotBilibili 配置类
     * @param http HTTP 请求服务
     * @return Bilibili API 请求服务
     */
    private BilibiliApiService createApiService(StarBotBilibiliProperties properties, HttpService http) {
        StarBotCacheService cacheService = new StarBotCacheService(new StarBotCoreProperties(),
                List.of(new StarBotCaffeineCacheFactory()));
        return new BilibiliApiService(properties, http, cacheService);
    }
}
