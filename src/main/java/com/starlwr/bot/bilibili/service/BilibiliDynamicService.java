package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Bilibili 动态服务
 */
@Slf4j
@StarBotComponent
public class BilibiliDynamicService {
    private static final Logger dynamicLogger = LoggerFactory.getLogger("DynamicLogger");

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private AbstractDataSource dataSource;

    @Resource
    private BilibiliApiUtil bilibili;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Set<String> dynamicIds = new HashSet<>();

    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public void handleLoadCompleteEvent() {
        int interval = properties.getDynamic().getApiRequestInterval();
        if (interval < 10) {
            log.warn("检测到动态推送抓取频率设置过小, 可能会造成动态抓取 API 访问被暂时封禁, 推荐将其设置为 10 以上的数值");
        }

        dynamicIds.addAll(bilibili.getDynamicUpdateList().stream().map(Dynamic::getId).collect(Collectors.toSet()));

        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("dynamic-watcher");

            try {
                List<Dynamic> dynamics = bilibili.getDynamicUpdateList();
                for (Dynamic dynamic: dynamics) {
                    if (dynamicIds.contains(dynamic.getId())) {
                        continue;
                    }

                    dynamicIds.add(dynamic.getId());

                    if (properties.getDebug().isDynamicRawMessageLog()) {
                        dynamicLogger.debug("{}: {}", dynamic.getType(), JSON.toJSONString(dynamic));
                    }

                    if ("DYNAMIC_TYPE_LIVE_RCMD".equals(dynamic.getType())) {
                        continue;
                    }

                    Long uid = dynamic.getModules().getJSONObject("module_author").getLong("mid");
                    Optional<PushUser> optionalUser = dataSource.getUser(LivePlatform.BILIBILI.getName(), uid);
                    if (optionalUser.isPresent()) {
                        PushUser user = optionalUser.get();

                        String action;
                        switch (dynamic.getType()) {
                            case "DYNAMIC_TYPE_ARTICLE" -> action = "投稿了新文章";
                            case "DYNAMIC_TYPE_AV" -> action = "投稿了新视频";
                            case "DYNAMIC_TYPE_FORWARD" -> action = "转发了动态";
                            default -> action = "发表了新动态";
                        }
                        String url;
                        if (dynamic.getType().equals("DYNAMIC_TYPE_AV")) {
                            String bvId = dynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("archive").getString("bvid");
                            url = "https://www.bilibili.com/video/" + bvId;
                        } else {
                            url = "https://t.bilibili.com/" + dynamic.getId();
                        }

                        log.info("[{}] [动态更新] {}: {}", user.getPlatform(), user.getUname(), url);

                        LiveStreamerInfo info = new LiveStreamerInfo(user.getUid(), user.getUname(), user.getRoomId(), user.getFace());
                        eventPublisher.publishEvent(new BilibiliDynamicUpdateEvent(info, dynamic, action, url, Instant.now()));
                    }
                }
            } catch (Exception e) {
                log.error("动态推送抓取任务异常", e);
            }
        }, interval, interval, TimeUnit.SECONDS);

        log.info("动态推送服务已启动");
    }
}
