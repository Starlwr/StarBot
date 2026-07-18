package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bilibili Cookies
 */
@Getter
@Setter
public class Cookies {
    /**
     * Cookie 中的 SESSDATA
     */
    @JSONField(name = "SESSDATA", alternateNames = {"sessData", "sessdata"})
    private String sessData;

    /**
     * Cookie 中的 bili_jct
     */
    @JSONField(name = "bili_jct", alternateNames = {"biliJct"})
    private String biliJct;

    /**
     * Cookie 中的 buvid3
     */
    @JSONField(name = "buvid3")
    private String buvid3;

    @JSONField(name = "buvid4")
    private String buvid4;

    @JSONField(name = "DedeUserID", alternateNames = {"dedeUserId", "dedeuserid"})
    private String dedeUserId;

    /** Cookie refresh token, named ac_time_value by bilibili-api-python. */
    @JSONField(name = "ac_time_value", alternateNames = {"acTimeValue", "refresh_token", "refreshToken"})
    private String acTimeValue;

    private String bNut;

    private String biliTicket;

    private Long biliTicketExpires;

    private Long issuedAtEpochSeconds;
    private Long expiresAtEpochSeconds;
    private Long nextRefreshAtEpochSeconds;
    private Long lastValidatedAtEpochSeconds;
    private Long validationLeaseExpiresAtEpochSeconds;
    private Boolean serverRefreshRequired;
    private Long serverRefreshCheckedAtEpochSeconds;
    private Long serverRefreshTimestampMillis;
    private Integer refreshFailureCount;
    private Long refreshRetryAfterEpochSeconds;

    /** Forward-compatible cookie values not yet modelled explicitly. */
    private Map<String, String> extraCookies;

    public Cookies() {
        this.sessData = "";
        this.biliJct = "";
        this.buvid3 = "";
        this.buvid4 = "";
        this.dedeUserId = "";
        this.acTimeValue = "";
        this.bNut = "";
        this.biliTicket = "";
        this.biliTicketExpires = 0L;
        this.issuedAtEpochSeconds = 0L;
        this.expiresAtEpochSeconds = 0L;
        this.nextRefreshAtEpochSeconds = 0L;
        this.lastValidatedAtEpochSeconds = 0L;
        this.validationLeaseExpiresAtEpochSeconds = 0L;
        this.serverRefreshRequired = null;
        this.serverRefreshCheckedAtEpochSeconds = 0L;
        this.serverRefreshTimestampMillis = 0L;
        this.refreshFailureCount = 0;
        this.refreshRetryAfterEpochSeconds = 0L;
        this.extraCookies = new LinkedHashMap<>();
    }

    public Cookies(String sessData, String biliJct, String buvid3) {
        this.sessData = sessData;
        this.biliJct = biliJct;
        this.buvid3 = buvid3;
        this.buvid4 = "";
        this.dedeUserId = "";
        this.acTimeValue = "";
        this.bNut = "";
        this.biliTicket = "";
        this.biliTicketExpires = 0L;
        this.issuedAtEpochSeconds = 0L;
        this.expiresAtEpochSeconds = 0L;
        this.nextRefreshAtEpochSeconds = 0L;
        this.lastValidatedAtEpochSeconds = 0L;
        this.validationLeaseExpiresAtEpochSeconds = 0L;
        this.serverRefreshRequired = null;
        this.serverRefreshCheckedAtEpochSeconds = 0L;
        this.serverRefreshTimestampMillis = 0L;
        this.refreshFailureCount = 0;
        this.refreshRetryAfterEpochSeconds = 0L;
        this.extraCookies = new LinkedHashMap<>();
    }

    // Explicit accessors keep these Java-source properties visible to the K2
    // compiler before Lombok runs in Maven's mixed Java/Kotlin build.
    public String getSessData() { return sessData; }
    public void setSessData(String value) { sessData = value; }
    public String getBiliJct() { return biliJct; }
    public void setBiliJct(String value) { biliJct = value; }
    public String getBuvid3() { return buvid3; }
    public void setBuvid3(String value) { buvid3 = value; }
    public String getBuvid4() { return buvid4; }
    public void setBuvid4(String value) { buvid4 = value; }
    public String getDedeUserId() { return dedeUserId; }
    public void setDedeUserId(String value) { dedeUserId = value; }
    public String getAcTimeValue() { return acTimeValue; }
    public void setAcTimeValue(String value) { acTimeValue = value; }
    public String getBNut() { return bNut; }
    public void setBNut(String value) { bNut = value; }
    public String getBiliTicket() { return biliTicket; }
    public void setBiliTicket(String value) { biliTicket = value; }
    public Long getBiliTicketExpires() { return biliTicketExpires; }
    public void setBiliTicketExpires(Long value) { biliTicketExpires = value; }
    public Long getIssuedAtEpochSeconds() { return issuedAtEpochSeconds; }
    public void setIssuedAtEpochSeconds(Long value) { issuedAtEpochSeconds = value; }
    public Long getExpiresAtEpochSeconds() { return expiresAtEpochSeconds; }
    public void setExpiresAtEpochSeconds(Long value) { expiresAtEpochSeconds = value; }
    public Long getNextRefreshAtEpochSeconds() { return nextRefreshAtEpochSeconds; }
    public void setNextRefreshAtEpochSeconds(Long value) { nextRefreshAtEpochSeconds = value; }
    public Long getLastValidatedAtEpochSeconds() { return lastValidatedAtEpochSeconds; }
    public void setLastValidatedAtEpochSeconds(Long value) { lastValidatedAtEpochSeconds = value; }
    public Long getValidationLeaseExpiresAtEpochSeconds() { return validationLeaseExpiresAtEpochSeconds; }
    public void setValidationLeaseExpiresAtEpochSeconds(Long value) { validationLeaseExpiresAtEpochSeconds = value; }
    public Boolean getServerRefreshRequired() { return serverRefreshRequired; }
    public void setServerRefreshRequired(Boolean value) { serverRefreshRequired = value; }
    public Long getServerRefreshCheckedAtEpochSeconds() { return serverRefreshCheckedAtEpochSeconds; }
    public void setServerRefreshCheckedAtEpochSeconds(Long value) { serverRefreshCheckedAtEpochSeconds = value; }
    public Long getServerRefreshTimestampMillis() { return serverRefreshTimestampMillis; }
    public void setServerRefreshTimestampMillis(Long value) { serverRefreshTimestampMillis = value; }
    public Integer getRefreshFailureCount() { return refreshFailureCount; }
    public void setRefreshFailureCount(Integer value) { refreshFailureCount = value; }
    public Long getRefreshRetryAfterEpochSeconds() { return refreshRetryAfterEpochSeconds; }
    public void setRefreshRetryAfterEpochSeconds(Long value) { refreshRetryAfterEpochSeconds = value; }
    public Map<String, String> getExtraCookies() { return extraCookies; }
    public void setExtraCookies(Map<String, String> value) { extraCookies = value; }
}
