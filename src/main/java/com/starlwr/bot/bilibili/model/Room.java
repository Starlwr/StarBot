package com.starlwr.bot.bilibili.model;

import lombok.*;

/**
 * 直播间信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Room {
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
     * 直播间状态
     */
    private Integer liveStatus;

    /**
     * 直播开始时间
     */
    private Long liveStartTime;

    /**
     * 直播间标题
     */
    private String title;

    /**
     * 直播间封面
     */
    private String cover;
}
