package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.util.RedisUtil;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * StarBotBilibili Redis 配置类
 */
@Profile("!core")
@Configuration
public class StarBotBilibiliRedisConfig {
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Bean
    public RedisUtil bilibiliRedis() {
        return new RedisUtil(LivePlatform.BILIBILI.getName(), redisTemplate);
    }
}
