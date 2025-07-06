package com.starlwr.bot.bilibili.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * StarBotBilibili 缓存配置类
 */
@Configuration
@StarBotComponent
public class StarBotBilibiliCacheConfig {
    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        CaffeineCache bilibiliApiCache = new CaffeineCache("bilibiliApiCache",
                Caffeine.newBuilder()
                        .expireAfterAccess(5, TimeUnit.MINUTES)
                        .maximumSize(100000)
                        .build());

        CaffeineCache bilibiliDynamicImageCache = new CaffeineCache("bilibiliDynamicImageCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(1, TimeUnit.MINUTES)
                        .maximumSize(10)
                        .build());

        cacheManager.setCaches(Arrays.asList(bilibiliApiCache, bilibiliDynamicImageCache));
        return cacheManager;
    }

    @Bean("cacheKeyGenerator")
    public KeyGenerator keyGenerator() {
        return (target, method, params) -> method.getName() + ":" + Arrays.toString(params);
    }
}
