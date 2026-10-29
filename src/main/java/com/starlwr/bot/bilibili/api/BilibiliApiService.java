package com.starlwr.bot.bilibili.api;
import com.starlwr.bot.bilibili.live.connection.ConnectAddress;
import com.starlwr.bot.bilibili.api.sign.BilibiliTicketUtil;
import com.starlwr.bot.bilibili.api.sign.BilibiliWbiUtil;
import com.starlwr.bot.bilibili.api.sign.WebSign;
import com.starlwr.bot.bilibili.account.Cookies;
import com.starlwr.bot.bilibili.live.connection.ConnectInfo;
import com.starlwr.bot.bilibili.dynamic.Dynamic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity;
import com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot;
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore;
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline;
import com.starlwr.bot.bilibili.http.BilibiliHttpResponse;
import com.starlwr.bot.bilibili.api.model.DanmuType;
import com.starlwr.bot.bilibili.api.exception.NetworkException;
import com.starlwr.bot.bilibili.api.exception.RequestFailedException;
import com.starlwr.bot.bilibili.api.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger;
import com.starlwr.bot.bilibili.api.model.*;
import com.starlwr.bot.bilibili.risk.BilibiliRiskProperties;
import com.starlwr.bot.bilibili.risk.GaiaChallenge;
import com.starlwr.bot.bilibili.risk.BrowserRiskExecutor;
import com.starlwr.bot.bilibili.risk.GaiaChallengeProvider;
import com.starlwr.bot.bilibili.risk.GaiaResolution;
import com.starlwr.bot.core.event.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;

import com.starlwr.bot.core.support.http.HttpService;
import com.starlwr.bot.core.util.MathUtil;
import org.apache.commons.lang3.StringUtils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.util.Pair;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.CollectionUtils;

import java.awt.image.BufferedImage;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * API 请求工具类
 */
@Slf4j
@StarBotComponent
public class BilibiliApiService {
    private final StarBotBilibiliProperties properties;

    private final HttpService http;

    private final BilibiliBrowserIdentity browserIdentity;

    private final BilibiliNetworkLogger networkLog;

    private final BilibiliHttpPipeline httpPipeline;

    private final BilibiliCredentialFileStore credentialFileStore;

    private final BilibiliRiskProperties riskProperties;

    private final GaiaChallengeProvider gaiaChallengeProvider;

    private final BrowserRiskExecutor browserRiskExecutor;

    private final com.starlwr.bot.bilibili.risk.BrowserIdentityRegistry identityRegistry;

    private final AtomicLong webSignUpdatedAtMillis = new AtomicLong();

    private final AtomicBoolean webSignRefreshInProgress = new AtomicBoolean();

    private final RetryTemplate retryTemplate = new RetryTemplate();

    private volatile WebSign sign;

    @Getter
    @Setter
    private volatile Cookies cookies = new Cookies();

    private final Pattern sessDataPattern = Pattern.compile("SESSDATA=(.*?)&");

