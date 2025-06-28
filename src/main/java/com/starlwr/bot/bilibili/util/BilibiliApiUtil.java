package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.NetworkException;
import com.starlwr.bot.bilibili.exception.RequestFailedException;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.model.*;
import com.starlwr.bot.core.util.CollectionUtil;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.MathUtil;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.util.Pair;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClientException;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API 请求工具类
 */
@Slf4j
@Component
public class BilibiliApiUtil {
    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private HttpUtil http;

    private final RetryTemplate retryTemplate = new RetryTemplate();

    private WebSign sign;

    @Getter
    @Setter
    private Cookies cookies = new Cookies();

    private final Pattern sessDataPattern = Pattern.compile("SESSDATA=(.*?)&");

    private final Pattern biliJctPattern = Pattern.compile("bili_jct=(.*?)&");

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        if (StringUtil.isNotBlank(cookies.getSessData()) && StringUtil.isNotBlank(cookies.getBuvid3()) && StringUtil.isNotBlank(cookies.getBiliJct())) {
            headers.put(
                    "Cookie", String.format(
                            "SESSDATA=%s; buvid3=%s; bili_jct=%s; bili_ticket=%s; bili_ticket_expires=%s; ",
                            cookies.getSessData(),
                            cookies.getBuvid3(),
                            cookies.getBiliJct(),
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
    public JSONObject requestBilibiliApi(String url, Object params) {
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
    public JSONObject requestBilibiliApi(String url, String method, Map<String, String> headers, Object params) {
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
            CompletableFuture<Optional<BufferedImage>> task = bilibili.asyncGetBilibiliImage(url, headers);
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
     * @return 是否登录成功
     */
    public Boolean getQrCodeLoginStatus(String token) {
        String api = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + token;
        JSONObject result = requestBilibiliApi(api);

        Integer code = result.getInteger("code");
        if (code == 0) {
            String url = result.getString("url");

            Matcher sessDataMatcher = sessDataPattern.matcher(url);
            Matcher biliJctMatcher = biliJctPattern.matcher(url);
            if (sessDataMatcher.find() && biliJctMatcher.find()) {
                String sessData = sessDataMatcher.group(1);
                String biliJct = biliJctMatcher.group(1);
                String buvid3 = getBuvid3();
                cookies = new Cookies(sessData, biliJct, buvid3);
            } else {
                log.error("二维码登录失败, 原始接口返回结果: {}", result.toJSONString());
                return false;
            }

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
        return bilibili.getUpInfoByUid(uid);
    }

    /**
     * 根据 UID 获取 UP 主昵称
     * @param uid UID
     * @return UP 主昵称
     */
    public Optional<String> getUnameByUid(@NonNull Long uid) {
        try {
            return Optional.ofNullable(bilibili.getUpInfoByUid(uid).getUname());
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
            return Optional.ofNullable(bilibili.getUpInfoByUid(uid).getRoomId());
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
            return Optional.ofNullable(bilibili.getUpInfoByUid(uid).getFace());
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
                log.error("直播间 {} 发送 Web 心跳包异常", roomId, exception);
            }
        });
    }

    /**
     * 获取直播间最新弹幕
     * @param roomId 房间号
     * @return 最新弹幕列表
     */
    public List<Pair<Long, String>> getLiveRoomLatestDanmus(@NonNull Long roomId) {
        List<Pair<Long, String>> danmus = new ArrayList<>();

        try {
            String api = "https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory?roomid=" + roomId;
            JSONObject result = requestBilibiliApi(api);

            JSONArray messages = result.getJSONArray("room");
            for (JSONObject message : messages.toList(JSONObject.class)) {
                danmus.add(Pair.of(message.getLong("uid"), message.getString("text")));
            }

            return danmus;
        } catch (Exception e) {
            log.error("获取直播间 {} 最新弹幕失败", roomId, e);
            return Collections.emptyList();
        }
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
     * @return 直播间信息
     */
    public Map<Long, Room> getLiveInfoByUids(Set<Long> uids) {
        Map<Long, Room> rooms = new HashMap<>();

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
                Long liveStartTime = roomInfo.getLong("live_time");
                String title = roomInfo.getString("title");
                String cover = roomInfo.getString("cover_from_user");

                Room room = new Room(uid, uname, roomId, face, liveStatus, liveStartTime, title, cover);
                rooms.put(uid, room);
            }
        }

        return rooms;
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
                    .toEpochSecond();
        }

        String title = result.getString("title");
        String cover = result.getString("user_cover");

        return new Room(uid, null, realRoomId, liveStatus, liveStartTime, title, cover);
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
}
