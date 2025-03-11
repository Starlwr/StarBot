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

    @Getter
    private final Thread thread = new Thread();

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

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class Thread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 10;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 100;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 0;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 300;
    }
}
