package com.starlwr.bot.bilibili.config;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * StarBotBilibili 缓存配置类
 */
@Configuration
public class StarBotBilibiliCacheConfig {
    @Bean("cacheKeyGenerator")
    public KeyGenerator keyGenerator() {
        return (target, method, params) -> method.getName() + ":" + Arrays.toString(params);
    }
}
