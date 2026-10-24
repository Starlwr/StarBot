package com.starlwr.bot.bilibili.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 弹幕类型
 */
@Getter
@AllArgsConstructor
public enum DanmuType {
    UNKNOWN(-1, "未知"),
    NORMAL(0, "普通弹幕"),
    EMOJI(1, "表情弹幕");

    private final int code;
    private final String name;

    public static DanmuType of(int code) {
        for (DanmuType guardType : DanmuType.values()) {
            if (guardType.code == code) {
                return guardType;
            }
        }

        return UNKNOWN;
    }
}
