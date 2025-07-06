package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.DefaultHandlerForEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotPushMessageSender;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * <h3>Bilibili 开播推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "message": String (推送消息模版)
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
 *         "message": "{uname} 正在直播 {title}\n{url}{next}{cover}"
 *     }
 * </pre>
 */
@Slf4j
@StarBotComponent
@DefaultHandlerForEvent(event = "com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent")
public class BilibiliLiveOnPushHandler implements StarBotEventHandler {
    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private StarBotPushMessageSender sender;

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOnEvent event = (BilibiliLiveOnEvent) baseEvent;

        String title = "";
        String cover = "";
        try {
            Room room = bilibili.getLiveInfoByRoomId(event.getSource().getRoomId());
            title = room.getTitle();
            cover = "{image_url=" + room.getCover() + "}";
        } catch (Exception e) {
            log.error("获取 Bilibili 直播间信息失败, UID: {}, 昵称: {}, 房间号: {}", event.getSource().getUid(), event.getSource().getUname(), event.getSource().getRoomId(), e);
        }

        JSONObject params = pushMessage.getParamsJsonObject();

        String raw = params.getString("message");
        String content = raw.replace("{uname}", event.getSource().getUname())
                .replace("{title}", title)
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{cover}", cover);

        PushTarget target = pushMessage.getTarget();
        List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);

        for (Message message : messages) {
            sender.send(message);
        }
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("message", "{uname} 正在直播 {title}\n{url}{next}{cover}");

        return params;
    }
}
