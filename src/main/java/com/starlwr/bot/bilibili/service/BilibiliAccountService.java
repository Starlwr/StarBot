package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.credential.BilibiliCredentialService;
import com.starlwr.bot.bilibili.credential.CredentialMaintenanceException;
import com.starlwr.bot.bilibili.browser.BilibiliBrowserRuntime;
import com.starlwr.bot.bilibili.credential.QrCodePollResult;
import com.starlwr.bot.bilibili.credential.QrCodeSession;
import com.starlwr.bot.bilibili.credential.QrCodeState;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.model.WebSign;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.QrCodeUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

/** Coordinates login while the Kotlin service owns the Credential protocol. */
@Slf4j
@StarBotComponent
public class BilibiliAccountService {
    private final WebServerApplicationContext webContext;
    private final BilibiliApiUtil bilibili;
    private final BilibiliCredentialService credentialService;
    private final BilibiliBrowserRuntime browserRuntime;

    @Getter
    private Up accountInfo;
    @Getter
    private volatile String loginUrl = "";

    @Autowired
    public BilibiliAccountService(WebServerApplicationContext webContext, BilibiliApiUtil bilibili,
                                  BilibiliCredentialService credentialService,
                                  BilibiliBrowserRuntime browserRuntime) {
        this.webContext = webContext;
        this.bilibili = bilibili;
        this.credentialService = credentialService;
        this.browserRuntime = browserRuntime;
    }

    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        if (!credentialService.getProperties().getLoginOnStartup()) {
            log.info("Bilibili startup login is disabled by configuration");
            return;
        }
        login();
    }

    public void login() {
        try {
            Cookies credential = credentialService.load();
            if (credential == null) {
                log.warn("登录凭据不存在、不完整或格式错误，将进入二维码登录流程");
                qrCodeLogin();
                return;
            }
            if (!credentialService.validateAndUpdateLease(credential)) {
                log.warn("登录凭据已失效，将进入二维码登录流程");
                qrCodeLogin();
                return;
            }
            try {
                credential = credentialService.refreshIfNeeded(credential, false);
            } catch (IllegalArgumentException e) {
                log.warn("旧版凭据缺少刷新字段，将继续使用；下次二维码登录后即可自动刷新: {}", e.getMessage());
            }
            bilibili.setCookies(credential);
            browserRuntime.refreshCanonicalIdentity();
            finishLogin();
        } catch (Exception e) {
            log.warn("使用现有凭据登录失败，将进入二维码登录流程", e);
            qrCodeLogin();
        }
    }

    private void qrCodeLogin() {
        bilibili.setCookies(new Cookies());
        while (!Thread.currentThread().isInterrupted()) {
            QrCodeSession session = credentialService.generateQrCode();
            loginUrl = session.getUrl();
            log.info("请使用 Bilibili APP 扫描二维码登录；网页二维码: http://localhost:{}/bilibili/login/qrcode",
                    webContext.getWebServer().getPort());
            QrCodeUtil.generateQrCodeAndPrint(session.getUrl(), 50);
            try {
                while (true) {
                    Thread.sleep(credentialService.getProperties().getQrPollMillis());
                    QrCodePollResult result = credentialService.pollQrCode(session);
                    if (result.getState() == QrCodeState.WAIT_SCAN || result.getState() == QrCodeState.WAIT_CONFIRM) continue;
                    if (result.getState() == QrCodeState.DONE && result.getCredential() != null) {
                        bilibili.setCookies(result.getCredential());
                        browserRuntime.refreshCanonicalIdentity();
                        finishLogin();
                        loginUrl = "";
                        return;
                    }
                    if (result.getState() == QrCodeState.EXPIRED
                            && credentialService.getProperties().getQrRegenerateOnExpiry()) {
                        log.warn("登录二维码已过期，正在自动生成新二维码");
                        break;
                    }
                    throw new IllegalStateException("二维码登录失败: " + result.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("二维码登录被中断", e);
            }
        }
    }

    private void finishLogin() {
        updateBilibiliWebSign();
        Long uid = bilibili.getLoginUid();
        accountInfo = bilibili.getUpInfoByUid(uid);
        log.info("Bilibili 账号登录成功, UID: {}, 昵称: {}, 直播间号: {}",
                accountInfo.getUid(), accountInfo.getUname(), accountInfo.getRoomIdString());
    }

    @Scheduled(fixedDelayString = "${starbot.bilibili.account.maintenance-interval-millis:30000}",
            initialDelayString = "${starbot.bilibili.account.maintenance-interval-millis:30000}")
    public void refreshCredentialIfNeeded() {
        Cookies current = bilibili.getCookies();
        if (current == null || current.getSessData() == null || current.getSessData().isBlank()) {
            log.info("跳过本轮 Bilibili Credential 维护: 当前没有可用的 SESSDATA");
            return;
        }
        try {
            Cookies refreshed = credentialService.maintain(current);
            if (refreshed != current) {
                bilibili.setCookies(refreshed);
                browserRuntime.refreshCanonicalIdentity();
                updateBilibiliWebSign();
            }
        } catch (CredentialMaintenanceException e) {
            log.warn("Bilibili Credential {}失败；仅该阶段进入退避，保留当前凭据，{} 秒后重试: {}",
                    e.getStage().getLabel(), e.getRetrySeconds(), e.getCause().toString());
            log.debug("Credential maintenance failure detail", e);
        } catch (Exception e) {
            // Reaching this branch means a programming/lifecycle error escaped the
            // stage boundary. Do not poison any persisted retry state without a
            // known owner.
            log.error("Bilibili Credential 维护发生未归类异常；未修改任何阶段退避状态", e);
        }
    }

    public void updateBilibiliWebSign() {
        log.info("开始更新 Bilibili Web API 签名");
        WebSign sign = bilibili.generateBilibiliWebSign();
        browserRuntime.refreshCanonicalIdentity();
        log.info("Bilibili Web API 签名更新成功, ticket expires: {}, imgKey: {}, subKey: {}",
                sign.getTicketExpires(), sign.getImgKey(), sign.getSubKey());
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void maintainBilibiliWebSign() {
        try {
            long before = bilibili.getWebSignUpdatedAtMillis();
            WebSign sign = bilibili.ensureBilibiliWebSign();
            if (bilibili.getWebSignUpdatedAtMillis() != before) browserRuntime.refreshCanonicalIdentity();
            log.debug("Bilibili Web API 签名维护完成, ticket expires: {}, imgKey: {}, subKey: {}",
                    sign.getTicketExpires(), sign.getImgKey(), sign.getSubKey());
        } catch (Exception e) {
            log.warn("Bilibili Web ticket 维护失败；继续保留当前尚可用状态: {}", e.toString());
            log.debug("Bilibili Web ticket maintenance detail", e);
        }
    }
}
