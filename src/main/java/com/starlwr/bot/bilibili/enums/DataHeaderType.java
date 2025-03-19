package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据头类型
 */
@Getter
@AllArgsConstructor
public enum DataHeaderType {
    RAW_JSON(0),
    HEARTBEAT(1),
    BROTLI_JSON(3);

    private final int code;
}
