package com.starlwr.bot.bilibili.live.connection;

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
    private String host;

    /**
     * 端口
     */
    private int port;

    /**
     * wss 端口
     */
    @JSONField(name = "wss_port")
    private int wssPort;

    /**
     * ws 端口
     */
    @JSONField(name = "ws_port")
    private int wsPort;
}
