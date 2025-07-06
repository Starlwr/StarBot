package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Bilibili 数据源事件监听器
 */
@Slf4j
@StarBotComponent
public class BilibiliDataSourceEventListener {
    @Resource
    private BilibiliLiveRoomService liveRoomService;

    /**
     * 将推送用户转换为 Up 主
     * @param user 推送用户
     * @return Up 主
     */
    private Up convertToUp(PushUser user) {
        return new Up(user.getUid(), user.getUname(), user.getRoomId(), user.getFace());
    }

    @EventListener
    public void handleAddEvent(StarBotDataSourceAddEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        liveRoomService.addUp(convertToUp(user));
    }

    @EventListener
    public void handleRemoveEvent(StarBotDataSourceRemoveEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        liveRoomService.removeUp(convertToUp(user));
    }
}
