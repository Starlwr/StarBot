package com.starlwr.bot.bilibili.credential;

import java.util.Map;
import java.util.Objects;

/** Run-only request/session identity. Never put this object in CredentialEnvelope. */
public final class RequestIdentitySnapshot {
    private final Map<String, String> headers;
    private final String buvid3;
    private final String uid;
    private final String sourceChallengeId;
    public RequestIdentitySnapshot(Map<String, String> headers, String buvid3, String uid, String sourceChallengeId) {
        this.headers = Map.copyOf(headers);
        this.buvid3 = Objects.requireNonNullElse(buvid3, "");
        this.uid = Objects.requireNonNullElse(uid, "");
        this.sourceChallengeId = sourceChallengeId;
    }
    public static RequestIdentitySnapshot jvm(Map<String, String> headers) {
        String cookie = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Cookie"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        String buvid = "", uid = "";
        for (String part : cookie.split(";")) {
            String value = part.trim();
            if (value.startsWith("buvid3=")) buvid = value.substring(7);
            if (value.startsWith("DedeUserID=")) uid = value.substring("DedeUserID=".length());
        }
        return new RequestIdentitySnapshot(headers, buvid, uid, null);
    }
    public Map<String, String> getHeaders() { return headers; }
    public String getBuvid3() { return buvid3; }
    public String getUid() { return uid; }
    public String getSourceChallengeId() { return sourceChallengeId; }
    public boolean isBrowser() { return sourceChallengeId != null && !sourceChallengeId.isBlank(); }

    /**
     * 判定登记的浏览器身份是否已过期: Bilibili Web 会自行轮换 buvid3/buvid4/bili_ticket/bili_jct,
     * 当前浏览器 jar 的任一关键字段与本快照不一致(轮换/新增)即视为旧快照作废, 应回落 JVM 身份。
     * current 为空或关键字段被删除时不可证明旧快照有效，回落 JVM。
     */
    public boolean keyIdentityDrifted(Map<String, String> currentCookies) {
        if (currentCookies == null || currentCookies.isEmpty()) return true;
        Map<String, String> recorded = new java.util.HashMap<>();
        for (String part : headers.getOrDefault("Cookie", "").split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) recorded.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        for (String key : KEY_IDENTITY_COOKIES) {
            String old = recorded.get(key);
            String now = currentCookies.get(key);
            if (!Objects.equals(old, now)) return true;
        }
        return false;
    }

    private static final java.util.List<String> KEY_IDENTITY_COOKIES =
            java.util.List.of("buvid3", "buvid4", "bili_ticket", "bili_jct", "SESSDATA", "DedeUserID");

    @Override public String toString() { return "RequestIdentitySnapshot[source=" + (isBrowser() ? "browser" : "jvm") + "]"; }
}
