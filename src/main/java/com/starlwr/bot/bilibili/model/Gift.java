package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 礼物
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gift {
    /**
     * 礼物 ID
     */
    private Long id;

    /**
     * 礼物名称
     */
    private String name;

    /**
     * 礼物单价
     */
    private Double price;

    /**
     * 礼物图片 URL
     */
    private String url;
}
