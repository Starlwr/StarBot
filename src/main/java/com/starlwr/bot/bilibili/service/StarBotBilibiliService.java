package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.common.datasource.AbstractDataSource;
import com.starlwr.bot.common.datasource.EmptyDataSource;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * StarBot Bilibili 主服务
 */
@Slf4j
@Service
public class StarBotBilibiliService implements ApplicationListener<ApplicationReadyEvent> {
    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private BilibiliAccountService accountService;

    private final AbstractDataSource dataSource;

    @Autowired
    public StarBotBilibiliService(Optional<AbstractDataSource> optDataSource) {
        this.dataSource = optDataSource.orElse(new EmptyDataSource());
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        log.info("开始启动 StarBot Bilibili - v{}", properties.getVersion().getNumber());

        accountService.login();

        dataSource.load();
    }
}
