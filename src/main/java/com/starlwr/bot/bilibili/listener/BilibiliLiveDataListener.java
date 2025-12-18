package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bilibili 直播数据监听器
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveDataListener {
    private final StarBotBilibiliProperties properties;

    private final BilibiliApiUtil bilibili;

    private final LiveDataService liveDataService;

    private boolean loadCompleted = false;

    @Autowired
    public BilibiliLiveDataListener(StarBotBilibiliProperties properties, BilibiliApiUtil bilibili, LiveDataService liveDataService) {
        this.properties = properties;
        this.bilibili = bilibili;
        this.liveDataService = liveDataService;
    }

    /**
     * 获取更新房间数据
     * @param event 事件
     */
    @Order(-20000)
    @EventListener
    public void onStarBotDataSourceAddEvent(StarBotDataSourceAddEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (loadCompleted && user.getRoomId() != null && (!properties.getLive().isOnlyConnectNecessaryRooms() || user.hasEnabledLiveEvent())) {
            Room room = bilibili.getLiveInfoByRoomId(user.getRoomId());
            updateRoomInfo(room);
        }
    }

    /**
     * 获取更新房间数据
     * @param event 事件
     */
    @Order(-20000)
    @EventListener
    public void onStarBotDataSourceUpdateEvent(StarBotDataSourceUpdateEvent event) {
        PushUser oldUser = event.getOldUser();
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        boolean shouldUpdate = properties.getLive().isOnlyConnectNecessaryRooms()
                ? user.getRoomId() != null && ((!oldUser.hasEnabledLiveEvent() && user.hasEnabledLiveEvent()) || oldUser.getRoomId() == null)
                : oldUser.getRoomId() == null && user.getRoomId() != null;

        if (shouldUpdate) {
            Room room = bilibili.getLiveInfoByRoomId(user.getRoomId());
            updateRoomInfo(room);
        }
    }

    /**
     * 获取更新房间数据
     * @param event 事件
     */
    @Order(-20000)
    @EventListener
    public void onStarBotDataSourceLoadCompleteEvent(StarBotDataSourceLoadCompleteEvent event) {
        loadCompleted = true;

        Set<Long> uids = event.getUsers().stream()
                .filter(user -> LivePlatform.BILIBILI.getName().equals(user.getPlatform()))
                .filter(user -> !properties.getLive().isOnlyConnectNecessaryRooms() || user.hasEnabledLiveEvent())
                .map(PushUser::getUid)
                .collect(Collectors.toSet());

        Map<Long, Room> liveInfos = bilibili.getLiveInfoByUids(uids);

        for (Room room : liveInfos.values()) {
            updateRoomInfo(room);
        }
    }

    /**
     * 更新直播间信息
     * @param room 直播间信息
     */
    private void updateRoomInfo(Room room) {
        Long uid = room.getUid();
        boolean status = room.getLiveStatus() == 1;

        Optional<Boolean> optionalLastStatus = liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), uid);
        if (optionalLastStatus.isEmpty()) {
            // 无状态记录，新增的主播，直接写入状态，若正在直播，写入开播时间
            liveDataService.setLiveStatus(LivePlatform.BILIBILI.getName(), uid, status);
            if (status) {
                liveDataService.setLiveStartTime(LivePlatform.BILIBILI.getName(), uid, room.getLiveStartTime());
            }
        } else {
            boolean lastStatus = optionalLastStatus.get();
            if (lastStatus) {
                if (status) {
                    Long lastStartTime = liveDataService.getLiveStartTime(LivePlatform.BILIBILI.getName(), uid).orElseThrow();
                    if (!lastStartTime.equals(room.getLiveStartTime())) {
                        // 记录的状态与当前状态均为正在直播，但记录的开播时间与当前开播时间不一致，为程序退出期间下播再次开播，重置直播数据，写入开播时间，删除下播时间
                        liveDataService.resetLiveData(LivePlatform.BILIBILI.getName(), uid);
                        liveDataService.setLiveStartTime(LivePlatform.BILIBILI.getName(), uid, room.getLiveStartTime());
                        liveDataService.deleteLiveEndTime(LivePlatform.BILIBILI.getName(), uid);
                    }
                } else {
                    // 记录的状态为正在直播，当前状态为未开播，为程序退出期间下播，更新状态
                    liveDataService.setLiveStatus(LivePlatform.BILIBILI.getName(), uid, false);
                }
            } else {
                if (status) {
                    // 记录的状态为未开播，存在记录的开播时间，当前状态为正在直播，为程序退出期间开播，重置直播数据，更新状态，写入开播时间，删除下播时间（可能不存在）
                    liveDataService.resetLiveData(LivePlatform.BILIBILI.getName(), uid);
                    liveDataService.setLiveStatus(LivePlatform.BILIBILI.getName(), uid, true);
                    liveDataService.setLiveStartTime(LivePlatform.BILIBILI.getName(), uid, room.getLiveStartTime());
                    liveDataService.deleteLiveEndTime(LivePlatform.BILIBILI.getName(), uid);
                }
            }
        }
    }
}
