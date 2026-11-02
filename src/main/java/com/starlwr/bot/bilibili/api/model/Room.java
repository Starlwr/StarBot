package com.starlwr.bot.bilibili.api.model;

import com.starlwr.bot.core.event.model.LiveStreamerInfo;
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

    /**
     * 直播分区
     */
    private String parentAreaName;

    /**
     * 子分区
     */
    private String areaName;

    public Integer getLiveStatus() {
        return liveStatus;
    }

    public Long getLiveStartTime() {
        return liveStartTime;
    }

    public Room(String uid, String uname, String roomId, Integer liveStatus, Long liveStartTime, String title, String cover) {
        this(uid, uname, roomId, liveStatus, liveStartTime, title, cover, null, null);
    }

    public Room(String uid, String uname, String roomId, String face, Integer liveStatus, Long liveStartTime, String title, String cover) {
        this(uid, uname, roomId, face, liveStatus, liveStartTime, title, cover, null, null);
    }

    public Room(String uid, String uname, String roomId, Integer liveStatus, Long liveStartTime, String title, String cover, String parentAreaName, String areaName) {
        super(uid, uname, roomId);
        this.liveStatus = liveStatus;
        this.liveStartTime = liveStartTime;
        this.title = title;
        this.cover = cover;
        this.parentAreaName = parentAreaName;
        this.areaName = areaName;
    }

    public Room(String uid, String uname, String roomId, String face, Integer liveStatus, Long liveStartTime, String title, String cover, String parentAreaName, String areaName) {
        super(uid, uname, roomId, face);
        this.liveStatus = liveStatus;
        this.liveStartTime = liveStartTime;
        this.title = title;
        this.cover = cover;
        this.parentAreaName = parentAreaName;
        this.areaName = areaName;
    }
}
