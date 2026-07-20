package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.bilibili.telemetry.LiveTelemetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotBilibili 线程池配置类
 */
@Slf4j
@StarBotComponent
@EnableConfigurationProperties(LiveTelemetryProperties.class)
public class StarBotBilibiliThreadPoolConfig {
    private final StarBotBilibiliProperties properties;
    private final LiveTelemetryProperties telemetryProperties;

    @Autowired
    public StarBotBilibiliThreadPoolConfig(StarBotBilibiliProperties properties,
                                           LiveTelemetryProperties telemetryProperties) {
        this.properties = properties;
        this.telemetryProperties = telemetryProperties;
    }

    @Bean
    public ThreadPoolTaskExecutor bilibiliThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getBilibiliThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getBilibiliThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getBilibiliThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getBilibiliThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("bilibili-thread-");
        executor.setRejectedExecutionHandler(new BilibiliWithLogCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** Isolates optional report API baselines from the live-room websocket executor. */
    @Bean
    public ThreadPoolTaskExecutor bilibiliLiveReportThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("bilibili-report-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor bilibiliTelemetryThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, telemetryProperties.getExecutorCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), telemetryProperties.getExecutorMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, telemetryProperties.getExecutorQueueCapacity()));
        executor.setKeepAliveSeconds(Math.max(1, telemetryProperties.getExecutorKeepAliveSeconds()));
        executor.setThreadNamePrefix("bilibili-telemetry-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new BoundedWaitPolicy(
                Math.max(0, telemetryProperties.getExecutorSubmitTimeoutMillis())));
        executor.initialize();
        return executor;
    }

    @Bean
    public ThreadPoolTaskScheduler bilibiliTelemetryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, telemetryProperties.getExecutorSchedulerPoolSize()));
        scheduler.setThreadNamePrefix("bilibili-telemetry-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    private static class BilibiliWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            log.warn("Bilibili 线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }

    private static class BoundedWaitPolicy implements RejectedExecutionHandler {
        private final long timeoutMillis;

        private BoundedWaitPolicy(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Bilibili telemetry executor is shutting down");
            }
            try {
                if (executor.getQueue().offer(task, timeoutMillis, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while waiting for Bilibili telemetry capacity", error);
            }
            throw new RejectedExecutionException("Bilibili telemetry queue remained full for " + timeoutMillis + " ms");
        }
    }
}
