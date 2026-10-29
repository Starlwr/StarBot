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
    @Override public String toString() { return "RequestIdentitySnapshot[source=" + (isBrowser() ? "browser" : "jvm") + "]"; }
}
