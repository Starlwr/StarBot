package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 直播间连接地址
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectAddress {
    /**
     * 连接地址
     */
    String host;

    /**
     * 端口
     */
    int port;

    /**
     * wss 端口
     */
    @JSONField(name = "wss_port")
    int wssPort;

    /**
     * ws 端口
     */
    @JSONField(name = "ws_port")
    int wsPort;
}
