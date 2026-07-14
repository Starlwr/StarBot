package com.starlwr.bot.bilibili.log;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Structured diagnostics for Bilibili HTTP and WebSocket traffic. */
@StarBotComponent
public class BilibiliNetworkLogger {
    private static final Logger HTTP_LOG = LoggerFactory.getLogger("com.starlwr.bot.bilibili.network.http");
    private static final Logger WS_LOG = LoggerFactory.getLogger("com.starlwr.bot.bilibili.network.websocket");
    private static final Logger LOG = LoggerFactory.getLogger(BilibiliNetworkLogger.class);
    private static final Set<String> AVAILABLE_CATEGORIES = Set.of(
            "credential", "dynamic", "live-api", "live-ws", "heartbeat", "image", "onebot", "api", "other");
    private static final int MAX_DUPLICATE_ENTRIES = 8192;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)^(authorization|cookie|set-cookie|sessdata|bili_jct|csrf|refresh_csrf|refresh_token|ac_time_value|access_token|token|key|qrcode_key)$");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:authorization|cookie|set-cookie|sessdata|bili_jct|csrf|refresh_csrf|refresh_token|ac_time_value|access_token|token|key|qrcode_key)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");
    private static final Pattern PARAM_SECRET = Pattern.compile(
            "(?i)((?:authorization|cookie|set-cookie|sessdata|bili_jct|csrf|refresh_csrf|refresh_token|ac_time_value|access_token|token|key|qrcode_key)=)[^&;\\s]*");

    private final StarBotBilibiliProperties properties;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<String, DuplicateState> duplicateStates = new HashMap<>();
    private long duplicateOperations;

    public BilibiliNetworkLogger(StarBotBilibiliProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void reportUnsafeLogging() {
        StarBotBilibiliProperties.Network network = properties.getNetwork();
        if (network.isIncludeSensitiveData()
                && (network.isHttpLogEnabled() || network.isWebsocketLogEnabled())) {
            LOG.warn("Bilibili DEBUG 网络日志已启用敏感数据原样输出；Cookie、Token 和刷新凭据可被用于重放，请限制日志访问范围");
        }
        if (network.isHttpLogEnabled() || network.isWebsocketLogEnabled()) {
            LOG.info("Bilibili DEBUG 控制台采集日志配置: categories={}, deduplicate={}, deduplicateSeconds={}, availableCategories={}",
                    network.getConsoleCategories(), network.isConsoleDeduplicate(),
                    duplicateWindowSeconds(), AVAILABLE_CATEGORIES);
        }
    }

    public HttpTrace httpRequest(String channel, String method, String url,
                                 Map<String, ?> headers, Object body) {
        String category = classifyHttp(channel, url);
        HttpTrace trace = new HttpTrace(requestSequence.incrementAndGet(), channel, category, method,
                url, System.nanoTime());
        if (properties.getNetwork().isHttpLogEnabled() && HTTP_LOG.isDebugEnabled()) {
            String safeUrl = sanitizeText(url);
            String safeHeaders = formatHeaders(headers);
            String safeBody = formatBody(body);
            debug(HTTP_LOG, category,
                    String.join("|", "HTTP OUT", channel, method, safeUrl, safeHeaders, safeBody),
                    "HTTP OUT id=" + trace.id() + " category=" + category + " channel=" + channel
                            + " method=" + method + " url=" + safeUrl + " headers=" + safeHeaders + " body=" + safeBody);
        }
        return trace;
    }

    public void httpResponse(HttpTrace trace, int status, Map<String, ?> headers, Object body) {
        if (properties.getNetwork().isHttpLogEnabled() && HTTP_LOG.isDebugEnabled()) {
            String safeHeaders = formatHeaders(headers);
            String safeBody = formatBody(body);
            debug(HTTP_LOG, trace.category(),
                    String.join("|", "HTTP IN", trace.channel(), trace.url(), String.valueOf(status), safeHeaders, safeBody),
                    "HTTP IN  id=" + trace.id() + " category=" + trace.category() + " channel=" + trace.channel()
                            + " status=" + status + " durationMs=" + trace.elapsedMillis()
                            + " headers=" + safeHeaders + " body=" + safeBody);
        }
    }

    public void httpFailure(HttpTrace trace, Throwable error) {
        if (properties.getNetwork().isHttpLogEnabled()) {
            HTTP_LOG.warn("HTTP ERR id={} category={} channel={} method={} url={} durationMs={} error={}",
                    trace.id(), trace.category(), trace.channel(), trace.method(), sanitizeText(trace.url()),
                    trace.elapsedMillis(), error.toString());
        }
    }

    public void websocketOut(String channel, Long roomId, String kind, int bytes, Object body, boolean heartbeat) {
        websocket("OUT", channel, roomId, kind, bytes, body, heartbeat);
    }

    public void websocketIn(String channel, Long roomId, String kind, int bytes, Object body, boolean heartbeat) {
        websocket("IN ", channel, roomId, kind, bytes, body, heartbeat);
    }

    private void websocket(String direction, String channel, Long roomId, String kind,
                           int bytes, Object body, boolean heartbeat) {
        StarBotBilibiliProperties.Network network = properties.getNetwork();
        if (!network.isWebsocketLogEnabled() || (heartbeat && !network.isWebsocketHeartbeatLogEnabled())
                || !WS_LOG.isDebugEnabled()) {
            return;
        }
        String category = classifyWebsocket(channel, heartbeat);
        String safeBody = formatBody(body);
        String safeRoom = roomId == null ? "-" : roomId.toString();
        debug(WS_LOG, category,
                String.join("|", "WS " + direction, channel, safeRoom, kind, String.valueOf(bytes), safeBody),
                "WS " + direction + " category=" + category + " channel=" + channel + " room=" + safeRoom
                        + " kind=" + kind + " bytes=" + bytes + " body=" + safeBody);
    }

    private void debug(Logger logger, String category, String stableContent, String renderedMessage) {
        if (!categoryEnabled(category)) {
            return;
        }
        StarBotBilibiliProperties.Network network = properties.getNetwork();
        if (!network.isConsoleDeduplicate()) {
            logger.debug(renderedMessage);
            return;
        }

        long now = System.nanoTime();
        long windowNanos = Duration.ofSeconds(duplicateWindowSeconds()).toNanos();
        String fingerprint = fingerprint(category + '\0' + stableContent);
        DuplicateDecision decision;
        synchronized (duplicateStates) {
            cleanupDuplicateStates(now, windowNanos);
            DuplicateState state = duplicateStates.get(fingerprint);
            if (state == null || now - state.lastSeenNanos > windowNanos) {
                duplicateStates.put(fingerprint, new DuplicateState(now));
                decision = DuplicateDecision.FULL;
            } else if (now - state.windowStartedNanos >= windowNanos) {
                state.windowStartedNanos = now;
                state.lastSeenNanos = now;
                int suppressed = state.suppressed + 1;
                state.suppressed = 0;
                state.noticeEmitted = false;
                decision = new DuplicateDecision(DuplicateAction.SUMMARY, suppressed);
            } else {
                state.lastSeenNanos = now;
                state.suppressed++;
                if (!state.noticeEmitted) {
                    state.noticeEmitted = true;
                    decision = DuplicateDecision.NOTICE;
                } else {
                    decision = DuplicateDecision.SUPPRESS;
                }
            }
        }

        switch (decision.action()) {
            case FULL -> logger.debug(renderedMessage);
            case NOTICE -> logger.debug("DEBUG category={} 日志内容未变化；接下来 {} 秒内相同内容将静默抑制, fingerprint={}",
                    category, duplicateWindowSeconds(), fingerprint.substring(0, 12));
            case SUMMARY -> logger.debug("DEBUG category={} 日志内容仍未变化；过去 {} 秒已抑制 {} 条重复日志, fingerprint={}",
                    category, duplicateWindowSeconds(), decision.suppressed(), fingerprint.substring(0, 12));
            case SUPPRESS -> { }
        }
    }

    private boolean categoryEnabled(String category) {
        Set<String> categories = properties.getNetwork().getConsoleCategories();
        if (categories == null || categories.isEmpty()) {
            return false;
        }
        return categories.stream().filter(value -> value != null)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals("all") || value.equals(category));
    }

    private String classifyHttp(String channel, String url) {
        String normalizedChannel = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        String normalizedUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (normalizedChannel.contains("onebot")) return "onebot";
        if (normalizedChannel.contains("live-ws")) return "live-ws";
        if (normalizedChannel.contains("credential") || normalizedUrl.contains("passport.bilibili.com")
                || normalizedUrl.contains("/correspond/")) return "credential";
        if (normalizedChannel.contains("heartbeat") || normalizedUrl.contains("live-trace.bilibili.com")) return "heartbeat";
        if (normalizedChannel.contains("image")) return "image";
        if (normalizedUrl.contains("web-dynamic") || normalizedUrl.contains("dynamic_svr")
                || normalizedUrl.contains("/x/relation/followings")) return "dynamic";
        if (normalizedUrl.contains("api.live.bilibili.com") || normalizedUrl.contains("xlive/")) return "live-api";
        if (normalizedChannel.startsWith("bilibili-api")) return "api";
        return "other";
    }

    private String classifyWebsocket(String channel, boolean heartbeat) {
        if (heartbeat) return "heartbeat";
        if (channel != null && channel.toLowerCase(Locale.ROOT).contains("onebot")) return "onebot";
        if (channel != null && channel.toLowerCase(Locale.ROOT).contains("bilibili-live")) return "live-ws";
        return "other";
    }

    private long duplicateWindowSeconds() {
        return Math.min(7 * 24 * 60 * 60L,
                Math.max(1, properties.getNetwork().getConsoleDeduplicateSeconds()));
    }

    private String fingerprint(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void cleanupDuplicateStates(long now, long windowNanos) {
        duplicateOperations++;
        if (duplicateOperations % 256 != 0 && duplicateStates.size() < MAX_DUPLICATE_ENTRIES) {
            return;
        }
        duplicateStates.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos > windowNanos * 2);
        if (duplicateStates.size() >= MAX_DUPLICATE_ENTRIES) {
            int removeCount = duplicateStates.size() - MAX_DUPLICATE_ENTRIES / 2;
            var iterator = duplicateStates.keySet().iterator();
            while (removeCount-- > 0 && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private String formatHeaders(Map<String, ?> headers) {
        if (!properties.getNetwork().isLogHeaders()) {
            return "<disabled>";
        }
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        if (properties.getNetwork().isIncludeSensitiveData()) {
            return limit(JSON.toJSONString(headers));
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        headers.forEach((key, value) -> safe.put(key, SENSITIVE_KEY.matcher(key).matches() ? "<redacted>" : value));
        return limit(JSON.toJSONString(safe));
    }

    private String formatBody(Object body) {
        if (body == null) {
            return "<empty>";
        }
        Object value = body;
        if (!properties.getNetwork().isIncludeSensitiveData() && body instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(String.valueOf(key),
                    SENSITIVE_KEY.matcher(String.valueOf(key)).matches() ? "<redacted>" : item));
            value = safe;
        }
        String text = value instanceof CharSequence ? value.toString() : JSON.toJSONString(value);
        return limit(sanitizeText(text));
    }

    private String sanitizeText(String text) {
        if (text == null || properties.getNetwork().isIncludeSensitiveData()) {
            return text;
        }
        return PARAM_SECRET.matcher(JSON_SECRET.matcher(text).replaceAll("$1<redacted>$2"))
                .replaceAll("$1<redacted>");
    }

    private String limit(String text) {
        if (text == null) {
            return "<null>";
        }
        int max = properties.getNetwork().getLogMaxBodyLength();
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...<truncated " + (text.length() - max) + " chars>";
    }

    public record HttpTrace(long id, String channel, String category, String method, String url, long startedNanos) {
        public long elapsedMillis() {
            return (System.nanoTime() - startedNanos) / 1_000_000L;
        }
    }

    private static final class DuplicateState {
        private long windowStartedNanos;
        private long lastSeenNanos;
        private int suppressed;
        private boolean noticeEmitted;

        private DuplicateState(long now) {
            this.windowStartedNanos = now;
            this.lastSeenNanos = now;
        }
    }

    private enum DuplicateAction { FULL, NOTICE, SUMMARY, SUPPRESS }

    private record DuplicateDecision(DuplicateAction action, int suppressed) {
        private static final DuplicateDecision FULL = new DuplicateDecision(DuplicateAction.FULL, 0);
        private static final DuplicateDecision NOTICE = new DuplicateDecision(DuplicateAction.NOTICE, 0);
        private static final DuplicateDecision SUPPRESS = new DuplicateDecision(DuplicateAction.SUPPRESS, 0);
    }
}
