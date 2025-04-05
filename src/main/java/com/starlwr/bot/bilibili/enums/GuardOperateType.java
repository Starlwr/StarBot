package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 大航海操作类型
 */
@Getter
@AllArgsConstructor
public enum GuardOperateType {
    UNKNOWN(-1, "未知"),
    ACTIVATION(1, "开通"),
    RENEWAL(2, "续费");

    private final int code;
    private final String name;

    public static GuardOperateType of(int code) {
        for (GuardOperateType guardOperateType : GuardOperateType.values()) {
            if (guardOperateType.code == code) {
                return guardOperateType;
            }
        }

        return UNKNOWN;
    }
}
