package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * <h3>Bilibili 开播推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "at_all": Boolean (是否 @ 全体成员)
 *         "message": String (推送消息模版)
 *         "reconnect_message": String (断线重连推送消息)
 *     }
 * </pre>
 * <h4>推送消息模版支持的参数：</h4>
 * <ul>
 *     <li>{uname}: 昵称</li>
 *     <li>{title}: 直播间标题</li>
 *     <li>{url}: 直播间链接</li>
 *     <li>{cover}: 直播间封面图片</li>
 * </ul>
 * <h4>默认参数:</h4>
 * <pre>
 *     {
 *         "at_all": false,
 *         "message": "{uname} 正在直播 {title}\n{url}{next}{cover}",
 *         "reconnect_message": "检测到下播后短时间内重新开播,本次开播不再重复通知"
 *     }
 * </pre>
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveOnPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil bilibili;

    private final StarBotMessageSender sender;

    @Autowired
    public BilibiliLiveOnPushHandler(BilibiliApiUtil bilibili, StarBotMessageSender sender) {
        this.bilibili = bilibili;
        this.sender = sender;
    }

    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOnEvent event = (BilibiliLiveOnEvent) baseEvent;

        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        if (event.isReconnect()) {
            String content = params.getString("reconnect_message");
            List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);

            for (Message message : messages) {
                sender.send(message);
            }

            return;
        }

        String uname = event.getSource().getUname();
        try {
            Up up = bilibili.getUpInfoByUid(event.getSource().getUid());
            uname = up.getUname();
        } catch (Exception e) {
            log.error("获取 Bilibili 用户昵称失败, UID: {}, 昵称: {}, 房间号: {}", event.getSource().getUid(), event.getSource().getUname(), event.getSource().getRoomIdString(), e);
        }

        String title = "";
        String cover = "";
        try {
            Room room = bilibili.getLiveInfoByRoomId(event.getSource().getRoomId());
            title = room.getTitle();
            if (StringUtil.isNotBlank(room.getCover())) {
                cover = "{image_url=" + room.getCover() + "}";
            }
        } catch (Exception e) {
            log.error("获取 Bilibili 直播间封面信息失败, UID: {}, 昵称: {}, 房间号: {}", event.getSource().getUid(), event.getSource().getUname(), event.getSource().getRoomIdString(), e);
        }

        String raw = params.getString("message");
        String atAll = params.getBooleanValue("at_all") && PushTargetType.GROUP == target.getType() && !raw.contains("{at=all}") ? "{at=all}{next}" : "";
        String content = atAll + raw.replace("{uname}", uname)
                .replace("{title}", title)
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{cover}", cover);

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
    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOnEvent.class;
    }

    /**
     * 获取事件处理器默认参数
     * @return 默认参数
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("at_all", false);
        params.put("message", "{uname} 正在直播 {title}\n{url}{next}{cover}");
        params.put("reconnect_message", "检测到下播后短时间内重新开播,本次开播不再重复通知");

        return params;
    }
}
