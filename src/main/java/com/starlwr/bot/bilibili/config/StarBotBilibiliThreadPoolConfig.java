package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotBilibili 线程池配置类
 */
@Slf4j
@StarBotComponent
public class StarBotBilibiliThreadPoolConfig {
    private final StarBotBilibiliProperties properties;

    @Autowired
    public StarBotBilibiliThreadPoolConfig(StarBotBilibiliProperties properties) {
        this.properties = properties;
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
}
