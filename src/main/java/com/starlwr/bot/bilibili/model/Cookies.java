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

    public Cookies() {
        this.sessData = "";
        this.biliJct = "";
        this.buvid3 = "";
    }

    public Cookies(String sessData, String biliJct, String buvid3) {
        this.sessData = sessData;
        this.biliJct = biliJct;
        this.buvid3 = buvid3;
    }
}
