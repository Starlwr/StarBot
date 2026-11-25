package com.starlwr.bot.bilibili.cache;

import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.cache.StarBotCache;
import com.starlwr.bot.core.cache.StarBotCacheDefinition;
import com.starlwr.bot.core.cache.StarBotCacheSerializer;
import com.starlwr.bot.core.cache.StarBotCacheService;
import com.starlwr.bot.core.event.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Bilibili 用户资料缓存
 */
@StarBotComponent
public class BilibiliProfileCache {
    /**
     * Bilibili API 请求服务
     */
    private final BilibiliApiService bilibili;

    /**
     * 昵称缓存
     */
    private final StarBotCache<String, String> unames;

    /**
     * 头像缓存
     */
    private final StarBotCache<String, String> faces;

    @Autowired
    public BilibiliProfileCache(StarBotCacheService cacheService, StarBotBilibiliProperties properties, BilibiliApiService bilibili) {
        this.bilibili = bilibili;
        StarBotBilibiliProperties.Cache cache = properties.getCache();
        Duration ttl = Duration.ofMinutes(cache.getProfileCacheExpireMinutes());
        Long maximumSize = cache.getProfileCacheMaxSize();
        unames = cacheService.getCache(new StarBotCacheDefinition<>("bilibili.profile.uname", String.class, String.class,
                ttl, maximumSize, StarBotCacheSerializer.STRING, StarBotCacheSerializer.STRING));
        faces = cacheService.getCache(new StarBotCacheDefinition<>("bilibili.profile.face", String.class, String.class,
                ttl, maximumSize, StarBotCacheSerializer.STRING, StarBotCacheSerializer.STRING));
    }

    /**
     * 获取用户昵称
     * @param uid 用户 UID
     * @return 用户昵称
     */
    public Optional<String> getUname(String uid) {
        return Optional.ofNullable(getUnames(Collections.singleton(uid)).get(uid));
    }

    /**
     * 批量获取用户昵称
     * @param uids 用户 UID 集合
     * @return 用户昵称映射
     */
    public Map<String, String> getUnames(Set<String> uids) {
        return getProfileField(uids, unames, UserInfo::getUname);
    }

    /**
     * 获取用户头像
     * @param uid 用户 UID
     * @return 用户头像 URL
     */
    public Optional<String> getFace(String uid) {
        return Optional.ofNullable(getFaces(Collections.singleton(uid)).get(uid));
    }

    /**
     * 批量获取用户头像
     * @param uids 用户 UID 集合
     * @return 用户头像 URL 映射
     */
    public Map<String, String> getFaces(Set<String> uids) {
        return getProfileField(uids, faces, UserInfo::getFace);
    }

    /**
     * 更新缓存中的用户资料并重新计算有效期
     * @param user 用户信息
     */
    public void cacheUserProfile(UserInfo user) {
        if (user == null || user.getUid() == null) {
            return;
        }

        String uid = user.getUid();
        if (StringUtils.isNotBlank(user.getUname())) {
            unames.put(uid, user.getUname());
        }
        if (StringUtils.isNotBlank(user.getFace())) {
            faces.put(uid, user.getFace());
        }
    }

    /**
     * 批量读取指定字段，并将补查结果写入昵称和头像缓存
     * @param uids 用户 UID 集合
     * @param cache 字段对应的缓存
     * @param getter 字段读取方法
     * @return 用户资料字段映射
     */
    private Map<String, String> getProfileField(Set<String> uids, StarBotCache<String, String> cache, Function<UserInfo, String> getter) {
        // 批量读取缓存
        Set<String> missing = new HashSet<>(uids);
        missing.remove(null);
        if (CollectionUtils.isEmpty(missing)) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new HashMap<>(cache.getAll(missing));
        missing.removeAll(values.keySet());
        if (CollectionUtils.isEmpty(missing)) {
            return values;
        }

        // 根据未命中缓存的 UID 补查缺失的用户资料
        Map<String, String> fetchedNames = new HashMap<>();
        Map<String, String> fetchedFaces = new HashMap<>();
        for (Map.Entry<String, UserInfo> entry : bilibili.getUserInfoByUids(missing).entrySet()) {
            String uid = entry.getKey();
            UserInfo user = entry.getValue();
            fetchedNames.put(uid, user.getUname());
            fetchedFaces.put(uid, user.getFace());

            values.put(uid, getter.apply(user));
        }

        // 回填用户资料缓存
        if (!fetchedNames.isEmpty()) {
            unames.putAll(fetchedNames);
        }
        if (!fetchedFaces.isEmpty()) {
            faces.putAll(fetchedFaces);
        }
        return values;
    }
}
