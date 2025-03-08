package com.starlwr.bot.bilibili.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * StarBotBilibili 配置类
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "starbot.bilibili")
public class StarBotBilibiliProperties {
    @Getter
    private final Version version = new Version();

    /**
     * 版本相关
     */
    @Getter
    @Setter
    public static class Version {
        /**
         * 版本号
         */
        private String number;

        /**
         * 发布日期
         */
        private String releaseDate;
    }
}
