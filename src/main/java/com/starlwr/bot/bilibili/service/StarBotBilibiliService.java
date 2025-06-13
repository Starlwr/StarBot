package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.common.datasource.AbstractDataSource;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

/**
 * StarBot Bilibili 主服务
 */
@Slf4j
@Service
public class StarBotBilibiliService implements ApplicationListener<ApplicationReadyEvent> {
    @Resource
    private BuildProperties properties;

    @Resource
    private BilibiliAccountService accountService;

    @Resource
    private AbstractDataSource dataSource;

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        log.info("开始启动 StarBot Bilibili - v{}", properties.getVersion());

        accountService.login();

        dataSource.load();
    }
}
