package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.DefaultHandlerForEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.sender.StarBotPushMessageSender;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h3>Bilibili 下播推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "message": String (推送消息模版)
 *     }
 * </pre>
 * <h4>推送消息模版支持的参数：</h4>
 * <ul>
 *     <li>{uname}: 昵称</li>
 * </ul>
 * <h4>默认参数:</h4>
 * <pre>
 *     {
 *         "message": "{uname} 直播结束了"
 *     }
 * </pre>
 */
@Slf4j
@Component
@DefaultHandlerForEvent(event = "com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent")
public class BilibiliLiveOffPushHandler implements StarBotEventHandler {
    @Resource
    private StarBotPushMessageSender sender;

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOffEvent event = (BilibiliLiveOffEvent) baseEvent;

        JSONObject params = pushMessage.getParamsJsonObject();

        String raw = params.getString("message");
        String content = raw.replace("{uname}", event.getSource().getUname());

        PushTarget target = pushMessage.getTarget();
        List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);

        for (Message message : messages) {
            sender.send(message);
        }
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("message", "{uname} 直播结束了");

        return params;
    }
}
