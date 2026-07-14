package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.handler.DefaultHandlerForEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

/**
 * <h3>Bilibili 下播推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "at_all": Boolean (是否 @ 全体成员)
 *         "message": String (推送消息模版)
 *     }
 * </pre>
 * <h4>推送消息模版支持的参数：</h4>
 * <ul>
 *     <li>{uname}: 昵称</li>
 *     <li>{hours}: 直播小时数</li>
 *     <li>{minutes}: 直播分钟数</li>
 *     <li>{seconds}: 直播秒数</li>
 *     <li>{time}: 直播时长，格式为：11 时 45 分 14 秒，自动省略值为 0 的部分</li>
 * </ul>
 * <h4>默认参数:</h4>
 * <pre>
 *     {
 *         "at_all": false,
 *         "message": "{uname} 直播结束了"
 *     }
 * </pre>
 */
@Slf4j
@StarBotComponent
@DefaultHandlerForEvent(event = "com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent")
public class BilibiliLiveOffPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil bilibili;

    private final StarBotMessageSender sender;

    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveOffPushHandler(BilibiliApiUtil bilibili, StarBotMessageSender sender, LiveDataService liveDataService) {
        this.bilibili = bilibili;
        this.sender = sender;
        this.liveDataService = liveDataService;
    }

    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOffEvent event = (BilibiliLiveOffEvent) baseEvent;

        JSONObject params = pushMessage.getParamsJsonObject();

        String uname = event.getSource().getUname();
        try {
            Up up = bilibili.getUpInfoByUid(event.getSource().getUid());
            uname = up.getUname();
        } catch (Exception e) {
            log.error("获取 Bilibili 用户昵称失败, UID: {}, 昵称: {}, 房间号: {}", event.getSource().getUid(), event.getSource().getUname(), event.getSource().getRoomIdString(), e);
        }

        long hours = 0;
        long minutes = 0;
        long seconds = 0;
        String time = "";
        Optional<Long> optionalLiveStartTime = liveDataService.getLiveStartTime(event.getPlatform(), event.getSource().getUid());
        Optional<Long> optionalLiveEndTime = liveDataService.getLiveEndTime(event.getPlatform(), event.getSource().getUid());
        if (optionalLiveStartTime.isPresent() && optionalLiveEndTime.isPresent()) {
            long duration = (optionalLiveEndTime.get() - optionalLiveStartTime.get()) / 1000;
            hours = duration / 3600;
            minutes = (duration % 3600) / 60;
            seconds = duration % 60;
            if (hours > 0) {
                time += hours + " 时 ";
            }
            if (minutes > 0) {
                time += minutes + " 分 ";
            }
            if (seconds > 0) {
                time += seconds + " 秒";
            }
            time = time.trim();
        }

        String raw = params.getString("message");
        String atAll = params.getBooleanValue("at_all") && PushTargetType.GROUP == pushMessage.getTarget().getType() && !raw.contains("{at=all}") ? "{at=all}{next}" : "";
        String content = atAll + raw.replace("{uname}", uname)
                .replace("{hours}", String.valueOf(hours))
                .replace("{minutes}", String.valueOf(minutes))
                .replace("{seconds}", String.valueOf(seconds))
                .replace("{time}", time);

        PushTarget target = pushMessage.getTarget();
        List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);

        for (Message message : messages) {
            sender.send(message);
        }
    }

    /**
     * 获取事件处理器处理的事件类型
     *
     * @return 事件类型
     */
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOffEvent.class;
    }

    /**
     * 获取事件处理器默认参数
     * @return 默认参数
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("at_all", false);
        params.put("message", "{uname} 直播结束了");

        return params;
    }
}
