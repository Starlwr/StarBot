package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.model.WebSign;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.QrCodeUtil;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.util.Pair;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bilibili 账号服务
 */
@Slf4j
@Order(-10000)
@StarBotComponent
public class BilibiliAccountService implements ApplicationListener<ApplicationReadyEvent> {
    @Resource
    private ApplicationContext context;

    @Resource
    private WebServerApplicationContext webContext;

    @Resource
    private BilibiliApiUtil bilibili;

    @Getter
    private Up accountInfo;

    @Getter
    private String loginUrl = "";

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        login();
    }

    /**
     * 使用登录凭据登录 B 站账号，登录失败会退出程序
     */
    public void login() {
        updateBilibiliWebSign();

        Path cookiePath = Path.of("cookies.json");

        try {
            if (!Files.exists(cookiePath)) {
                log.warn("登录凭据文件不存在, 将触发扫码登录流程");
                qrCodeLogin();
                return;
            }

            Cookies cookies = JSON.parseObject(Files.readString(cookiePath), Cookies.class);

            if (cookies == null || cookies.getSessData() == null || cookies.getBiliJct() == null || cookies.getBuvid3() == null) {
                log.warn("登录凭据文件内容不完整或格式不正确, 将触发扫码登录流程");
                qrCodeLogin();
                return;
            }

            log.info("开始尝试使用登录凭据登录 B 站账号");
            bilibili.setCookies(cookies);
            Long uid = bilibili.getLoginUid();
            accountInfo = bilibili.getUpInfoByUid(uid);

            log.info("B 站账号登录成功, UID: {}, 昵称: {}, 房间号: {}", accountInfo.getUid(), accountInfo.getUname(), accountInfo.getRoomId() == null ? "未开通" : accountInfo.getRoomId());
        } catch (ResponseCodeException e) {
            if (e.getCode() == -101) {
                log.warn("尝试登录 B 站账号失败, 可能的原因为登录凭据填写不正确或已失效, 将触发扫码登录流程");
            } else {
                log.warn("尝试登录 B 站账号失败, 将触发扫码登录流程", e);
            }
            qrCodeLogin();
        } catch (JSONException e) {
            log.warn("登录凭据文件格式不正确, 将触发扫码登录流程");
            qrCodeLogin();
        } catch (Exception e) {
            log.warn("尝试登录时出现异常, 将触发扫码登录流程", e);
            qrCodeLogin();
        }
    }

    /**
     * 扫码登录
     */
    private void qrCodeLogin() {
        bilibili.setCookies(new Cookies());

        Pair<String, String> loginInfo = bilibili.getQrCodeLoginInfo();
        String url = loginInfo.getFirst();
        String token = loginInfo.getSecond();

        loginUrl = url;

        log.info("请使用 Bilibili APP 扫描以下二维码进行登录, 若终端二维码无法扫描, 请打开 http://localhost:{}/bilibili/login/qrcode 扫描, 若当前为外网环境部署, 请将 localhost 替换为相应的 IP 或域名:", webContext.getWebServer().getPort());
        QrCodeUtil.generateQrCodeAndPrint(url, 50);

        while (true) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("扫码登录中断", e);
            }

            Boolean loginStatus = bilibili.getQrCodeLoginStatus(token);
            if (loginStatus == null) {
                continue;
            }

            if (loginStatus) {
                log.info("扫码登录成功, 开始使用登录凭据登录 B 站账号");

                try {
                    Long uid = bilibili.getLoginUid();
                    accountInfo = bilibili.getUpInfoByUid(uid);
                } catch (Exception e) {
                    log.error("登录 B 站账号失败", e);
                    SpringApplication.exit(context);
                    System.exit(0);
                }

                log.info("B 站账号登录成功, UID: {}, 昵称: {}, 房间号: {}", accountInfo.getUid(), accountInfo.getUname(), accountInfo.getRoomId() == null ? "未开通" : accountInfo.getRoomId());

                loginUrl = "";

                break;
            } else {
                log.error("登录二维码已过期, 请重试");
                SpringApplication.exit(context);
                System.exit(0);
            }
        }
    }

    /**
     * 自动更新 Bilibili Web Api 签名
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void updateBilibiliWebSign() {
        log.info("开始更新 Bilibili Web Api 签名");
        WebSign sign = bilibili.generateBilibiliWebSign();
        log.info("Bilibili Web Api 签名获取成功, ticket: {}, imgKey: {}, subKey: {}", sign.getTicket(), sign.getImgKey(), sign.getSubKey());
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
