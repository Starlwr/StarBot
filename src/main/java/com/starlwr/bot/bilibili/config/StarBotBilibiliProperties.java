package com.starlwr.bot.bilibili.config;

import ch.qos.logback.classic.Level;
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
    private final Log log = new Log();

    @Getter
    private final Debug debug = new Debug();

    @Getter
    private final BilibiliThread bilibiliThread = new BilibiliThread();

    @Getter
    private final Network network = new Network();

    @Getter
    private final Live live = new Live();

    @Getter
    private final Dynamic dynamic = new Dynamic();

    /**
     * 日志相关
     */
    @Getter
    @Setter
    public static class Log {
        /**
         * 控制台日志级别
         */
        private Level console;

        /**
         * 文件日志级别
         */
        private Level file;
    }

    /**
     * 调试相关
     */
    @Getter
    @Setter
    public static class Debug {
        /**
         * 是否记录直播间原始消息日志
         */
        private boolean liveRoomRawMessageLog = false;

        /**
         * 是否记录原始动态信息日志
         */
        private boolean dynamicRawMessageLog = false;
    }

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class BilibiliThread {
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

    /**
     * 网络相关
     */
    @Getter
    @Setter
    public static class Network {
        /**
         * 接口请求时使用的 User-Agent
         */
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Safari/537.36 Core/1.94.218.400 QQBrowser/12.1.5496.400";

        /**
         * 接口请求最大重试次数
         */
        private int apiRetryMaxTimes = 3;

        /**
         * 接口请求重试间隔，单位：毫秒
         */
        private int apiRetryInterval = 3000;
    }

    /**
     * 直播相关
     */
    @Getter
    @Setter
    public static class Live {
        /**
         * 连接两个直播间之间的时间间隔，单位：毫秒
         */
        private int liveRoomConnectInterval = 1000;

        /**
         * 直播间自动断线重连时间间隔，单位：毫秒
         */
        private int liveRoomReconnectInterval = 1000;

        /**
         * 礼物数据缓存时间，单位：秒
         */
        private int giftCacheExpire = 3600;

        /**
         * 是否自动补全事件中缺失的信息，开启后可能会因网络请求耗时导致事件延迟发布
         */
        private boolean completeEvent = false;
    }

    /**
     * 动态相关
     */
    @Getter
    @Setter
    public static class Dynamic {
        /**
         * 动态接口请求频率，单位：秒
         */
        public int apiRequestInterval = 10;

        /**
         * 是否自动保存绘制的动态图片
         */
        public boolean autoSaveImage = false;
    }
}
