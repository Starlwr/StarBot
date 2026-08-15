package com.starlwr.bot.bilibili.model;

import lombok.Getter;
import lombok.Setter;

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

    /**
     * Cookie 中的 DedeUserID
     */
    private String dedeUserId;

    /**
     * Cookie 中的 DedeUserID__ckMd5
     */
    private String dedeUserIdCkMd5;

    /**
     * Cookie 中的 sid
     */
    private String sid;

    public Cookies() {
        this.sessData = "";
        this.biliJct = "";
        this.buvid3 = "";
        this.dedeUserId = "";
        this.dedeUserIdCkMd5 = "";
        this.sid = "";
    }

    public Cookies(String sessData, String biliJct, String buvid3, String dedeUserId, String dedeUserIdCkMd5, String sid) {
        this.sessData = sessData;
        this.biliJct = biliJct;
        this.buvid3 = buvid3;
        this.dedeUserId = dedeUserId;
        this.dedeUserIdCkMd5 = dedeUserIdCkMd5;
        this.sid = sid;
    }
}
