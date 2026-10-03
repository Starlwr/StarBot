package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.bilibili.report.LiveReportDemandService;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * Bilibili 数据源事件监听器
 */
@Slf4j
@StarBotComponent
public class BilibiliDataSourceEventListener {
    private final StarBotBilibiliProperties properties;

    private final BilibiliLiveRoomService liveRoomService;

    private final BilibiliDynamicService dynamicService;

    private final LiveReportDemandService reportDemandService;

    private boolean loadCompleted = false;

    @Autowired
    public BilibiliDataSourceEventListener(StarBotBilibiliProperties properties, BilibiliLiveRoomService liveRoomService, BilibiliDynamicService dynamicService, LiveReportDemandService reportDemandService) {
        this.properties = properties;
        this.liveRoomService = liveRoomService;
        this.dynamicService = dynamicService;
        this.reportDemandService = reportDemandService;
    }

    /**
     * B 站关注与连接直播间
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceAddEvent(StarBotDataSourceAddEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (properties.getDynamic().isAutoFollow() && loadCompleted && user.hasEnabledDynamicEvent()) {
            dynamicService.followUp(new Up(user));
        }

        if (!properties.getLive().isEnableConnectLiveRoom()) {
            return;
        }

        if (properties.getLive().isOnlyConnectNecessaryRooms() && !user.hasEnabledLiveEvent()) {
            log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 未监听直播事件, 跳过连接直播间", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());
            return;
        }

        liveRoomService.addUp(new Up(user));
    }

    /**
     * B 站断开连接直播间
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceRemoveEvent(StarBotDataSourceRemoveEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        reportDemandService.deactivate(user.getUid());
        dynamicService.deactivate(user.getUid());

        if (!properties.getLive().isEnableConnectLiveRoom()) {
            return;
        }

        if (properties.getLive().isOnlyConnectNecessaryRooms() && !user.hasEnabledLiveEvent()) {
            log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 未监听直播事件, 无需断开直播间连接", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());
            return;
        }

        liveRoomService.removeUp(new Up(user));
    }

    /**
     * B 站关注与连接或断开直播间
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceUpdateEvent(StarBotDataSourceUpdateEvent event) {
        PushUser oldUser = event.getOldUser();
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (properties.getDynamic().isAutoFollow() && loadCompleted && user.hasEnabledDynamicEvent()) {
            dynamicService.followUp(new Up(user));
        }

        if (!properties.getLive().isEnableConnectLiveRoom()) {
            return;
        }

        if (properties.getLive().isOnlyConnectNecessaryRooms()) {
            if (!oldUser.hasEnabledLiveEvent() && user.hasEnabledLiveEvent()) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 监听了直播事件, 准备连接到直播间", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());
                liveRoomService.addUp(new Up(user));
            } else if (oldUser.hasEnabledLiveEvent() && !user.hasEnabledLiveEvent()) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 未监听直播事件, 准备断开直播间连接", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());
                liveRoomService.removeUp(new Up(user));
            }
        } else {
            if (oldUser.getRoomId() == null && user.getRoomId() != null) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 已开通直播间, 准备连接到直播间", user.getUid(), user.getUname(), user.getRoomIdString(), user.getPlatform());
                liveRoomService.addUp(new Up(user));
            }
        }
    }

    /**
     * 标记数据源加载完毕
     */
    @Order(-10000)
    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public void onStarBotDataSourceLoadCompleteEvent() {
        loadCompleted = true;
    }
}
