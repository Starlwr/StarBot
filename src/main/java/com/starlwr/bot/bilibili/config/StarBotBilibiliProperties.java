package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * StarBotBilibili 配置类
 */
@Getter
@Setter
@Configuration
@StarBotComponent
@ConfigurationProperties(prefix = "starbot.bilibili")
public class StarBotBilibiliProperties {
    @Getter
    private final BilibiliThread bilibiliThread = new BilibiliThread();

    @Getter
    private final Debug debug = new Debug();

    @Getter
    private final Network network = new Network();

    @Getter
    private final Live live = new Live();

    @Getter
    private final Dynamic dynamic = new Dynamic();

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

        /** Log Bilibili HTTP request and response exchanges at DEBUG. */
        private boolean httpLogEnabled = false;

        /** Log decoded WebSocket messages at DEBUG. */
        private boolean websocketLogEnabled = false;

        /** Include WebSocket heartbeat request and response messages. */
        private boolean websocketHeartbeatLogEnabled = false;

        /** Include request and response headers in HTTP diagnostics. */
        private boolean logHeaders = true;

        /** Preserve reusable credentials and tokens in DEBUG diagnostics. */
        private boolean includeSensitiveData = false;

        /** Maximum logged body characters; zero disables truncation. */
        private int logMaxBodyLength = 16384;

        /** DEBUG collection categories emitted to the console; use all to disable category filtering. */
        private Set<String> consoleCategories = new LinkedHashSet<>(List.of("all"));

        /** Suppress repeated DEBUG payloads while retaining change visibility. */
        private boolean consoleDeduplicate = true;

        /** Duplicate suppression window in seconds. */
        private long consoleDeduplicateSeconds = 300;

        /** Raw debug file categories. Supports all, dynamic, live, and category:type selectors. */
        private Set<String> fileCategories = new LinkedHashSet<>(List.of("all"));

        /** Suppress repeated raw payloads in DynamicDebug and LiveDebug independently from the console. */
        private boolean fileDeduplicate = true;

        /** Raw debug file duplicate suppression window in seconds. */
        private long fileDeduplicateSeconds = 900;
    }

    /**
     * 直播相关
     */
    @Getter
    @Setter
    public static class Live {
        /**
         * 是否启用直播间连接
         */
        private boolean enableConnectLiveRoom = true;

        /**
         * 是否仅连接启用了直播推送的直播间
         */
        private boolean onlyConnectNecessaryRooms = false;

        /**
         * 连接两个直播间之间的时间间隔，单位：毫秒
         */
        private int liveRoomConnectInterval = 1000;

        /**
         * 直播间自动断线重连时间间隔，单位：毫秒
         */
        private int liveRoomReconnectInterval = 1000;

        /** Whether concentrated live-room disconnects are summarized as one incident. */
        private boolean disconnectSummaryEnabled = true;

        /** Distinct rooms required to open a disconnect incident. */
        private int disconnectSummaryRoomThreshold = 3;

        /** Sliding window used to correlate disconnects, in seconds. */
        private int disconnectSummaryWindowSeconds = 15;

        /** Quiet time before the final incident summary, in seconds. */
        private int disconnectSummaryQuietSeconds = 30;

        /** Maximum room identifiers retained as incident samples. */
        private int disconnectSummarySampleLimit = 10;

        /**
         * 礼物数据缓存时间，单位：秒
         */
        private int giftCacheExpire = 3600;

        /**
         * 是否自动补全事件中缺失的信息，开启后可能会因网络请求耗时导致事件延迟发布
         */
        private boolean completeEvent = false;

        /**
         * 是否自动检测直播间数据风控，检测到后会自动重新连接直播间
         */
        private boolean autoDetectLiveRoomRisk = true;

        /**
         * 自动检测直播间数据风控的时间间隔，单位：秒
         */
        private int autoDetectLiveRoomRiskInterval = 60;

        /**
         * 直播间数据风控检测阈值，范围：1 ~ 100，数值越高检测越严格
         */
        private int autoDetectLiveRoomRiskRatio = 50;

        /**
         * 是否启用备用直播推送
         */
        private boolean backupLivePush = true;

        /**
         * 备用直播推送检测时间间隔，单位：秒
         */
        private int backupLivePushInterval = 10;
    }

    /**
     * 动态相关
     */
    @Getter
    @Setter
    public static class Dynamic {
        /**
         * 是否自动关注开启了动态推送的 UP 主
         */
        private boolean autoFollow = true;

        /**
         * 自动关注的时间间隔，单位：秒
         */
        private int autoFollowInterval = 30;

        /**
         * 动态接口请求频率，单位：秒
         */
        private int apiRequestInterval = 10;

        /**
         * 是否绘制 StarBot logo
         */
        private boolean drawLogo = true;

        /**
         * 是否自动保存绘制的动态图片
         */
        private boolean autoSaveImage = false;

        /**
         * 推送动态的时间范围，仅推送该时间内的动态，超时不再推送，设置为 0 为不限制，单位：分钟
         */
        private int pushMinutes = 1440;
    }
}
