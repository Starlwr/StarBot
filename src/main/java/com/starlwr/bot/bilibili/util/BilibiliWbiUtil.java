package com.starlwr.bot.bilibili.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Bilibili Wbi 签名工具类
 */
public class BilibiliWbiUtil {
    private static final int[] mixinKeyEncTab = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private static final char[] hexDigits = "0123456789abcdef".toCharArray();

    /**
     * 获取已包含全部参数的 Wbi 签名
     * @param imgKey imgKey
     * @param subKey subKey
     * @return 已包含全部参数的 Wbi 签名
     */
    public static String getWbiSign(Map<String, Object> params, String imgKey, String subKey) {
        TreeMap<String, Object> map = new TreeMap<>(params);
        map.put("wts", System.currentTimeMillis() / 1000);
        String encodedParams = map.entrySet().stream()
                .map(entry -> String.format("%s=%s", entry.getKey(), urlEncode(entry.getValue())))
                .collect(Collectors.joining("&"));

        String mixinKey = getMixinKey(imgKey, subKey);
        String wbiSign = md5(encodedParams + mixinKey);

        return "?" + params.entrySet().stream()
                .map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("&")) +
                "&w_rid=" + wbiSign + "&wts=" + map.get("wts");
    }

    /**
     * 获取 mixinKey
     * @param imgKey imgKey
     * @param subKey subKey
     * @return mixinKey
     */
    private static String getMixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++)
            key.append(s.charAt(mixinKeyEncTab[i]));
        return key.toString();
    }

    /**
     * 获取 URL encode 编码
     * @param object 要编码的内容
     * @return 编码后的内容
     */
    private static String urlEncode(Object object) {
        return URLEncoder.encode(object.toString(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * MD5 加密
     * @param input 要加密的内容
     * @return 加密后的内容
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[messageDigest.length * 2];
            for (int i = 0; i < messageDigest.length; i++) {
                result[i * 2] = hexDigits[(messageDigest[i] >> 4) & 0xF];
                result[i * 2 + 1] = hexDigits[messageDigest[i] & 0xF];
            }
            return new String(result);
        } catch (Exception e) {
            throw new RuntimeException("MD5 加密失败", e);
        }
    }
}
