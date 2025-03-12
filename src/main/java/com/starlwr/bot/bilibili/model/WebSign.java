package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bilibili Web Api 签名
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSign {
    /**
     * Web Ticket
     */
    private String ticket;

    /**
     * Web Ticket 过期时间戳
     */
    private Integer ticketExpires;

    /**
     * Wbi 签名 imgKey
     */
    private String imgKey;

    /**
     * Wbi 签名 subKey
     */
    private String subKey;
}
