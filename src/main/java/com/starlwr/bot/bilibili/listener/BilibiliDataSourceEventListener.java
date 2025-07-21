package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bilibili 数据源事件监听器
 */
@Slf4j
@StarBotComponent
public class BilibiliDataSourceEventListener {
    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private BilibiliLiveRoomService liveRoomService;

    @Resource
    private BilibiliDynamicService dynamicService;

    private boolean loadCompleted = false;

    /**
     * 推送用户是否监听直播事件
     * @param user 推送用户
     * @return 是否监听直播事件
     */
    private boolean hasEnabledLiveEvent(PushUser user) {
        Set<String> events = user.getTargets().stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEvent)
                .collect(Collectors.toSet());

        for (String event: events) {
            try {
                Class<?> clazz = Class.forName(event);
                if (StarBotBaseLiveEvent.class.isAssignableFrom(clazz)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    /**
     * 检查推送用户是否监听动态更新事件
     * @param user 推送用户
     * @return 是否监听动态更新事件
     */
    private boolean hasEnabledDynamicEvent(PushUser user) {
        Set<String> events = user.getTargets().stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEvent)
                .collect(Collectors.toSet());

        return events.contains("com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent");
    }

    @EventListener
    public void handleAddEvent(StarBotDataSourceAddEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (properties.getDynamic().isAutoFollow() && loadCompleted && hasEnabledDynamicEvent(user)) {
            dynamicService.followUp(new Up(user));
        }

        if (properties.getLive().isOnlyConnectNecessaryRooms() && !hasEnabledLiveEvent(user)) {
            log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 未监听直播事件, 跳过连接直播间", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());
            return;
        }

        liveRoomService.addUp(new Up(user));
    }

    @EventListener
    public void handleRemoveEvent(StarBotDataSourceRemoveEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (properties.getLive().isOnlyConnectNecessaryRooms() && !hasEnabledLiveEvent(user)) {
            log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 未监听直播事件, 无需断开直播间连接", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());
            return;
        }

        liveRoomService.removeUp(new Up(user));
    }

    @EventListener
    public void handleUpdateEvent(StarBotDataSourceUpdateEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (properties.getDynamic().isAutoFollow() && loadCompleted && hasEnabledDynamicEvent(user)) {
            dynamicService.followUp(new Up(user));
        }

        if (properties.getLive().isOnlyConnectNecessaryRooms()) {
            if (hasEnabledLiveEvent(user) && !liveRoomService.hasUp(user.getUid())) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 监听了直播事件, 准备连接到直播间", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());
                liveRoomService.addUp(new Up(user));
            } else if (!hasEnabledLiveEvent(user) && liveRoomService.hasUp(user.getUid())) {
                log.info("推送用户 (UID: {}, 昵称: {}, 房间号: {}, 平台: {}) 未监听直播事件, 准备断开直播间连接", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());
                liveRoomService.removeUp(new Up(user));
            }
        }
    }

    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public void handleLoadCompleteEvent() {
        loadCompleted = true;
    }
}
