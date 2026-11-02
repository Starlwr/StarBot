package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 直播间连接信息
 */
@Data
@NoArgsConstructor

public class ConnectInfo {
    /**
     * 直播间连接 Token
     */
    private String token;

    @com.alibaba.fastjson2.annotation.JSONField(serialize = false, deserialize = false)
    @lombok.ToString.Exclude
    private RequestIdentitySnapshot identity;

    public ConnectInfo(String token, List<ConnectAddress> addresses) {
        this.token = token;
        this.addresses = addresses;
    }

    /**
     * 直播间连接地址
     */
    private List<ConnectAddress> addresses;
}
