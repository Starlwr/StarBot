package com.starlwr.bot.bilibili.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotBilibili 线程池配置类
 */
@Slf4j
@Configuration
public class StarBotBilibiliThreadPoolConfig {
    @Resource
    private StarBotBilibiliProperties properties;

    @Bean
    public ThreadPoolTaskExecutor bilibiliThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("bilibili-thread-");
        executor.setRejectedExecutionHandler(new WithLogAbortPolicy());
        executor.initialize();
        return executor;
    }

    private static class WithLogAbortPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.error("Bilibili 线程池资源已耗尽, 请考虑增加线程池大小!");
            throw new RejectedExecutionException("异步任务 " + r.toString() + " 提交失败!");
        }
    }
}
