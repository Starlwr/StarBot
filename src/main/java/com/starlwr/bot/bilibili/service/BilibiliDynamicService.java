package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.CollectionUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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

    @Resource
    private BilibiliAccountService accountService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BlockingQueue<Up> autoFollowQueue = new LinkedBlockingQueue<>();

    private final Set<Up> alreadyFollowUps = new HashSet<>();

    private final Set<String> dynamicIds = new HashSet<>();

    /**
     * 关注 UP 主
     * @param up UP 主
     */
    public void followUp(Up up) {
        if (alreadyFollowUps.contains(up) || autoFollowQueue.contains(up)) {
            return;
        }

        autoFollowQueue.add(up);
    }

    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public void handleLoadCompleteEvent() {
        startDynamicPush();
        autoFollowUps();
    }

    /**
     * 启动动态推送服务
     */
    private void startDynamicPush() {
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

    /**
     * 自动关注开启了动态推送的 UP 主
     */
    private void autoFollowUps() {
        if (!properties.getDynamic().isAutoFollow()) {
            log.warn("未启用自动关注开启了动态推送的 UP 主, 未关注的 UP 主的动态将无法被推送, 请手动关注所有需要动态推送的 UP 主");
            return;
        }

        List<Up> needFollowUps = dataSource.getUsers(LivePlatform.BILIBILI.getName()).stream()
                .filter(user -> user.getTargets().stream()
                        .map(PushTarget::getMessages)
                        .flatMap(List::stream)
                        .anyMatch(message -> "com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent".equals(message.getEvent()))
                ).map(Up::new)
                .toList();

        if (needFollowUps.isEmpty()) {
            log.info("不存在打开了动态推送但未关注的 UP 主");
            return;
        }

        alreadyFollowUps.addAll(bilibili.getFollowingUps());
        alreadyFollowUps.add(accountService.getAccountInfo());

        List<Up> notFollowUps = new ArrayList<>();
        CollectionUtil.compareCollectionDiff(alreadyFollowUps, needFollowUps, notFollowUps, new ArrayList<>(), new ArrayList<>());

        if (notFollowUps.isEmpty()) {
            log.info("不存在打开了动态推送但未关注的 UP 主");
            return;
        }

        log.info("检测到 {} 个打开了动态推送但未关注的 UP 主: [{}], 开始自动关注", notFollowUps.size(), notFollowUps.stream().map(up -> up.getUname() + "(" + up.getUid() + ")").collect(Collectors.joining(", ")));

        autoFollowQueue.addAll(notFollowUps);

        executor.submit(() -> {
            Thread.currentThread().setName("auto-follow-queue");

            if (properties.getDynamic().getAutoFollowInterval() < 30) {
                log.warn("检测到自动关注 UP 主的间隔时间设置过小, 可能会造成 API 访问被暂时封禁, 推荐将其设置为 30 以上的数值");
            }

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Up up = autoFollowQueue.take();
                    log.info("尝试关注 UP 主: {} ({})", up.getUname(), up.getUid());
                    bilibili.followUp(up.getUid());
                    alreadyFollowUps.add(up);
                    log.info("关注 {} ({}) 成功: ", up.getUname(), up.getUid());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("自动关注中断", e);
                    return;
                } catch (Exception e) {
                    log.error("自动关注异常", e);
                }

                try {
                    if (!autoFollowQueue.isEmpty()) {
                        log.info("即将在 {} 秒后关注下一个 UP 主, 自动关注队列中还剩余 {} 个 UP 主", properties.getDynamic().getAutoFollowInterval(), autoFollowQueue.size());
                    }
                    Thread.sleep(properties.getDynamic().getAutoFollowInterval() * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}
