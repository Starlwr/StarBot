package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 直播间连接状态
 */
@Getter
@AllArgsConstructor
public enum ConnectStatus {
    INIT(0, "初始化"),
    CONNECTING(1, "连接中"),
    CONNECTED(2, "已连接"),
    CLOSING(3, "断开连接中"),
    CLOSED(4, "已断开"),
    TIMEOUT(5, "心跳响应超时"),
    ERROR(6, "错误"),
    RISK(7, "直播间数据风控");

    private final int code;
    private final String msg;
}
