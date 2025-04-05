package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.bilibili.enums.GuardType;
import lombok.*;

/**
 * 大航海
 */
@Data
@NoArgsConstructor
public class Guard {
    /**
     * 大航海类型
     */
    private GuardType guardType;

    /**
     * 图标
     */
    private String icon;

    public Guard(GuardType guardType) {
        this.guardType = guardType;
    }

    public Guard(GuardType guardType, String icon) {
        this.guardType = guardType;
        this.icon = icon;
    }

    public Guard(Integer guardType) {
        this.guardType = GuardType.of(guardType);
    }

    public Guard(Integer guardType, String icon) {
        this.guardType = GuardType.of(guardType);
        this.icon = icon;
    }
}
