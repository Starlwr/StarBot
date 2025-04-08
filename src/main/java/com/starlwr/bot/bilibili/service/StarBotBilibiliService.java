package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

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

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        log.info("开始启动 StarBot Bilibili - v{}", properties.getVersion().getNumber());

        accountService.login();
    }
}