    private final Pattern biliJctPattern = Pattern.compile("bili_jct=(.*?)&");

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    public BilibiliApiService(StarBotBilibiliProperties properties, HttpService http,
                           BilibiliBrowserIdentity browserIdentity, BilibiliNetworkLogger networkLog,
                           BilibiliHttpPipeline httpPipeline, BilibiliCredentialFileStore credentialFileStore,
                           BilibiliRiskProperties riskProperties, GaiaChallengeProvider gaiaChallengeProvider,
                           @org.springframework.lang.Nullable BrowserRiskExecutor browserRiskExecutor,
                           @org.springframework.lang.Nullable com.starlwr.bot.bilibili.risk.BrowserIdentityRegistry identityRegistry) {
        this.properties = properties;
        this.http = http;
        this.browserIdentity = browserIdentity;
        this.networkLog = networkLog;
        this.httpPipeline = httpPipeline;
        this.credentialFileStore = credentialFileStore;
        this.riskProperties = riskProperties;
        this.gaiaChallengeProvider = gaiaChallengeProvider;
        this.browserRiskExecutor = browserRiskExecutor;
        this.identityRegistry = identityRegistry;
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
        headers.putAll(browserIdentity.headers(properties.getNetwork().getUserAgent()));
        if (StringUtils.isNotBlank(cookies.getSessData()) && StringUtils.isNotBlank(cookies.getBiliJct())) {
            Map<String, String> values = new LinkedHashMap<>();
            if (cookies.getExtraCookies() != null) {
                values.putAll(cookies.getExtraCookies());
            }
            values.put("SESSDATA", cookies.getSessData());
            values.put("bili_jct", cookies.getBiliJct());
            values.put("buvid3", cookies.getBuvid3());
            values.put("buvid4", cookies.getBuvid4());
            values.put("DedeUserID", cookies.getDedeUserId());
            values.put("b_nut", cookies.getBNut());
            if (sign != null) {
                values.put("bili_ticket", sign.getTicket());
                values.put("bili_ticket_expires", String.valueOf(sign.getTicketExpires()));
            }
            headers.put("Cookie", values.entrySet().stream()
                    .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("; ")));
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

    /** Request a Bilibili API whose data/result payload is an array. */
    public JSONArray requestBilibiliApiForArray(String url) {
        return requestBilibiliApiForArrayInternal(url, "GET", new HashMap<>());
    }

    /** Request a Bilibili API whose data/result payload is an array. */
    public JSONArray requestBilibiliApiForArray(String url, Map<String, Object> params) {
        return requestBilibiliApiForArrayInternal(url, "POST", params);
    }

    private JSONArray requestBilibiliApiForArrayInternal(String url, String method, Map<String, Object> params) {
        return retryTemplate.execute(retryContext -> {
            BilibiliHttpResponse response = "GET".equals(method)
                    ? httpPipeline.get(url, getBilibiliHeaders(), "bilibili-api-array")
                    : httpPipeline.postForm(url, getBilibiliHeaders(), params, "bilibili-api-array");
            if (!response.successful()) throw new NetworkException(response.getStatus());
            JSONObject result = response.json();
            int code = result.getIntValue("code", 0);
            if (code != 0) throw new ResponseCodeException(code, result.getString("message"));
            Object payload = result.containsKey("data") ? result.get("data") : result.get("result");
            if (!(payload instanceof JSONArray array)) {
                throw new RequestFailedException("API 返回数据未含 JSONArray data 或 result 字段: " + result);
            }
            return array;
        });
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
        return requestBilibiliApi(url, method, headers, params, null);
    }

    private JSONObject requestBilibiliApi(String originalUrl, String method, Map<String, String> headers,
                                        Map<String, Object> params, AtomicReference<RequestIdentitySnapshot> selectedIdentity) {
        AtomicBoolean gaiaRetryPerformed = new AtomicBoolean();
        return retryTemplate.execute(retryContext -> {
            // Refreshing keys is not enough: the retry must rebuild wts/w_rid from the unsigned parameters.
            String url = retryContext.getRetryCount() == 0 ? originalUrl : resignWbiUrl(originalUrl);
            RequestIdentitySnapshot requestIdentity = selectedIdentity == null ? null : selectedIdentity.get();
            JSONObject rtn;
            JSONObject result;
            BilibiliHttpResponse response;
            if ("GET".equalsIgnoreCase(method)) {
                response = requestIdentity == null ? httpPipeline.get(url, headers, "bilibili-api")
                        : httpPipeline.getWithIdentity(url, requestIdentity, "bilibili-api");
            } else if ("POST".equalsIgnoreCase(method)) {
                response = httpPipeline.postForm(url, headers, params, "bilibili-api");
            } else {
                throw new IllegalArgumentException("不支持的请求方法: " + method);
            }
            if (!response.successful()) {
                throw new NetworkException(response.getStatus());
            }
            synchronizeCookiesFromStore();
            result = response.json();

            if (!result.containsKey("code")) {
                throw new RequestFailedException("API 返回数据未含 code 字段: " + result);
            }
            Integer code = result.getInteger("code");
            if (selectedIdentity != null && "browser".equals(response.getRequest().getTransport()) && code != null && code == 0) {
                RequestIdentitySnapshot observed = response.getRequest().getIdentity();
                if (observed == null || !observed.isBrowser()
                        || (requestIdentity != null && !requestIdentity.getUid().isBlank()
                            && !requestIdentity.getUid().equals(observed.getUid()))) {
                    throw new ResponseCodeException(-352, "Browser response identity is unavailable or belongs to another account");
                }
                selectedIdentity.set(observed);
            }
            if (code == -352 && retryContext.getRetryCount() == 0) {
                long updatedAt = webSignUpdatedAtMillis.get();
                long ageSeconds = updatedAt == 0 ? Long.MAX_VALUE
                        : Math.max(0, (System.currentTimeMillis() - updatedAt) / 1000);
                if (ageSeconds >= 900 && webSignRefreshInProgress.compareAndSet(false, true)) {
                    try {
                        log.info("Bilibili API 返回 -352，WBI 已使用 {} 秒，达到 900 秒阈值，重新计算后重试一次", ageSeconds);
                        generateBilibiliWebSign();
                        throw new NetworkException(code);
                    } finally {
                        webSignRefreshInProgress.set(false);
                    }
                } else if (ageSeconds < 900) {
                    log.info("Bilibili API 返回 -352，但 WBI 仅使用 {} 秒，未达到 900 秒阈值，不重复计算", ageSeconds);
                } else {
                    log.info("Bilibili API 返回 -352，但已有 WBI 重算任务正在进行，本请求不再重复触发");
                }
            }
            if ((code == -352 || code == -401) && riskProperties.getGaiaEnabled()) {
                JSONObject challengeData = result.getJSONObject("data");
                String voucher = responseHeader(response, "x-bili-gaia-vvoucher");
                if (StringUtils.isBlank(voucher) && challengeData != null) {
                    voucher = Optional.ofNullable(challengeData.getString("voucher"))
                            .orElse(challengeData.getString("gaia_vvoucher"));
                }
                Object gaDataValue = challengeData == null ? null : challengeData.get("ga_data");
                String gaData = gaDataValue == null ? null
                        : gaDataValue instanceof String value ? value : JSON.toJSONString(gaDataValue);
                GaiaChallenge challenge = new GaiaChallenge(code, voucher, gaData, url);
                GaiaResolution resolution = gaiaChallengeProvider.submit(challenge);
                if (resolution != null && resolution.getHarvestedEnvelope() != null
                        && gaiaRetryPerformed.compareAndSet(false, true)) {
                    // 浏览器环境自行通过: 直接带回完整响应, StarBot 不再自发请求
                    log.info("Bilibili Gaia challenge 已由浏览器环境直接通过并带回响应: code={}, uri={}", code, url);
                    result = resolution.getHarvestedEnvelope();
                    code = result.getInteger("code");
                    RequestIdentitySnapshot verified = resolution.getBrowserIdentity();
                    if (selectedIdentity != null && code != null && code == 0) {
                        if (verified == null || !challenge.getId().equals(verified.getSourceChallengeId())) {
                            throw new ResponseCodeException(-352, "Browser response omitted matching session identity");
                        }
                        if (requestIdentity != null && !requestIdentity.getUid().isBlank()
                                && !requestIdentity.getUid().equals(verified.getUid())) {
                            throw new ResponseCodeException(-352, "Browser account does not match the requested account");
                        }
                        selectedIdentity.set(verified);
                    }
                } else if (resolution != null && StringUtils.isNotBlank(resolution.getVtoken())
                        && gaiaRetryPerformed.compareAndSet(false, true)) {
                    String token = resolution.getVtoken();
                    // 网页端语义: WBI 签名请求(gaia_vtoken 进入 params 后重过签名中间件);
                    // 非 WBI 端点保持追加语义。同时携带 header 与 Cookie 两条 vtoken 通道。
                    String retryUrl;
                    if (url.contains("w_rid=") && sign != null) {
                        Map<String, Object> retryParams = new LinkedHashMap<>();
                        URI originalUri = URI.create(url);
                        if (originalUri.getRawQuery() != null) {
                            for (String pair : originalUri.getRawQuery().split("&")) {
                                int eq = pair.indexOf('=');
                                String key = eq < 0 ? pair : pair.substring(0, eq);
                                if (key.equals("wts") || key.equals("w_rid") || key.equals("gaia_vtoken") || key.equals("token")) continue;
                                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                                retryParams.put(key, value);
                            }
                        }
                        retryParams.put("gaia_vtoken", token);
                        if (code == -401) retryParams.put("token", token);
                        retryUrl = "https://" + originalUri.getHost() + originalUri.getRawPath()
                                + BilibiliWbiUtil.getWbiSign(retryParams, sign.getImgKey(), sign.getSubKey(),
                                properties.getNetwork().isWbiEncodedQuery());
                    } else {
                        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
                        retryUrl = url + (url.contains("?") ? "&" : "?") + "gaia_vtoken=" + encoded;
                        if (code == -401) retryUrl += "&token=" + encoded;
                    }
                    Map<String, String> retryHeaders = new HashMap<>(requestIdentity == null ? headers : requestIdentity.getHeaders());
                    retryHeaders.put("x-bili-gaia-vtoken", token);
                    var browserSnapshot = credentialFileStore.snapshot();
                    if (browserSnapshot != null) {
                        String vtokenValue = browserSnapshot.getCookies().stream()
                                .filter(cookie -> "x-bili-gaia-vtoken".equalsIgnoreCase(cookie.getName()))
                                .map(com.starlwr.bot.bilibili.credential.StoredCookie::getValue)
                                .filter(StringUtils::isNotBlank)
                                .findFirst().orElse(null);
                        if (StringUtils.isNotBlank(vtokenValue)) {
                            String existingCookie = retryHeaders.get("Cookie");
                            retryHeaders.put("Cookie", (existingCookie == null || existingCookie.isBlank()
                                    ? "x-bili-gaia-vtoken=" + vtokenValue
                                    : existingCookie + "; x-bili-gaia-vtoken=" + vtokenValue));
                        }
                    }
                    log.info("Bilibili Gaia challenge 已由交互 provider 完成，重试一次: code={}, uri={}", code, url);
                    // token 绑定浏览器验证环境: 优先经 CfT 通道执行重试(Chrome TLS + 浏览器 cookie,
                    // 含 SDK 写入的 x-bili-gaia-vtoken); 浏览器不可用时回落 JVM 通道(header+query+cookie)
                    response = null;
                    if ("GET".equalsIgnoreCase(method) && browserRiskExecutor != null) {
                        response = browserRiskExecutor.executeGetInBrowser(retryUrl, challenge.getId());
                        if (response != null) {
                            log.info("Gaia 重试经浏览器通道执行: status={}", response.getStatus());
                        }
                    }
                    if (response == null) {
                        response = "GET".equalsIgnoreCase(method)
                                ? (requestIdentity == null ? httpPipeline.get(retryUrl, retryHeaders, "bilibili-api-gaia-retry")
                                    : httpPipeline.getWithIdentity(retryUrl, new RequestIdentitySnapshot(retryHeaders,
                                        requestIdentity.getBuvid3(), requestIdentity.getUid(), requestIdentity.getSourceChallengeId()), "bilibili-api-gaia-retry"))
                                : httpPipeline.postForm(retryUrl, retryHeaders, params, "bilibili-api-gaia-retry");
                    }
                    if (!response.successful()) throw new NetworkException(response.getStatus());
                    result = response.json();
                    code = result.getInteger("code");
                    if (selectedIdentity != null && code != null && code == 0
                            && !"browser".equals(response.getRequest().getTransport()) && response.getRequest().getIdentity() != null) {
                        selectedIdentity.set(response.getRequest().getIdentity());
                    }
                    if (selectedIdentity != null && "browser".equals(response.getRequest().getTransport()) && code != null && code == 0) {
                        RequestIdentitySnapshot observed = response.getRequest().getIdentity();
                        if (observed == null || !challenge.getId().equals(observed.getSourceChallengeId())
                                || (requestIdentity != null && !requestIdentity.getUid().isBlank()
                                    && !requestIdentity.getUid().equals(observed.getUid()))) {
                            throw new ResponseCodeException(-352, "Browser response identity is unavailable or belongs to another account");
                        }
                        selectedIdentity.set(observed);
                    }
                }
            }
            if (code != 0) {
                // 1024 is the transient timeout response used by current Live Web APIs.
                if (code == 4101130 || code == 4101131 || code == 4101132 || code == 22015 || code == 1024) {
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

    private String responseHeader(BilibiliHttpResponse response, String name) {
        return response.getHeaders().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }

    private void synchronizeCookiesFromStore() {
        Cookies persisted = credentialFileStore.loadCookies();
        if (persisted != null && StringUtils.isNotBlank(persisted.getSessData())) {
            cookies = persisted;
        }
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
        if (StringUtils.isEmpty(url)) {
            return Optional.empty();
        }

        BilibiliNetworkLogger.HttpTrace trace = networkLog.httpRequest("bilibili-image", "GET", url, headers, null);
        try {
            Optional<BufferedImage> image = http.getBufferedImage(url, headers);
            networkLog.httpResponse(trace, image.isPresent() ? 200 : 204, Collections.emptyMap(),
                    image.<Object>map(value -> Map.of("width", value.getWidth(), "height", value.getHeight()))
                            .orElse("<empty image>"));
            return image;
        } catch (RuntimeException e) {
            networkLog.httpFailure(trace, e);
            throw e;
        }
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
        if (StringUtils.isEmpty(url)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        BilibiliNetworkLogger.HttpTrace trace = networkLog.httpRequest("bilibili-image-async", "GET", url, headers, null);
        return http.asyncGetBufferedImage(url, headers).whenComplete((image, error) -> {
            if (error != null) {
                networkLog.httpFailure(trace, error);
            } else {
                networkLog.httpResponse(trace, image.isPresent() ? 200 : 204, Collections.emptyMap(),
                        image.<Object>map(value -> Map.of("width", value.getWidth(), "height", value.getHeight()))
                                .orElse("<empty image>"));
            }
        });
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
        JSONObject result = requestBilibiliApi(api, "POST", new HashMap<>(), new HashMap<>());
        String ticket = result.getString("ticket");
        Integer ticketExpires = result.getInteger("created_at") + result.getInteger("ttl");

        String img = result.getJSONObject("nav").getString("img");
        String sub = result.getJSONObject("nav").getString("sub");
        String imgKey = img.substring(img.lastIndexOf("/") + 1, img.lastIndexOf("."));
        String subKey = sub.substring(sub.lastIndexOf("/") + 1, sub.lastIndexOf("."));

        sign = new WebSign(ticket, ticketExpires, imgKey, subKey);
        webSignUpdatedAtMillis.set(System.currentTimeMillis());
        cookies.setBiliTicket(ticket);
        cookies.setBiliTicketExpires(ticketExpires.longValue());
        credentialFileStore.saveCookies(cookies);

        return sign;
    }

    /** Keep the JVM-owned Web ticket valid using the same 30 minute safety window as current Web clients. */
    public synchronized WebSign ensureBilibiliWebSign() {
        long now = System.currentTimeMillis() / 1000;
        if (sign != null && sign.getTicketExpires() > now + 1800) {
            log.debug("跳过 Bilibili Web ticket 更新: 当前 ticket 仍在安全窗口内, expires={}, remainingSeconds={}",
                    sign.getTicketExpires(), sign.getTicketExpires() - now);
            return sign;
        }
        log.info("准备更新 Bilibili Web ticket: reason={}",
                sign == null ? "JVM 尚无 ticket/WBI 状态" : "ticket 距到期不足 30 分钟");
        return generateBilibiliWebSign();
    }

    public long getWebSignUpdatedAtMillis() {
        return webSignUpdatedAtMillis.get();
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

            try {
                credentialFileStore.saveCookies(cookies);
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
    public String getLoginUid() {
        String api = "https://api.bilibili.com/x/space/v2/myinfo";
        JSONObject result = requestBilibiliApi(api);
        JSONObject profile = result.getJSONObject("profile");
        return profile.getString("mid");
    }

    /**
     * 根据 UID 获取 UP 主信息
     * @param uid UID
     * @return UP 主信息
     */
    @Cacheable(value = "bilibiliApiCache", keyGenerator = "cacheKeyGenerator")
    public Up getUpInfoByUid(@NonNull String uid) {
        String api = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=" + uid;
        JSONObject result = requestBilibiliApi(api);

        JSONObject info = result.getJSONObject("info");
        String uname = info.getString("uname");
        String roomId = result.getString("room_id");
        String face = info.getString("face");
        return new Up(uid, uname, "0".equals(roomId) ? null : roomId, face);
    }

    /**
     * 根据房间号获取 UP 主信息
     * @param roomId 房间号
     * @return UP 主信息
     */
    @Cacheable(value = "bilibiliApiCache", keyGenerator = "cacheKeyGenerator")
    public Up getUpInfoByRoomId(@NonNull String roomId) {
        if ("0".equals(roomId)) {
            throw new IllegalArgumentException("房间号不能为 0");
        }

        String api = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId;
        JSONObject result = requestBilibiliApi(api);
        String uid = result.getString("uid");
        return getUpInfoByUid(uid);
    }

    /**
     * 根据 UID 获取 UP 主昵称
     * @param uid UID
     * @return UP 主昵称
     */
    public Optional<String> getUnameByUid(@NonNull String uid) {
        try {
            return Optional.ofNullable(((BilibiliApiService) AopContext.currentProxy()).getUpInfoByUid(uid).getUname());
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
    public Optional<String> getRoomIdByUid(@NonNull String uid) {
        try {
            return Optional.ofNullable(((BilibiliApiService) AopContext.currentProxy()).getUpInfoByUid(uid).getRoomId());
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
    public Optional<String> getFaceByUid(@NonNull String uid) {
        try {
            return Optional.ofNullable(((BilibiliApiService) AopContext.currentProxy()).getUpInfoByUid(uid).getFace());
        } catch (Exception e) {
            log.error("获取头像失败", e);
            return Optional.empty();
        }
    }

    String resignWbiUrl(String url) {
        WebSign currentSign = sign;
        if (!url.contains("w_rid=") || currentSign == null) return url;
        URI uri = URI.create(url);
        Map<String, Object> unsigned = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals("wts") || key.equals("w_rid")) continue;
            unsigned.put(key, URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath()
                + BilibiliWbiUtil.getWbiSign(unsigned, currentSign.getImgKey(), currentSign.getSubKey(), properties.getNetwork().isWbiEncodedQuery());
    }

    /**
     * 获取直播间连接信息
     * @param roomId 房间号
     * @return 直播间连接信息
     */
    public ConnectInfo getLiveRoomConnectInfo(@NonNull String roomId) {
        return getLiveRoomConnectInfo(roomId, false);
    }

    /**
     * preferBrowserIdentity: 该会话曾以浏览器身份连接成功(-352 后), 其重连继续沿用浏览器身份;
     * 否则: -352 熔断激活期间或登记表中已有验证身份时优先浏览器通道, 常规情况优先 JVM 身份。
     */
    public ConnectInfo getLiveRoomConnectInfo(@NonNull String roomId, boolean preferBrowserIdentity) {
        RequestIdentitySnapshot initial = RequestIdentitySnapshot.jvm(httpPipeline.snapshotJvmHeaders(
                    "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo", getBilibiliHeaders()));
        // 浏览器身份登记优先: 曾验证成功 → 直接走浏览器通道取回绑定该身份的连接信息;
        // 浏览器身份也被 -352 拒绝时清除登记, 落回 JVM 全流程(含 WBI 刷新与 Gaia 解算)
        RequestIdentitySnapshot verified = identityRegistry == null ? null : identityRegistry.lastVerified();
        if (verified != null && browserRiskExecutor != null) {
            Map<String, Object> stickyParams = new HashMap<>();
            stickyParams.put("id", roomId);
            String stickyUrl = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
                    + BilibiliWbiUtil.getWbiSign(stickyParams, sign.getImgKey(), sign.getSubKey(),
                    properties.getNetwork().isWbiEncodedQuery());
            var browserResponse = browserRiskExecutor.executeGetInBrowser(stickyUrl, "sticky-browser-identity");
            if (browserResponse != null && browserResponse.successful()) {
                var json = browserResponse.json();
                var code = json.getInteger("code");
                var observed = browserResponse.getRequest().getIdentity();
                if (code != null && code == 0) {
                    if (identityRegistry != null && observed != null && observed.isBrowser()) {
                        identityRegistry.recordVerified(observed);
                    }
                    var data = json.getJSONObject("data");
                    if (data != null) {
                        List<ConnectAddress> harvested = data.getJSONArray("host_list")
                                .toJavaList(ConnectAddress.class);
                        ConnectInfo info = new ConnectInfo(data.getString("token"), harvested);
                        info.setIdentity(observed != null ? observed : verified);
                        return info;
                    }
                } else if (code != null && code == -352 && identityRegistry != null) {
                    identityRegistry.clear();
                }
            }
        }
        RequestIdentitySnapshot selectedInitial = preferBrowserIdentity && verified != null ? verified : initial;
        AtomicReference<RequestIdentitySnapshot> selected = new AtomicReference<>(selectedInitial);
        Map<String, Object> params = new HashMap<>();
        params.put("id", roomId);

        String api = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo" +
                BilibiliWbiUtil.getWbiSign(params, sign.getImgKey(), sign.getSubKey(), properties.getNetwork().isWbiEncodedQuery());
        JSONObject result = requestBilibiliApi(api, "GET", initial.getHeaders(), Collections.emptyMap(), selected);

        String token = result.getString("token");
        List<ConnectAddress> addresses = result.getJSONArray("host_list").toJavaList(ConnectAddress.class);
        ConnectInfo connectInfo = new ConnectInfo(token, addresses);
        connectInfo.setIdentity(selected.get());
        return connectInfo;
    }

    /**
     * 直播间 Web 心跳包
     * @param roomId 房间号
     */
    public void liveRoomHeartbeat(@NonNull String roomId) {
        String api = "https://live-trace.bilibili.com/xlive/rdata-interface/v1/heartbeat/webHeartBeat?pf=web&hb=";
        String hbParam = Base64.getEncoder().encodeToString(("60|" + roomId + "|1|0").getBytes(StandardCharsets.UTF_8));
        String url = api + hbParam;
        Map<String, String> headers = getBilibiliHeaders();
        BilibiliNetworkLogger.HttpTrace trace = networkLog.httpRequest("bilibili-web-heartbeat", "GET", url, headers, null);
        http.asyncGet(url, headers).whenComplete((response, exception) -> {
            if (exception != null) {
                networkLog.httpFailure(trace, exception);
                if (isTimeoutFailure(exception)) {
                    log.warn("直播间 {} 发送 Web 心跳包超时，本次遥测已跳过", roomId);
                    log.debug("Web 心跳包超时详情: room={}", roomId, exception);
                } else {
                    log.error("直播间 {} 发送 Web 心跳包异常", roomId, exception);
                }
            } else {
                networkLog.httpResponse(trace, 200, Collections.emptyMap(), response);
            }
        });
    }

    private boolean isTimeoutFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    /**
     * 获取直播间最新弹幕
     * @param roomId 房间号
     * @return 最新弹幕列表
     */
    public List<Danmu> getLiveRoomLatestDanmus(@NonNull String roomId) {
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

                String uid = userInfo.getString("uid");
                String uname = baseInfo.getString("name");
                String face = baseInfo.getString("face");

                JSONArray fansMedalInfo = message.getJSONArray("medal");
                FansMedal fansMedal = null;
                if (!fansMedalInfo.isEmpty()) {
                    String fansMedalUid = fansMedalInfo.getString(12);
                    String fansMedalUname = fansMedalInfo.getString(2);
                    String fansMedalRoomId = fansMedalInfo.getString(3);
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
                String replyUid = replyInfo.getString("reply_mid");
                if (!"0".equals(replyUid)) {
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
            String giftId = giftInfo.getString("id");
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
    public Map<String, Room> getLiveInfoByUids(Set<String> uids) {
        Map<String, Room> rooms = new HashMap<>();

        if (uids.size() == 1) {
            String uid = uids.iterator().next();
            Optional<String> optionalRoomId = getRoomIdByUid(uid);
            if (optionalRoomId.isPresent()) {
                Room room = getLiveInfoByRoomId(optionalRoomId.get());
                room.setUname(getUnameByUid(uid).orElseThrow());
                room.setFace(getFaceByUid(uid).orElseThrow());
                rooms.put(uid, room);
            }
            return rooms;
        }

        String api = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]=";

        List<List<String>> uidLists = org.apache.commons.collections4.ListUtils.partition(new ArrayList<>(uids), 100);
        for (List<String> uidList : uidLists) {
            JSONObject result = requestBilibiliApi(api + uidList.stream().map(String::valueOf).collect(Collectors.joining("&uids[]=")));
            for (String uidString : result.keySet()) {
                JSONObject roomInfo = result.getJSONObject(uidString);

                String uid = uidString;
                String uname = roomInfo.getString("uname");
                String roomId = roomInfo.getString("room_id");
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
     * Batch-load user profiles for report rankings and other optional enrichments.
     * A failed member is retained with a readable fallback name and does not abort
     * the remaining batches.
     */
    public Map<String, UserInfo> getUserInfoByUids(Set<String> uids) {
        if (CollectionUtils.isEmpty(uids)) return new HashMap<>();
        Map<String, UserInfo> infos = new HashMap<>();
        for (String uid : uids) infos.put(uid, new UserInfo(uid, "昵称获取失败"));
        for (List<String> part : org.apache.commons.collections4.ListUtils.partition(new ArrayList<>(uids), 10)) {
            String api = "https://api.vc.bilibili.com/account/v1/user/cards?uids=" +
                    part.stream().map(String::valueOf).collect(Collectors.joining(","));
            try {
                JSONArray results = requestBilibiliApiForArray(api);
                for (Object value : results) {
                    JSONObject info = (JSONObject) value;
                    String uid = info.getString("mid");
                    if (uid != null) infos.put(uid, new UserInfo(uid, info.getString("name"), info.getString("face")));
                }
            } catch (Exception error) {
                log.warn("批量获取 Bilibili 用户信息失败: uids={}, reason={}", part, error.toString());
            }
        }
        return infos;
    }

    /**
     * 根据房间号获取直播间信息，该接口无昵称及头像信息
     * @param roomId 房间号
     * @return 直播间信息
     */
    public Room getLiveInfoByRoomId(@NonNull String roomId) {
        if ("0".equals(roomId)) {
            throw new IllegalArgumentException("房间号不能为 0");
        }

        String api = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId;
        JSONObject result = requestBilibiliApi(api);

        String uid = result.getString("uid");
        String realRoomId = result.getString("room_id");
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
    public List<Up> getFollowingUps(String selfUid) {
        List<Up> ups = new ArrayList<>();

        int page = 1;

        while(true) {
            String api = "https://api.bilibili.com/x/relation/followings?vmid=" + selfUid + "&pn=" + page + "&ps=50";
            JSONObject result = requestBilibiliApi(api);

            JSONArray followings = result.getJSONArray("list");
            for (JSONObject following : followings.toList(JSONObject.class)) {
                String uid = following.getString("mid");
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
    public void followUp(String uid) {
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
    public int getFansCount(String uid) {
        String api = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=" + uid;
        JSONObject result = requestBilibiliApi(api);

        return result.getIntValue("follower_num");
    }

    /**
     * 获取粉丝团数(点亮粉丝勋章的粉丝数)
     * @param uid UID
     * @return 粉丝团数(点亮粉丝勋章的粉丝数)
     */
    public int getFansMedalCount(String uid) {
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
    public int getGuardCount(String uid, String roomId) {
        String api = "https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topListNew?roomid=" + roomId + "&page=1&ruid=" + uid + "&page_size=20&typ=5&platform=web";
        JSONObject result = requestBilibiliApi(api);

        JSONObject info = result.getJSONObject("info");
        return info.getIntValue("num");
    }

    /** Acknowledge the outer danmaku protocol sequence advertised by support_ack. */
    public void acknowledgeLiveRoomSequence(long sequence) {
        if (sequence <= 1 || StringUtils.isBlank(cookies.getBiliJct())) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("terminal", 0);
        body.put("sequence", sequence);
        body.put("csrf", cookies.getBiliJct());
        body.put("csrf_token", cookies.getBiliJct());
        try {
            httpPipeline.postMultipart(
                    "https://api.live.bilibili.com/xlive/open-interface/v1/dm/message_ack",
                    getBilibiliHeaders(), body, "bilibili-live-sequence-ack");
        } catch (Exception e) {
            log.warn("Bilibili 直播消息 sequence ACK 失败: sequence={}, reason={}", sequence, e.toString());
            log.debug("Bilibili live sequence ACK detail", e);
        }
    }

    /**
     * 获取直播报告的可选基线数据。每个接口独立容错，缺失字段表示未知，调用方不得按 0 处理。
     */
    public JSONObject getLiveReportBaseStats(String uid, String roomId) {
        JSONObject stats = new JSONObject();
        try {
            JSONObject room = requestBilibiliApi("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId);
            if (room.containsKey("attention")) stats.put("fans", room.getLong("attention"));
        } catch (Exception e) {
            log.warn("获取直播报告粉丝基线失败, uid={}, roomId={}", uid, roomId, e);
        }
        try {
            JSONObject medal = requestBilibiliApi("https://api.live.bilibili.com/xlive/app-ucenter/v1/fansMedal/fans_medal_info?target_id=" + uid + "&room_id=" + roomId);
            if (medal.containsKey("fans_medal_light_count")) stats.put("fans_medal", medal.getLong("fans_medal_light_count"));
        } catch (Exception e) {
            log.warn("获取直播报告粉丝团基线失败, uid={}, roomId={}", uid, roomId, e);
        }
        try {
            JSONObject guard = requestBilibiliApi("https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topListNew?roomid=" + roomId
                    + "&page=1&ruid=" + uid + "&page_size=1&typ=5&platform=web");
            JSONObject info = guard.getJSONObject("info");
            if (info != null && info.containsKey("num")) stats.put("guard", info.getLong("num"));
        } catch (Exception e) {
            log.warn("获取直播报告大航海基线失败, uid={}, roomId={}", uid, roomId, e);
        }
        return stats;
    }
}
