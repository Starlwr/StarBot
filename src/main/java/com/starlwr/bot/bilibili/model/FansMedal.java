package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 粉丝勋章
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class FansMedal extends LiveStreamerInfo {
    /**
     * 粉丝勋章名称
     */
    private String name;

    /**
     * 粉丝勋章等级
     */
    private Integer level;

    /**
     * 是否点亮
     */
    private Boolean isLighted;

    public FansMedal(Long uid, String uname, Long roomId, String name, Integer level, Boolean isLighted) {
        super(uid, uname, roomId);
        this.name = name;
        this.level = level;
        this.isLighted = isLighted;
    }

    public FansMedal(Long uid, String uname, Long roomId, String face, String name, Integer level, Boolean isLighted) {
        super(uid, uname, roomId, face);
        this.name = name;
        this.level = level;
        this.isLighted = isLighted;
    }
}
