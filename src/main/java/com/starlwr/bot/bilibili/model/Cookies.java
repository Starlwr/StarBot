package com.starlwr.bot.bilibili.model;

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
    private String sessData;

    /**
     * Cookie 中的 bili_jct
     */
    private String biliJct;

    /**
     * Cookie 中的 buvid3
     */
    private String buvid3;

    private String buvid4;

    private String dedeUserId;

    /** Cookie refresh token, named ac_time_value by bilibili-api-python. */
    private String acTimeValue;

    private String bNut;

    private String biliTicket;

    private Long biliTicketExpires;

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
        this.extraCookies = new LinkedHashMap<>();
    }
}
