package com.starlwr.bot.bilibili.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Bilibili Web Ticket 工具类
 */
@Slf4j
public class BilibiliTicketUtil {
    /**
     * 获取 Bilibili Web Ticket 请求地址
     * @param biliJct Cookie 中的 bili_jct
     * @return Bilibili Web Ticket 请求地址
     */
    public static String getBilibiliTicketUrl(String biliJct) {
        long ts = System.currentTimeMillis() / 1000;
        return "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket?key_id=ec02" +
                "&hexsign=" + hmacSha256("XgwSnGZ1p", "ts" + ts) +
                "&context[ts]=" + ts +
                "&csrf=" + Optional.ofNullable(biliJct).orElse("");
    }

    /**
     * HMAC-SHA256 加密
     * @param key 密钥
     * @param message 要加密的内容
     * @return 加密后的内容
     */
    private static String hmacSha256(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 加密失败", e);
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }
}
