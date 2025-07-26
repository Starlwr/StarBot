package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotBilibili 线程池配置类
 */
@Slf4j
@Configuration
@StarBotComponent
public class StarBotBilibiliThreadPoolConfig {
    @Resource
    private StarBotBilibiliProperties properties;

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

    private static class BilibiliWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Bilibili 线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }
}
