package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.factory.BilibiliDynamicPainterFactory;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.CollectionUtil;
import com.starlwr.bot.core.util.FixedSizeSetQueue;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import com.starlwr.bot.bilibili.log.BilibiliDebugFileLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Bilibili 动态服务
 */
@Slf4j
@StarBotComponent
public class BilibiliDynamicService {
    private final ApplicationEventPublisher eventPublisher;

    private final StarBotBilibiliProperties properties;

    private final AbstractDataSource dataSource;

    private final BilibiliApiUtil bilibili;

    private final BilibiliAccountService accountService;

    private final BilibiliDynamicPainterFactory factory;

    private final BilibiliDebugFileLogger debugFileLog;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BlockingQueue<Up> autoFollowQueue = new LinkedBlockingQueue<>();

    private final Set<Up> alreadyFollowUps = new HashSet<>();

    private final FixedSizeSetQueue<String> dynamicIds = new FixedSizeSetQueue<>(1000);

    private final AtomicBoolean closing = new AtomicBoolean();

    private final CountDownLatch stopSignal = new CountDownLatch(1);

    @PreDestroy
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        stopSignal.countDown();
        scheduler.shutdown();
        executor.shutdown();
        awaitTermination(scheduler, "动态轮询", 5);
        awaitTermination(executor, "自动关注", 5);
    }

    @Autowired
    public BilibiliDynamicService(ApplicationEventPublisher eventPublisher, StarBotBilibiliProperties properties, AbstractDataSource dataSource, BilibiliApiUtil bilibili, BilibiliAccountService accountService, BilibiliDynamicPainterFactory factory, BilibiliDebugFileLogger debugFileLog) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.dataSource = dataSource;
        this.bilibili = bilibili;
        this.accountService = accountService;
        this.factory = factory;
        this.debugFileLog = debugFileLog;
    }

    /**
     * 启动 B 站自动关注与动态推送线程
     */
    @Order(-30000)
    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public void onStarBotDataSourceLoadCompleteEvent() {
        startDynamicPush();
        autoFollowUps();
    }

    /**
     * 关注 UP 主
     * @param up UP 主
     */
    public void followUp(Up up) {
        if (closing.get()) {
            return;
        }
        if (alreadyFollowUps.contains(up) || autoFollowQueue.contains(up)) {
            return;
        }

        autoFollowQueue.add(up);
    }

    /**
     * 启动动态推送服务
     */
    private void startDynamicPush() {
        int interval = properties.getDynamic().getApiRequestInterval();
        if (interval < 10) {
            log.warn("动态推送抓取频率设置过小, 可能会造成动态抓取 API 访问被暂时封禁, 推荐将其设置为 10 以上的数值");
        }

        try {
            dynamicIds.addAll(bilibili.getDynamicUpdateList().stream().map(Dynamic::getId).collect(Collectors.toSet()));
        } catch (Exception e) {
            int pushMinutes = properties.getDynamic().getPushMinutes();
            if (pushMinutes == 0) {
                log.error("初始化动态列表异常, 可能造成近期动态被重复推送, 请注意", e);
            } else {
                log.error("初始化动态列表异常, 可能造成 {} 分钟内的动态被重复推送, 请注意", pushMinutes, e);
            }
        }

        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("dynamic-watcher");

            if (closing.get()) {
                return;
            }

            try {
                List<Dynamic> dynamics = bilibili.getDynamicUpdateList();
                for (Dynamic dynamic: dynamics) {
                    if (dynamicIds.contains(dynamic.getId())) {
                        continue;
                    }

                    dynamicIds.add(dynamic.getId());

                    if (properties.getDebug().isDynamicRawMessageLog()) {
                        debugFileLog.dynamic(dynamic.getType(), dynamic.getId(), JSON.toJSONString(dynamic));
                    }

                    if ("DYNAMIC_TYPE_LIVE_RCMD".equals(dynamic.getType())) {
                        continue;
                    }

                    Long uid = dynamic.getModules().getJSONObject("module_author").getLong("mid");
                    Optional<PushUser> optionalUser = dataSource.getUser(LivePlatform.BILIBILI.getName(), uid);
                    if (optionalUser.isPresent()) {
                        PushUser user = optionalUser.get();

                        if (!user.hasEnabledDynamicEvent()) {
                            continue;
                        }

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
                if (closing.get()) {
                    log.debug("动态推送抓取任务已随应用关闭");
                } else {
                    log.error("动态推送抓取任务异常", e);
                }
            }
        }, interval, interval, TimeUnit.SECONDS);

        log.info("bilibili 动态推送服务已启动");
    }

    /**
     * 自动关注开启了动态推送的 UP 主
     */
    private void autoFollowUps() {
        if (!properties.getDynamic().isAutoFollow()) {
            log.warn("未启用自动关注开启了动态推送的 UP 主, 未关注的 UP 主的动态将无法被推送, 请手动关注所有需要动态推送的 UP 主");
            return;
        }

        executor.submit(() -> {
            Thread.currentThread().setName("auto-follow-queue");

            if (properties.getDynamic().getAutoFollowInterval() < 30) {
                log.warn("检测到自动关注 UP 主的间隔时间设置过小, 可能会造成 API 访问被暂时封禁, 推荐将其设置为 30 以上的数值");
            }

            while (!closing.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Up up = autoFollowQueue.poll(1, TimeUnit.SECONDS);
                    if (up == null) {
                        continue;
                    }
                    log.info("尝试关注 UP 主: {} ({})", up.getUname(), up.getUid());
                    bilibili.followUp(up.getUid());
                    alreadyFollowUps.add(up);
                    log.info("关注 {} ({}) 成功", up.getUname(), up.getUid());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (closing.get()) {
                        log.debug("自动关注线程已随应用关闭");
                    } else {
                        log.warn("自动关注线程被意外中断", e);
                    }
                    return;
                } catch (Exception e) {
                    log.error("自动关注异常", e);
                }

                try {
                    if (!autoFollowQueue.isEmpty()) {
                        log.info("即将在 {} 秒后关注下一个 UP 主, 自动关注队列中还剩余 {} 个 UP 主", properties.getDynamic().getAutoFollowInterval(), autoFollowQueue.size());
                    }
                    if (stopSignal.await(properties.getDynamic().getAutoFollowInterval(), TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (!closing.get()) {
                        log.warn("自动关注等待被意外中断", e);
                    }
                    return;
                }
            }
        });

        List<Up> needFollowUps = dataSource.getUsers(LivePlatform.BILIBILI.getName()).stream()
                .filter(PushUser::hasEnabledDynamicEvent)
                .map(Up::new)
                .toList();

        if (needFollowUps.isEmpty()) {
            log.info("不存在打开了动态推送但未关注的 UP 主");
            return;
        }

        alreadyFollowUps.addAll(bilibili.getFollowingUps(accountService.getAccountInfo().getUid()));
        alreadyFollowUps.add(accountService.getAccountInfo());

        List<Up> notFollowUps = new ArrayList<>();
        CollectionUtil.compareCollectionDiff(alreadyFollowUps, needFollowUps, notFollowUps, new ArrayList<>(), new ArrayList<>());

        if (notFollowUps.isEmpty()) {
            log.info("不存在打开了动态推送但未关注的 UP 主");
            return;
        }

        log.info("检测到 {} 个打开了动态推送但未关注的 UP 主: [{}], 开始自动关注", notFollowUps.size(), notFollowUps.stream().map(up -> up.getUname() + "(" + up.getUid() + ")").collect(Collectors.joining(", ")));

        autoFollowQueue.addAll(notFollowUps);
    }

    private void awaitTermination(ExecutorService service, String name, int seconds) {
        try {
            if (!service.awaitTermination(seconds, TimeUnit.SECONDS)) {
                List<Runnable> cancelled = service.shutdownNow();
                log.warn("{}服务未在 {} 秒内完成关闭, 已取消 {} 个待执行任务", name, seconds, cancelled.size());
                service.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 绘制动态图片
     * @param dynamic 动态
     * @return 动态图片的 Base64 字符串
     */
    @Cacheable(value = "bilibiliDynamicImageCache", keyGenerator = "cacheKeyGenerator")
    public Optional<String> paint(Dynamic dynamic) {
        BilibiliDynamicPainter painter = factory.create(dynamic);

        if (properties.getDynamic().isAutoSaveImage()) {
            Path path = Paths.get("DynamicImage", dynamic.getId() + ".png");
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                log.error("创建动态图片保存目录失败: {}", path.getParent(), e);
            }
            String savePath = path.toString();
            return painter.paint(savePath);
        } else {
            return painter.paint();
        }
    }
}
