package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 大航海类型
 */
@Getter
@AllArgsConstructor
public enum GuardType {
    UNKNOWN(-1, "未知"),
    Governor(1, "总督"),
    Commander(2, "提督"),
    Captain(3, "舰长");

    private final int code;
    private final String name;

    public static GuardType of(int code) {
        for (GuardType guardType : GuardType.values()) {
            if (guardType.code == code) {
                return guardType;
            }
        }

        return UNKNOWN;
    }
}
