package com.starlwr.bot.bilibili.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.concurrent.TimeUnit;

/**
 * StarBotBilibili 缓存配置类
 */
@StarBotComponent
public class StarBotBilibiliCacheConfig {
    private final CaffeineCacheManager cacheManager;

    @Autowired
    public StarBotBilibiliCacheConfig(CaffeineCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @PostConstruct
    public void init() {
        cacheManager.registerCustomCache("bilibiliApiCache",
                Caffeine.newBuilder()
                        .expireAfterAccess(5, TimeUnit.MINUTES)
                        .maximumSize(100000)
                        .build());
    }
}
