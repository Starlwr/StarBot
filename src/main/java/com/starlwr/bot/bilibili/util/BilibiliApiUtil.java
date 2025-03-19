package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.NetworkException;
import com.starlwr.bot.bilibili.exception.RequestFailedException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.ConnectAddress;
import com.starlwr.bot.bilibili.model.ConnectInfo;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.model.WebSign;
import com.starlwr.bot.common.util.HttpUtil;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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

    private WebSign sign;

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
            headers.put(
                    "Cookie", String.format(
                            "SESSDATA=%s; buvid3=%s; bili_jct=%s; bili_ticket=%s; bili_ticket_expires=%s; ",
                            properties.getCookie().getSessData(),
                            properties.getCookie().getBuvid3(),
                            properties.getCookie().getBiliJct(),
                            sign.getTicket(),
                            sign.getTicketExpires()
                    )
            );
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

    /**
     * 获取 Bilibili Web Api 签名
     * @return Bilibili Web Api 签名
     */
    public WebSign generateBilibiliWebSign() {
        String api = BilibiliTicketUtil.getBilibiliTicketUrl(properties.getCookie().getBiliJct());
        JSONObject result = requestBilibiliApi(api, "POST", new HashMap<>(), new HashMap<>());
        String ticket = result.getString("ticket");
        Integer ticketExpires = result.getInteger("created_at") + result.getInteger("ttl");

        String img = result.getJSONObject("nav").getString("img");
        String sub = result.getJSONObject("nav").getString("sub");
        String imgKey = img.substring(img.lastIndexOf("/") + 1, img.lastIndexOf("."));
        String subKey = sub.substring(sub.lastIndexOf("/") + 1, sub.lastIndexOf("."));

        sign = new WebSign(ticket, ticketExpires, imgKey, subKey);

        return sign;
    }

    /**
     * 获取登录账号 UID
     * @return 登录账号 UID
     */
    public Long getLoginUid() {
        String api = "https://api.bilibili.com/x/space/v2/myinfo";
        JSONObject result = requestBilibiliApi(api);
        JSONObject profile = result.getJSONObject("profile");
        return profile.getLong("mid");
    }

    /**
     * 根据 UID 获取 UP 主信息
     * @param uid UID
     * @return UP 主信息
     */
    public Up getUpInfoByUid(@NonNull Long uid) {
        String api = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=" + uid;
        JSONObject result = requestBilibiliApi(api);

        JSONObject info = result.getJSONObject("info");
        String uname = info.getString("uname");
        Long roomId = result.getLong("room_id");
        String face = info.getString("face");
        return new Up(uid, uname, roomId == 0 ? null : roomId, face);
    }

    /**
     * 根据房间号获取 UP 主信息
     * @param roomId 房间号
     * @return UP 主信息
     */
    public Up getUpInfoByRoomId(@NonNull Long roomId) {
        if (roomId == 0) {
            throw new IllegalArgumentException("房间号不能为 0");
        }

        String api = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId;
        JSONObject result = requestBilibiliApi(api);
        Long uid = result.getLong("uid");
        return getUpInfoByUid(uid);
    }

    /**
     * 获取直播间连接信息
     * @param roomId 房间号
     * @return 直播间连接信息
     */
    public ConnectInfo getLiveRoomConnectInfo(@NonNull Long roomId) {
        String api = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=" + roomId;
        JSONObject result = requestBilibiliApi(api);

        String token = result.getString("token");
        List<ConnectAddress> addresses = result.getJSONArray("host_list").toJavaList(ConnectAddress.class);
        return new ConnectInfo(token, addresses);
    }

    /**
     * 直播间 Web 心跳包
     * @param roomId 房间号
     */
    public void liveRoomHeartbeat(@NonNull Long roomId) {
        String api = "https://live-trace.bilibili.com/xlive/rdata-interface/v1/heartbeat/webHeartBeat?pf=web&hb=";
        String hbParam = Base64.getEncoder().encodeToString(("60|" + roomId + "|1|0").getBytes(StandardCharsets.UTF_8));
        http.asyncGet(api + hbParam, getBilibiliHeaders()).whenComplete((response, exception) -> {
            if (exception != null) {
                log.error("直播间 {} 发送 Web 心跳包异常", roomId, exception);
            }
        });
    }
}
