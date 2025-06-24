package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.*;

/**
 * 直播间信息
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class Room extends LiveStreamerInfo {
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

    public Room(Long uid, String uname, Long roomId, Integer liveStatus, Long liveStartTime, String title, String cover) {
        super(uid, uname, roomId);
        this.liveStatus = liveStatus;
        this.liveStartTime = liveStartTime;
        this.title = title;
        this.cover = cover;
    }

    public Room(Long uid, String uname, Long roomId, String face, Integer liveStatus, Long liveStartTime, String title, String cover) {
        super(uid, uname, roomId, face);
        this.liveStatus = liveStatus;
        this.liveStartTime = liveStartTime;
        this.title = title;
        this.cover = cover;
    }
}
