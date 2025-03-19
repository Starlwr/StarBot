package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据包类型
 */
@Getter
@AllArgsConstructor
public enum DataPackType {
    HEARTBEAT(2),
    HEARTBEAT_RESPONSE(3),
    NOTICE(5),
    VERIFY(7),
    VERIFY_SUCCESS_RESPONSE(8);

    private final int code;
}
