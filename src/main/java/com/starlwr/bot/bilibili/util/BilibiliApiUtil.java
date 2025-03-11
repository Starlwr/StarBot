package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.NetworkException;
import com.starlwr.bot.bilibili.exception.RequestFailedException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.common.util.HttpUtil;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.HashMap;
import java.util.Map;

/**
 * API 请求工具类
 */
@Slf4j
@Component
public class BilibiliApiUtil {
    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private HttpUtil http;

    private final RetryTemplate retryTemplate = new RetryTemplate();

    @PostConstruct
    public void init() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(NetworkException.class, true);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(properties.getNetwork().getApiRetryMaxTimes(), retryableExceptions);
        retryTemplate.setRetryPolicy(retryPolicy);

        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(properties.getNetwork().getApiRetryInterval());
        retryTemplate.setBackOffPolicy(backOffPolicy);
    }

    /**
     * 获取请求 bilibili API 时所需的 HTTP 请求头
     *
     * @return HTTP 请求头
     */
    public Map<String, String> getBilibiliHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com");
        headers.put("User-Agent", properties.getNetwork().getUserAgent());
        if (StringUtils.isNotBlank(properties.getCookie().getSessData()) && StringUtils.isNotBlank(properties.getCookie().getBuvid3()) && StringUtils.isNotBlank(properties.getCookie().getBiliJct())) {
            headers.put("Cookie", String.format("SESSDATA=%s; buvid3=%s; bili_jct=%s; ", properties.getCookie().getSessData(), properties.getCookie().getBuvid3(), properties.getCookie().getBiliJct()));
        }
        return headers;
    }

    /**
     * 使用默认 bilibili 请求头 GET 请求 bilibili API
     * @param url URL
     * @return 请求结果
     */
    public JSONObject requestBilibiliApi(String url) {
        return requestBilibiliApi(url, "GET", getBilibiliHeaders(), new HashMap<>());
    }

    /**
     * 使用默认 bilibili 请求头 POST 请求 bilibili API
     * @param url URL
     * @param params 请求参数
     * @return 请求结果
     */
    public JSONObject requestBilibiliApi(String url, Map<String, Object> params) {
        return requestBilibiliApi(url, "POST", getBilibiliHeaders(), params);
    }

    /**
     * 请求 bilibili API
     * @param url URL
     * @param method 请求方法，GET 或 POST
     * @param headers 请求头
     * @param params 请求参数
     * @return 请求结果
     */
    public JSONObject requestBilibiliApi(String url, String method, Map<String, String> headers, Map<String, Object> params) {
        return retryTemplate.execute((RetryCallback<JSONObject, NetworkException>) retryContext -> {
            JSONObject rtn;
            JSONObject result;

            try {
                if ("GET".equalsIgnoreCase(method)) {
                    result = http.getJson(url, headers);
                } else if ("POST".equalsIgnoreCase(method)) {
                    result = http.postJson(url, headers, params);
                } else {
                    throw new IllegalArgumentException("不支持的请求方法: " + method);
                }
            } catch (WebClientException e) {
                log.error("请求失败", e);
                throw new RequestFailedException("请求失败", e);
            }

            if (!result.containsKey("code")) {
                throw new RequestFailedException("API 返回数据未含 code 字段: " + result);
            }
            Integer code = result.getInteger("code");
            if (code != 0) {
                // 4101131: 加载错误，请稍后再试, 22015: 您的账号异常，请稍后再试
                if (code == 4101131 || code == 22015) {
                    throw new NetworkException(code);
                }
                String message = result.containsKey("message") ? result.getString("message") : "接口未返回错误信息";
                throw new ResponseCodeException(code, message);
            }

            if (result.containsKey("data")) {
                rtn = result.getJSONObject("data");
            } else if (result.containsKey("result")) {
                rtn = result.getJSONObject("result");
            } else {
                throw new RequestFailedException("API 返回数据未含 data 或 result 字段: " + result);
            }

            return rtn;
        });
    }
}
