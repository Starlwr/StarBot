package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.DanmuType;
import com.starlwr.bot.bilibili.exception.NetworkException;
import com.starlwr.bot.bilibili.exception.RequestFailedException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.*;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.CollectionUtil;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.MathUtil;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.CollectionUtils;

import java.awt.image.BufferedImage;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * API 请求工具类
 */
@Slf4j
@StarBotComponent
public class BilibiliApiUtil {
    private final StarBotBilibiliProperties properties;

    private final HttpUtil http;

    private final RetryTemplate retryTemplate = new RetryTemplate();

    private WebSign sign;

    @Getter
    @Setter
    private Cookies cookies = new Cookies();

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    public BilibiliApiUtil(StarBotBilibiliProperties properties, HttpUtil http) {
        this.properties = properties;
        this.http = http;
    }

    @PostConstruct
    public void init() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(NetworkException.class, true);
        retryableExceptions.put(SocketException.class, true);
        retryableExceptions.put(SocketTimeoutException.class, true);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(properties.getNetwork().getApiRetryMaxTimes(), retryableExceptions, true);
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
        if (StringUtil.isNotBlank(cookies.getSessData()) && StringUtil.isNotBlank(cookies.getBiliJct()) && StringUtil.isNotBlank(cookies.getBuvid3())) {
            StringJoiner cookie = new StringJoiner("; ");
            cookie.add("SESSDATA=" + cookies.getSessData());
            cookie.add("buvid3=" + cookies.getBuvid3());
            cookie.add("bili_jct=" + cookies.getBiliJct());
            if (StringUtil.isNotBlank(cookies.getDedeUserId())) {
                cookie.add("DedeUserID=" + cookies.getDedeUserId());
            }
            if (StringUtil.isNotBlank(cookies.getDedeUserIdCkMd5())) {
                cookie.add("DedeUserID__ckMd5=" + cookies.getDedeUserIdCkMd5());
            }
            if (StringUtil.isNotBlank(cookies.getSid())) {
                cookie.add("sid=" + cookies.getSid());
            }
            cookie.add("bili_ticket=" + sign.getTicket());
            cookie.add("bili_ticket_expires=" + sign.getTicketExpires());
            headers.put("Cookie", cookie.toString());
        }
        return headers;
    }

    /**
     * 使用默认 bilibili 请求头 GET 请求 bilibili API
     * @param url URL
     * @return 请求结果
     */
    public JSONObject requestBilibiliApi(String url) {
        return requestBilibiliApi(url, "GET", getBilibiliHeaders(), new HashMap<>(), JSONObject.class);
    }

    /**
     * 使用默认 bilibili 请求头 POST 请求 bilibili API
     * @param url URL
     * @param params 请求参数
     * @return 请求结果
     */
    public JSONObject requestBilibiliApi(String url, Map<String, Object> params) {
        return requestBilibiliApi(url, "POST", getBilibiliHeaders(), params, JSONObject.class);
    }

    /**
     * 使用默认 bilibili 请求头 GET 请求返回列表的 bilibili API
     * @param url URL
     * @return 请求结果
     */
    public JSONArray requestBilibiliApiForArray(String url) {
        return requestBilibiliApi(url, "GET", getBilibiliHeaders(), new HashMap<>(), JSONArray.class);
    }

    /**
     * 使用默认 bilibili 请求头 POST 请求返回列表的 bilibili API
     * @param url URL
     * @param params 请求参数
     * @return 请求结果
     */
    public JSONArray requestBilibiliApiForArray(String url, Map<String, Object> params) {
        return requestBilibiliApi(url, "POST", getBilibiliHeaders(), params, JSONArray.class);
    }

    /**
     * 请求 bilibili API
     * @param url URL
     * @param method 请求方法，GET 或 POST
     * @param headers 请求头
     * @param params 请求参数
     * @param type 返回类型，JSONObject 或 JSONArray
     * @return 请求结果
     */
    public <T> T requestBilibiliApi(String url, String method, Map<String, String> headers, Map<String, Object> params, Class<T> type) {
        return (T) retryTemplate.execute(retryContext -> {
            JSONObject result;

            if ("GET".equalsIgnoreCase(method)) {
                result = http.getJson(url, headers);
            } else if ("POST".equalsIgnoreCase(method)) {
                result = http.postJsonAsForm(url, headers, params);
            } else {
                throw new IllegalArgumentException("不支持的请求方法: " + method);
            }

            if (!result.containsKey("code")) {
                throw new RequestFailedException("API 返回数据未含 code 字段: " + result);
            }
            Integer code = result.getInteger("code");
            if (code != 0) {
                // 4101130: 请求数据发生错误，请刷新或稍后重试, 4101131: 加载错误，请稍后再试, 4101132: 加载错误，请稍后再试, 22015: 您的账号异常，请稍后再试
                if (code == 4101130 || code == 4101131 || code == 4101132 || code == 22015) {
                    throw new NetworkException(code);
                }
                String message = result.containsKey("message") ? result.getString("message") : "接口未返回错误信息";
                throw new ResponseCodeException(code, message);
            }

            if (result.containsKey("data")) {
                if (type == JSONArray.class) {
                    return result.getJSONArray("data");
                } else if (type == JSONObject.class) {
                    return result.getJSONObject("data");
                } else {
                    throw new IllegalArgumentException("返回类型参数只能为 JSONObject 或 JSONArray");
                }
            } else if (result.containsKey("result")) {
                if (type == JSONArray.class) {
                    return result.getJSONArray("result");
                } else if (type == JSONObject.class) {
                    return result.getJSONObject("result");
                } else {
                    throw new IllegalArgumentException("返回类型参数只能为 JSONObject 或 JSONArray");
                }
            } else {
                throw new RequestFailedException("API 返回数据未含 data 或 result 字段: " + result);
            }
        });
    }

    /**
     * 使用默认 bilibili 请求头获取 Bilibili 图片
     * @param url URL
     * @return 图片
     */
    public Optional<BufferedImage> getBilibiliImage(String url) {
        return getBilibiliImage(url, getBilibiliHeaders());
    }

    /**
     * 获取 Bilibili 图片
     * @param url URL
     * @param headers 请求头
     * @return 图片
     */
    public Optional<BufferedImage> getBilibiliImage(String url, Map<String, String> headers) {
        if (StringUtil.isEmpty(url)) {
            return Optional.empty();
        }

        return http.getBufferedImage(url, headers);
    }

    /**
     * 异步获取 Bilibili 图片
     * @param url URL
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBilibiliImage(String url) {
        return asyncGetBilibiliImage(url, getBilibiliHeaders());
    }

    /**
     * 异步获取 Bilibili 图片
     * @param url URL
     * @param headers 请求头
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBilibiliImage(String url, Map<String, String> headers) {
        if (StringUtil.isEmpty(url)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return http.asyncGetBufferedImage(url, headers);
    }

    /**
     * 批量异步获取 Bilibili 图片
     * @param urls URL 列表
     * @return 图片列表
     */
    public CompletableFuture<List<Optional<BufferedImage>>> asyncGetBilibiliImages(List<String> urls) {
        return asyncGetBilibiliImages(urls, getBilibiliHeaders());
    }

    /**
     * 批量异步获取 Bilibili 图片
     * @param urls URL 列表
     * @param headers 请求头
     * @return 图片列表
     */
    public CompletableFuture<List<Optional<BufferedImage>>> asyncGetBilibiliImages(List<String> urls, Map<String, String> headers) {
        if (CollectionUtils.isEmpty(urls)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<CompletableFuture<Optional<BufferedImage>>> downloadPictureTasks = new ArrayList<>();

        for (String url : urls) {
            CompletableFuture<Optional<BufferedImage>> task = asyncGetBilibiliImage(url, headers)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .exceptionally(throwable -> {
                        if (throwable instanceof TimeoutException) {
                            log.warn("从 {} 下载头像超时", url);
                        } else {
                            log.warn("从 {} 下载头像失败", url, throwable);
                        }
                        return Optional.empty();
                    });
            downloadPictureTasks.add(task);
        }

        return CompletableFuture.allOf(downloadPictureTasks.toArray(new CompletableFuture[0]))
                .thenApply(v -> downloadPictureTasks.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    /**
     * 获取 Bilibili Web Api 签名
     * @return Bilibili Web Api 签名
     */
    public WebSign generateBilibiliWebSign() {
        String api = BilibiliTicketUtil.getBilibiliTicketUrl(cookies.getBiliJct());
        JSONObject result = requestBilibiliApi(api, "POST", new HashMap<>(), new HashMap<>(), JSONObject.class);
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
     * 获取 Cookies 中 buvid3 字段
     * @return buvid3 字段
     */
    public String getBuvid3() {
        String api = "https://api.bilibili.com/x/web-frontend/getbuvid";
        JSONObject result = requestBilibiliApi(api);
        return result.getString("buvid");
    }

    /**
     * 获取扫码登录信息
     * @return 扫码登录链接, 二维码 Token
     */
    public Pair<String, String> getQrCodeLoginInfo() {
        String api = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
        JSONObject result = requestBilibiliApi(api);
        return Pair.of(result.getString("url"), result.getString("qrcode_key"));
    }

    /**
     * 获取扫码登录状态
     * @param token 二维码 Token
     * @return 是否登录成功，null 表示尚未扫码或已扫码待确认
     */
    public Boolean getQrCodeLoginStatus(String token) {
        String api = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + token;

        ResponseEntity<String> response = http.getForEntity(api, getBilibiliHeaders());

        JSONObject body = response.getBody() == null ? null : JSON.parseObject(response.getBody());
        JSONObject data = body == null ? null : body.getJSONObject("data");
        Integer code = data == null ? null : data.getInteger("code");
        if (code == null) {
            log.warn("获取扫码登录接口状态码失败");
            return null;
        }

        if (code == 0) {
            Cookies loginCookies = parseLoginCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
            if (StringUtil.isBlank(loginCookies.getSessData()) || StringUtil.isBlank(loginCookies.getBiliJct())) {
                log.error("扫码登录成功, 但未能解析出登录凭据, 原始接口返回结果: {}", body.toJSONString());
                return false;
            }

            try {
                loginCookies.setBuvid3(getBuvid3());
            } catch (Exception e) {
                log.warn("获取 buvid3 失败: {}", e.getMessage());
            }

            cookies = loginCookies;

            Path cookiePath = Path.of("cookies.json");
            try {
                Files.writeString(cookiePath, JSON.toJSONString(cookies, JSONWriter.Feature.PrettyFormat), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.error("保存登录凭据文件失败", e);
            }

            return true;
        } else if (code == 86038) {
            return false;
        } else {
            return null;
        }
    }

    /**
     * 从扫码登录 Set-Cookie 响应头中解析登录凭据
     * @param setCookies Set-Cookie 响应头
     * @return 解析出的登录凭据
     */
    private Cookies parseLoginCookies(List<String> setCookies) {
        Cookies loginCookies = new Cookies();
        if (CollectionUtils.isEmpty(setCookies)) {
            return loginCookies;
        }

        for (String header : setCookies) {
            if (StringUtil.isBlank(header)) {
                continue;
            }

            String pair = header.split(";", 2)[0].trim();
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }

            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (value.isEmpty()) {
                continue;
            }

            switch (name) {
                case "SESSDATA" -> loginCookies.setSessData(value);
                case "bili_jct" -> loginCookies.setBiliJct(value);
                case "DedeUserID" -> loginCookies.setDedeUserId(value);
                case "DedeUserID__ckMd5" -> loginCookies.setDedeUserIdCkMd5(value);
                case "sid" -> loginCookies.setSid(value);
            }
        }

        return loginCookies;
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
    @Cacheable(value = "bilibiliApiCache", keyGenerator = "cacheKeyGenerator")
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
    @Cacheable(value = "bilibiliApiCache", keyGenerator = "cacheKeyGenerator")
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
     * 根据 UID 获取 UP 主昵称
     * @param uid UID
     * @return UP 主昵称
     */
    public Optional<String> getUnameByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(((BilibiliApiUtil) AopContext.currentProxy()).getUpInfoByUid(uid).getUname());
        } catch (Exception e) {
            log.error("获取昵称失败", e);
            return Optional.empty();
        }
    }

    /**
     * 根据 UID 获取 UP 主房间号
     * @param uid UID
     * @return UP 主房间号
     */
    public Optional<Long> getRoomIdByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(((BilibiliApiUtil) AopContext.currentProxy()).getUpInfoByUid(uid).getRoomId());
        } catch (Exception e) {
            log.error("获取房间号失败", e);
            return Optional.empty();
        }
    }

    /**
     * 根据 UID 获取 UP 主头像
     * @param uid UID
     * @return UP 主头像
     */
    public Optional<String> getFaceByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(((BilibiliApiUtil) AopContext.currentProxy()).getUpInfoByUid(uid).getFace());
        } catch (Exception e) {
            log.error("获取头像失败", e);
            return Optional.empty();
        }
    }

    /**
     * 获取直播间连接信息
     * @param roomId 房间号
     * @return 直播间连接信息
     */
    public ConnectInfo getLiveRoomConnectInfo(@NonNull Long roomId) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", roomId);

        String api = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo" + BilibiliWbiUtil.getWbiSign(params, sign.getImgKey(), sign.getSubKey());
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
                log.error("直播间 {} 发送 Web 心跳包异常, 偶然出现此异常可忽略", roomId, exception);
            }
        });
    }

    /**
     * 获取直播间最新弹幕
     * @param roomId 房间号
     * @return 最新弹幕列表
     */
    public List<Danmu> getLiveRoomLatestDanmus(@NonNull Long roomId) {
        List<Danmu> danmus = new ArrayList<>();

        String api = "https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory?roomid=" + roomId;
        JSONObject result = requestBilibiliApi(api);

        JSONArray messages = result.getJSONArray("room");
        for (JSONObject message : messages.toList(JSONObject.class)) {
            try {
                JSONObject userInfo = message.getJSONObject("user");
                JSONObject baseInfo = userInfo.getJSONObject("base");

                if (baseInfo == null) {
                    continue;
                }

                Long uid = userInfo.getLong("uid");
                String uname = baseInfo.getString("name");
                String face = baseInfo.getString("face");

                JSONArray fansMedalInfo = message.getJSONArray("medal");
                FansMedal fansMedal = null;
                if (!fansMedalInfo.isEmpty()) {
                    Long fansMedalUid = fansMedalInfo.getLong(12);
                    String fansMedalUname = fansMedalInfo.getString(2);
                    Long fansMedalRoomId = fansMedalInfo.getLong(3);
                    String fansMedalName = fansMedalInfo.getString(1);
                    Integer fansMedalLevel = fansMedalInfo.getInteger(0);
                    Boolean fansMedalLighted = fansMedalInfo.getInteger(11) == 1;
                    fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalName, fansMedalLevel, fansMedalLighted);
                }

                JSONObject guardInfo = userInfo.getJSONObject("medal");
                Guard guard = (guardInfo != null && guardInfo.getInteger("guard_level") != 0)
                        ? new Guard(guardInfo.getInteger("guard_level"), guardInfo.getString("guard_icon"))
                        : null;

                Integer honorLevel = message.getInteger("wealth_level");

                BilibiliUserInfo sender = new BilibiliUserInfo(uid, uname, face, fansMedal, guard, honorLevel);

                UserInfo reply = null;
                JSONObject replyInfo = message.getJSONObject("reply");
                Long replyUid = replyInfo.getLong("reply_mid");
                if (replyUid != 0L) {
                    String replyUname = replyInfo.getString("reply_uname");
                    reply = new UserInfo(replyUid, replyUname);
                }

                String content = message.getString("text");

                Instant timestamp = LocalDateTime.parse(message.getString("timeline"), formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();

                Integer type = message.getInteger("dm_type");
                if (type == 0) {
                    // 普通弹幕
                    List<BilibiliEmojiInfo> emojis = new ArrayList<>();
                    String contentText = content;
                    JSONObject emojiInfos = message.getJSONObject("emots");
                    if (emojiInfos != null) {
                        for (String emojiName: emojiInfos.keySet()) {
                            contentText = contentText.replace(emojiName, "");

                            JSONObject emojiInfo = emojiInfos.getJSONObject(emojiName);
                            String emojiId = emojiInfo.getString("emoticon_unique");
                            String emojiUrl = emojiInfo.getString("url");
                            Integer emojiWidth = emojiInfo.getInteger("width");
                            Integer emojiHeight = emojiInfo.getInteger("height");
                            Integer emojiCount = emojiInfo.getInteger("count");
                            BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(emojiId, emojiName, emojiUrl, emojiWidth, emojiHeight, emojiCount);
                            emojis.add(emoji);
                        }
                    }

                    Danmu danmu = new Danmu(DanmuType.NORMAL, sender, reply, content, contentText, emojis, timestamp);
                    danmus.add(danmu);
                } else if (type == 1) {
                    // 表情弹幕
                    List<BilibiliEmojiInfo> emojis = new ArrayList<>();
                    JSONObject emojiInfo = message.getJSONObject("emoticon");

                    String emojiId = emojiInfo.getString("emoticon_unique");
                    String emojiName = emojiInfo.getString("text");
                    String emojiUrl = emojiInfo.getString("url");
                    Integer emojiWidth = emojiInfo.getInteger("width");
                    Integer emojiHeight = emojiInfo.getInteger("height");
                    BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(emojiId, emojiName, emojiUrl, emojiWidth, emojiHeight);
                    emojis.add(emoji);

                    Danmu danmu = new Danmu(DanmuType.EMOJI, sender, reply, content, "", emojis, timestamp);
                    danmus.add(danmu);
                } else {
                    log.warn("未处理的弹幕类型: {}, 内容: {}", type, message.toJSONString());
                }
            } catch (Exception e) {
                log.error("读取弹幕信息异常, 原始接口返回结果: {}", message.toJSONString(), e);
            }
        }

        return danmus;
    }

    /**
     * 获取礼物信息
     * @return 礼物信息列表
     */
    public List<Gift> getGiftInfos() {
        List<Gift> gifts = new ArrayList<>();

        String api = "https://api.live.bilibili.com/xlive/web-room/v1/giftPanel/roomGiftConfig?platform=pc";
        JSONObject result = requestBilibiliApi(api);

        JSONArray giftInfos = result.getJSONObject("global_gift").getJSONArray("list");
        for (JSONObject giftInfo: giftInfos.toList(JSONObject.class)) {
            Long giftId = giftInfo.getLong("id");
            String giftName = giftInfo.getString("name");
            double giftPrice = MathUtil.divide(giftInfo.getInteger("price"), 1000.0);
            String giftUrl = giftInfo.getString("img_basic");
            gifts.add(new Gift(giftId, giftName, giftPrice, giftUrl));
        }

        return gifts;
    }

    /**
     * 获取大航海信息
     * @return 大航海信息
     */
    public Map<String, String> getGuardInfos() {
        Map<String, String> guards = new HashMap<>();

        String api = "https://api.live.bilibili.com/xlive/web-room/v1/giftPanel/roomGiftConfig?platform=pc";
        JSONObject result = requestBilibiliApi(api);

        JSONArray guardInfos = result.getJSONArray("guard_resources");
        for (JSONObject guardInfo: guardInfos.toList(JSONObject.class)) {
            guards.put(guardInfo.getString("name"), guardInfo.getString("img"));
        }

        return guards;
    }

    /**
     * 根据 UID 列表批量获取直播间信息
     * @param uids UID 列表
     * @return 直播间信息列表
     */
    public Map<Long, Room> getLiveInfoByUids(Set<Long> uids) {
        Map<Long, Room> rooms = new HashMap<>();

        if (uids.size() == 1) {
            Long uid = uids.iterator().next();
            Optional<Long> optionalRoomId = getRoomIdByUid(uid);
            if (optionalRoomId.isPresent()) {
                Room room = getLiveInfoByRoomId(optionalRoomId.get());
                room.setUname(getUnameByUid(uid).orElseThrow());
                room.setFace(getFaceByUid(uid).orElseThrow());
                rooms.put(uid, room);
            }
            return rooms;
        }

        String api = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]=";

        List<List<Long>> uidLists = CollectionUtil.splitCollection(uids, 100);
        for (List<Long> uidList : uidLists) {
            JSONObject result = requestBilibiliApi(api + uidList.stream().map(String::valueOf).collect(Collectors.joining("&uids[]=")));
            for (String uidString : result.keySet()) {
                JSONObject roomInfo = result.getJSONObject(uidString);

                Long uid = Long.parseLong(uidString);
                String uname = roomInfo.getString("uname");
                Long roomId = roomInfo.getLong("room_id");
                String face = roomInfo.getString("face");
                Integer liveStatus = roomInfo.getInteger("live_status");
                Long liveStartTime = roomInfo.getLong("live_time") * 1000;
                String title = roomInfo.getString("title");
                String cover = roomInfo.getString("cover_from_user");
                String parentAreaName = roomInfo.getString("area_v2_parent_name");
                String areaName = roomInfo.getString("area_v2_name");

                Room room = new Room(uid, uname, roomId, face, liveStatus, liveStartTime, title, cover, parentAreaName, areaName);
                rooms.put(uid, room);
            }
        }

        return rooms;
    }

    /**
     * 根据 UID 列表批量获取用户信息
     * @param uids UID 集合
     * @return 用户信息列表
     */
    public Map<Long, UserInfo> getUserInfoByUids(Set<Long> uids) {
        if (CollectionUtils.isEmpty(uids)) {
            return new HashMap<>();
        }

        Map<Long, UserInfo> infos = new HashMap<>();

        for (Long uid : uids) {
            infos.put(uid, new UserInfo(uid, "昵称获取失败"));
        }

        List<List<Long>> parts = CollectionUtil.splitCollection(uids, 10);
        for (List<Long> part: parts) {
            String param = part.stream().map(String::valueOf).collect(Collectors.joining(","));
            String api = "https://api.vc.bilibili.com/account/v1/user/cards?uids=" + param;
            JSONArray results = requestBilibiliApiForArray(api);
            for (Object result : results) {
                JSONObject info = (JSONObject) result;
                Long uid = info.getLong("mid");
                String uname = info.getString("name");
                String face = info.getString("face");
                infos.put(uid, new UserInfo(uid, uname, face));
            }
        }

        return infos;
    }

    /**
     * 根据房间号获取直播间信息，该接口无昵称及头像信息
     * @param roomId 房间号
     * @return 直播间信息
     */
    public Room getLiveInfoByRoomId(@NonNull Long roomId) {
        if (roomId == 0) {
            throw new IllegalArgumentException("房间号不能为 0");
        }

        String api = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId;
        JSONObject result = requestBilibiliApi(api);

        Long uid = result.getLong("uid");
        Long realRoomId = result.getLong("room_id");
        Integer liveStatus = result.getInteger("live_status");

        long liveStartTime = 0L;
        if (liveStatus == 1) {
            liveStartTime = LocalDateTime.parse(result.getString("live_time"), formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        }

        String title = result.getString("title");
        String cover = result.getString("user_cover");
        String parentAreaName = result.getString("parent_area_name");
        String areaName = result.getString("area_name");

        return new Room(uid, null, realRoomId, liveStatus, liveStartTime, title, cover, parentAreaName, areaName);
    }

    /**
     * 获取最新动态列表
     * @return 最新动态列表
     */
    public List<Dynamic> getDynamicUpdateList() {
        String api = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,decorationCard,onlyfansAssetsV2,forwardListHidden,ugcDelete,onlyfansQaCard,commentsNewVersion";
        JSONObject result = requestBilibiliApi(api);

        try {
            return result.getJSONArray("items").toList(Dynamic.class);
        } catch (Exception e) {
            log.error("获取动态更新列表失败, 原始接口返回内容: {}", result.toJSONString(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取关注的 UP 主列表，该接口无房间号信息
     * @param selfUid 自身 UID
     * @return 关注的 UP 主列表
     */
    public List<Up> getFollowingUps(Long selfUid) {
        List<Up> ups = new ArrayList<>();

        int page = 1;

        while(true) {
            String api = "https://api.bilibili.com/x/relation/followings?vmid=" + selfUid + "&pn=" + page + "&ps=50";
            JSONObject result = requestBilibiliApi(api);

            JSONArray followings = result.getJSONArray("list");
            for (JSONObject following : followings.toList(JSONObject.class)) {
                Long uid = following.getLong("mid");
                String uname = following.getString("uname");
                String face = following.getString("face");
                ups.add(new Up(uid, uname, null, face));
            }

            if (followings.size() < 50) {
                break;
            }

            page++;
        }

        return ups;
    }

    /**
     * 关注 UP 主
     * @param uid 要关注的 UP 主 UID
     */
    public void followUp(Long uid) {
        String api = "https://api.bilibili.com/x/relation/modify";

        Map<String, Object> params = new HashMap<>();
        params.put("fid", uid);
        params.put("act", 1);
        params.put("re_src", 11);
        params.put("csrf", cookies.getBiliJct());

        try {
            requestBilibiliApi(api, params);
        } catch (RequestFailedException e) {
            if (!e.getMessage().startsWith("API 返回数据未含 data 或 result 字段")) {
                throw e;
            }
        }
    }

    /**
     * 获取粉丝数
     * @param uid UID
     * @return 粉丝数
     */
    public int getFansCount(Long uid) {
        String api = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=" + uid;
        JSONObject result = requestBilibiliApi(api);

        return result.getIntValue("follower_num");
    }

    /**
     * 获取粉丝团数(点亮粉丝勋章的粉丝数)
     * @param uid UID
     * @return 粉丝团数(点亮粉丝勋章的粉丝数)
     */
    public int getFansMedalCount(Long uid) {
        String api = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuMedalAnchorInfo?ruid=" + uid;
        JSONObject result = requestBilibiliApi(api);

        return result.getIntValue("fans_club_count");
    }

    /**
     * 获取大航海数
     * @param uid UID
     * @param roomId 房间号
     * @return 大航海数
     */
    public int getGuardCount(Long uid, Long roomId) {
        String api = "https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topListNew?roomid=" + roomId + "&page=1&ruid=" + uid + "&page_size=20&typ=5&platform=web";
        JSONObject result = requestBilibiliApi(api);

        JSONObject info = result.getJSONObject("info");
        return info.getIntValue("num");
    }
}
