package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.factory.BilibiliDynamicPainterFactory;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
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
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * <h3>Bilibili 动态推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "message": String (推送消息模版)
 *         “white_list”: List&lt;String&gt; (类型白名单) [与 black_list 二选一配置，二者均配置以白名单优先]
 *         "black_list": List&lt;String&gt; (类型黑名单) [与 white_list 二选一配置，二者均配置以白名单优先]
 *         "only_self_origin": Boolean (是否仅推送源动态作者为自己的转发动态)
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
    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private BilibiliDynamicPainterFactory factory;

    @Resource
    private StarBotPushMessageSender sender;

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliDynamicUpdateEvent event = (BilibiliDynamicUpdateEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();

        String type = event.getDynamic().getType();
        JSONArray whiteList = params.getJSONArray("white_list");
        JSONArray blackList = params.getJSONArray("black_list");
        if (!CollectionUtils.isEmpty(whiteList)) {
            if (!whiteList.contains(type)) {
                log.info("[{}] {} 的动态类型 {} 不在白名单中，跳过推送", event.getPlatform(), event.getSource().getUname(), type);
                return;
            }
        } else if (!CollectionUtils.isEmpty(blackList)) {
            if (blackList.contains(type)) {
                log.info("[{}] {} 的动态类型 {} 在黑名单中，跳过推送", event.getPlatform(), event.getSource().getUname(), type);
                return;
            }
        }

        boolean onlySelfOrigin = params.getBooleanValue("only_self_origin", false);
        if ("DYNAMIC_TYPE_FORWARD".equals(type) && onlySelfOrigin) {
            Long originUid = event.getDynamic().getOrigin().getModules().getJSONObject("module_author").getLong("mid");
            if (!event.getSource().getUid().equals(originUid)) {
                log.info("[{}] {} 的转发动态源作者不为自己，跳过推送", event.getPlatform(), event.getSource().getUname());
                return;
            }
        }

        BilibiliDynamicPainter painter = factory.create(event.getDynamic());

        Optional<String> optionalBase64;
        if (properties.getDynamic().isAutoSaveImage()) {
            Path path = Paths.get("DynamicImage", event.getDynamic().getId() + ".png");
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                log.error("创建动态图片保存目录失败: {}", path.getParent(), e);
            }
            String savePath = path.toString();
            optionalBase64 = painter.paint(savePath);
        } else {
            optionalBase64 = painter.paint();
        }

        if (optionalBase64.isPresent()) {
            String base64 = optionalBase64.get();

            String raw = params.getString("message");
            String content = raw.replace("{uname}", event.getSource().getUname())
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

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("message", "{uname} {action}\n{url}{next}{picture}");
        params.put("white_list", List.of());
        params.put("black_list", List.of());
        params.put("only_self_origin", false);

        return params;
    }
}
