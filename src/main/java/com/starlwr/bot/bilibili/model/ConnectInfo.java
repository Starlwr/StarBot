package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 直播间连接信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectInfo {
    /**
     * 直播间连接 Token
     */
    private String token;

    /**
     * 直播间连接地址
     */
    private List<ConnectAddress> addresses;
}
