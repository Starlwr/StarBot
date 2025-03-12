package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.WebSign;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bilibili 账号服务
 */
@Slf4j
@Service
public class BilibiliAccountService {
    @Resource
    private ApplicationContext context;

    @Resource
    private BilibiliApiUtil bilibili;

    @Getter
    private Up accountInfo;

    /**
     * 使用登录凭据登录 B 站账号，登录失败会退出程序
     */
    public void login() {
        log.info("开始获取 Bilibili Web Api 签名");
        WebSign sign = bilibili.generateBilibiliWebSign();
        log.info("Bilibili Web Api 签名获取成功, ticket: {}, imgKey: {}, subKey: {}", sign.getTicket(), sign.getImgKey(), sign.getSubKey());

        log.info("开始尝试使用登录凭据登录 B 站账号");
        try {
            Long uid = bilibili.getLoginUid();
            accountInfo = bilibili.getUpInfoByUid(uid);
            log.info("B 站账号登录成功, UID: {}, 昵称: {}, 房间号: {}", uid, accountInfo.getUname(), accountInfo.getRoomId() == null ? "未开通" : accountInfo.getRoomId());
        } catch (ResponseCodeException e) {
            if (e.getCode() == -101) {
                log.error("尝试登录 B 站账号失败, 可能的原因为登录凭据填写不正确或已失效, 请检查后重试");
            } else {
                log.error("尝试登录 B 站账号失败", e);
            }
        } catch (Exception e) {
            log.error("尝试登录 B 站账号失败", e);
        }

        if (accountInfo == null) {
            SpringApplication.exit(context);
            System.exit(0);
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
}
