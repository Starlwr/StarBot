package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.handler.DefaultHandlerForEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * <h3>Bilibili 动态推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "at_all": Boolean (是否 @ 全体成员)
 *         "message": String (推送消息模版)
 *         “white_list”: List&lt;String&gt; (类型白名单) [与 black_list 二选一配置，二者均配置以白名单优先]
 *         "black_list": List&lt;String&gt; (类型黑名单) [与 white_list 二选一配置，二者均配置以白名单优先]
 *         "only_self_origin": Boolean (当动态类型为转发动态时，是否过滤掉源动态作者不为自己的动态)
 *     }
 * </pre>
 * <h4>推送消息模版支持的参数：</h4>
 * <ul>
 *     <li>{uname}: 昵称</li>
 *     <li>{action}: 动态操作类型（发表了新动态，转发了动态，投稿了新视频...）</li>
 *     <li>{url}: 动态链接</li>
 *     <li>{picture}: 动态图片</li>
 * </ul>
 * <h4>默认参数:</h4>
 * <pre>
 *     {
 *         "at_all": false,
 *         "message": "{uname} {action}\n{url}{next}{picture}"
 *         "white_list": [],
 *         "black_list": [],
 *         "only_self_origin": false
 *     }
 * </pre>
 */
@Slf4j
@StarBotComponent
@DefaultHandlerForEvent(event = "com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent")
public class BilibiliDynamicPushHandler implements StarBotEventHandler {
    private final StarBotBilibiliProperties properties;

    private final StarBotMessageSender sender;

    private final BilibiliDynamicService service;

    @Autowired
    public BilibiliDynamicPushHandler(StarBotBilibiliProperties properties, StarBotMessageSender sender, BilibiliDynamicService service) {
        this.properties = properties;
        this.sender = sender;
        this.service = service;
    }

    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliDynamicUpdateEvent event = (BilibiliDynamicUpdateEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();

        int pushMinutes = properties.getDynamic().getPushMinutes();
        if (pushMinutes > 0) {
            Instant timestamp = Instant.ofEpochSecond(event.getDynamic().getModules().getJSONObject("module_author").getInteger("pub_ts"));
            Instant now = Instant.now();
            if (timestamp.isBefore(now.minus(pushMinutes, ChronoUnit.MINUTES))) {
                log.info("[{}] {} 的动态发表时间在 {} 分钟前, 跳过推送", event.getPlatform(), event.getSource().getUname(), pushMinutes);
                return;
            }
        }

        String type = event.getDynamic().getType();
        JSONArray whiteList = params.getJSONArray("white_list");
        JSONArray blackList = params.getJSONArray("black_list");
        if (!CollectionUtils.isEmpty(whiteList)) {
            if (!whiteList.contains(type)) {
                log.info("[{}] {} 的动态类型 {} 不在白名单中, 跳过推送", event.getPlatform(), event.getSource().getUname(), type);
                return;
            }
        } else if (!CollectionUtils.isEmpty(blackList)) {
            if (blackList.contains(type)) {
                log.info("[{}] {} 的动态类型 {} 在黑名单中, 跳过推送", event.getPlatform(), event.getSource().getUname(), type);
                return;
            }
        }

        boolean onlySelfOrigin = params.getBooleanValue("only_self_origin", false);
        if ("DYNAMIC_TYPE_FORWARD".equals(type) && onlySelfOrigin) {
            Long originUid = event.getDynamic().getOrigin().getModules().getJSONObject("module_author").getLong("mid");
            if (!event.getSource().getUid().equals(originUid)) {
                log.info("[{}] {} 的转发动态源作者不为自己, 跳过推送", event.getPlatform(), event.getSource().getUname());
                return;
            }
        }

        Optional<String> optionalBase64 = service.paint(event.getDynamic());

        if (optionalBase64.isPresent()) {
            String base64 = optionalBase64.get();

            String raw = params.getString("message");
            String atAll = params.getBooleanValue("at_all") && PushTargetType.GROUP == pushMessage.getTarget().getType() && !raw.contains("{at=all}") ? "{at=all}{next}" : "";
            String content = atAll + raw.replace("{uname}", event.getSource().getUname())
                    .replace("{action}", event.getAction())
                    .replace("{url}", event.getUrl())
                    .replace("{picture}", "{image_base64=" + base64 + "}");

            PushTarget target = pushMessage.getTarget();
            List<Message> messages = Message.create(target.getPlatform(), target.getType(), target.getNum(), content);

            for (Message message : messages) {
                sender.send(message);
            }
        }
    }

    /**
     * 获取事件处理器处理的事件类型
     *
     * @return 事件类型
     */
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliDynamicUpdateEvent.class;
    }

    /**
     * 获取事件处理器默认参数
     * @return 默认参数
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("at_all", false);
        params.put("message", "{uname} {action}\n{url}{next}{picture}");
        params.put("white_list", List.of());
        params.put("black_list", List.of());
        params.put("only_self_origin", false);

        return params;
    }
}
