package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.factory.BilibiliLiveReportPainterFactory;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportConfig;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * <h3>Bilibili 下播推送处理器</h3>
 * <h4>参数格式:</h4>
 * <pre>
 *     {
 *         "at_all": Boolean (是否 @ 全体成员)
 *         "message": String (推送消息模版)
 *         "modules": JSONObject (直播报告各模块开关)
 *     }
 * </pre>
 * <h4>推送消息模版支持的参数：</h4>
 * <ul>
 *     <li>{uname}: 昵称</li>
 *     <li>{hours}: 直播小时数</li>
 *     <li>{minutes}: 直播分钟数</li>
 *     <li>{seconds}: 直播秒数</li>
 *     <li>{time}: 直播时长，格式为：11 时 45 分 14 秒，自动省略值为 0 的部分</li>
 *     <li>{picture}: 直播报告图片</li>
 * </ul>
 * <h4>默认参数:</h4>
 * <pre>
 *     {
 *         "at_all": false,
 *         "message": "{uname} 直播结束了{next}{picture}",
 *         "modules": {
 *             "sequence": ["changeInfo", "danmuAnalysis", "boxAnalysis", "giftAnalysis", "superChatAnalysis", "guardAnalysis"],
 *             "enableBasicInfo": true,
 *             "showLiveArea": true,
 *             "showLiveTitle": true,
 *             "showLiveTime": true,
 *             "enableChangeInfo": true,
 *             "showFansChange": true,
 *             "showFansMedalChange": true,
 *             "showGuardChange": true,
 *             "enableDanmuAnalysis": true,
 *             "showDanmuDetails": true,
 *             "danmuRankingLimit": 5,
 *             "showDanmuGrowthChart": true,
 *             "showDanmuInteractionChart": true,
 *             "showDanmuTypeDistributionChart": true,
 *             "showDanmuSenderDistributionChart": true,
 *             "showDanmuWordCloud": true,
 *             "enableBoxAnalysis": true,
 *             "showBoxDetails": true,
 *             "showBoxProfitDetails": true,
 *             "boxRankingLimit": 5,
 *             "boxProfitRankingLimit": 5,
 *             "showBoxGrowthChart": true,
 *             "showBoxInteractionChart": true,
 *             "showBoxProfitGrowthChart": true,
 *             "showBoxProfitInteractionChart": true,
 *             "showBoxProfitDistributionChart": true,
 *             "showBoxGiftDistributionChart": true,
 *             "enableGiftAnalysis": true,
 *             "showGiftDetails": true,
 *             "giftRankingLimit": 5,
 *             "showGiftGrowthChart": true,
 *             "showGiftInteractionChart": true,
 *             "showGiftTypeDistributionChart": true,
 *             "enableSuperChatAnalysis": true,
 *             "showSuperChatDetails": true,
 *             "superChatRankingLimit": 5,
 *             "showSuperChatGrowthChart": true,
 *             "showSuperChatInteractionChart": true,
 *             "enableGuardAnalysis": true,
 *             "showGuardDetails": true,
 *             "showGuardList": true
 *         }
 *     }
 * </pre>
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveOffPushHandler implements StarBotEventHandler {
    private final StarBotBilibiliProperties properties;

    private final BilibiliApiUtil bilibili;

    private final StarBotMessageSender sender;

    private final LiveDataService liveDataService;

    private final BilibiliLiveReportPainterFactory factory;

    private final Cache<String, String> liveReportCache =
            Caffeine.newBuilder()
                    .maximumSize(10)
                    .expireAfterWrite(1, TimeUnit.MINUTES)
                    .build();

    @Autowired
    public BilibiliLiveOffPushHandler(StarBotBilibiliProperties properties, BilibiliApiUtil bilibili, StarBotMessageSender sender, LiveDataService liveDataService, BilibiliLiveReportPainterFactory factory) {
        this.properties = properties;
        this.bilibili = bilibili;
        this.sender = sender;
        this.liveDataService = liveDataService;
        this.factory = factory;
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

        Up up;
        try {
            up = bilibili.getUpInfoByUid(event.getSource().getUid());
        } catch (Exception e) {
            log.error("获取 Bilibili 用户信息失败, UID: {}, 昵称: {}, 房间号: {}", event.getSource().getUid(), event.getSource().getUname(), event.getSource().getRoomId(), e);
            up = new Up(event.getSource().getUid(), event.getSource().getUname(), event.getSource().getRoomId());
        }
        String uname = up.getUname();

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

        if (raw.contains("{picture}")) {
            BilibiliLiveReportConfig config = params.getJSONObject("modules").toJavaObject(BilibiliLiveReportConfig.class);

            String cacheKey = up.getUid() + "|" + event.getTimestamp() + "|" + params.getJSONObject("modules").toJSONString();
            String base64 = liveReportCache.getIfPresent(cacheKey);

            if (base64 == null) {
                BilibiliLiveReportPainter reportPainter = factory.create(up, config);

                Optional<String> optionalBase64;
                if (properties.getLive().isAutoSaveLiveReportImage()) {
                    Path path = Paths.get("LiveReport", "live-report-" + up.getUname() + "-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + ".png");
                    try {
                        Files.createDirectories(path.getParent());
                    } catch (IOException e) {
                        log.error("创建直播报告图片保存目录失败: {}", path.getParent(), e);
                    }
                    optionalBase64 = reportPainter.paint(path.toString());
                } else {
                    optionalBase64 = reportPainter.paint();
                }

                if (optionalBase64.isPresent()) {
                    base64 = optionalBase64.get();
                    liveReportCache.put(cacheKey, base64);
                }
            }

            if (base64 != null) {
                content = content.replace("{picture}", "{image_base64=" + base64 + "}");
            } else {
                log.error("生成直播报告图片失败, UID: {}, 昵称: {}, 房间号: {}", up.getUid(), up.getUname(), up.getRoomId());
                content = content.replace("{picture}", "");
            }
        }

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
    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOffEvent.class;
    }

    /**
     * 获取事件处理器默认参数
     *
     * @return 默认参数
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();

        params.put("at_all", false);
        params.put("message", "{uname} 直播结束了{next}{picture}");

        JSONObject modules = new JSONObject();
        modules.put("sequence", List.of("changeInfo", "danmuAnalysis", "boxAnalysis", "giftAnalysis", "superChatAnalysis", "guardAnalysis"));
        modules.put("enableBasicInfo", true);
        modules.put("showLiveArea", true);
        modules.put("showLiveTitle", true);
        modules.put("showLiveTime", true);
        modules.put("enableChangeInfo", true);
        modules.put("showFansChange", true);
        modules.put("showFansMedalChange", true);
        modules.put("showGuardChange", true);
        modules.put("enableDanmuAnalysis", true);
        modules.put("showDanmuDetails", true);
        modules.put("danmuRankingLimit", 5);
        modules.put("showDanmuGrowthChart", true);
        modules.put("showDanmuInteractionChart", true);
        modules.put("showDanmuTypeDistributionChart", true);
        modules.put("showDanmuSenderDistributionChart", true);
        modules.put("showDanmuWordCloud", true);
        modules.put("enableBoxAnalysis", true);
        modules.put("showBoxDetails", true);
        modules.put("showBoxProfitDetails", true);
        modules.put("boxRankingLimit", 5);
        modules.put("boxProfitRankingLimit", 5);
        modules.put("showBoxGrowthChart", true);
        modules.put("showBoxInteractionChart", true);
        modules.put("showBoxProfitGrowthChart", true);
        modules.put("showBoxProfitInteractionChart", true);
        modules.put("showBoxProfitDistributionChart", true);
        modules.put("showBoxGiftDistributionChart", true);
        modules.put("enableGiftAnalysis", true);
        modules.put("showGiftDetails", true);
        modules.put("giftRankingLimit", 5);
        modules.put("showGiftGrowthChart", true);
        modules.put("showGiftInteractionChart", true);
        modules.put("showGiftTypeDistributionChart", true);
        modules.put("enableSuperChatAnalysis", true);
        modules.put("showSuperChatDetails", true);
        modules.put("superChatRankingLimit", 5);
        modules.put("showSuperChatGrowthChart", true);
        modules.put("showSuperChatInteractionChart", true);
        modules.put("enableGuardAnalysis", true);
        modules.put("showGuardDetails", true);
        modules.put("showGuardList", true);

        params.put("modules", modules);

        return params;
    }
}
