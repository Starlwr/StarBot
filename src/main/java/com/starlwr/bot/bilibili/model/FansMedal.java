package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 粉丝勋章
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FansMedal {
    /**
     * UID
     */
    private Long uid;

    /**
     * 昵称
     */
    private String uname;

    /**
     * 房间号
     */
    private Long roomId;

    /**
     * 头像
     */
    private String face;

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
        this.uid = uid;
        this.uname = uname;
        this.roomId = roomId;
        this.name = name;
        this.level = level;
        this.isLighted = isLighted;
    }
}
