package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Bilibili 备用直播推送服务
 */
@Slf4j
@StarBotComponent
public class BilibiliBackupLivePushService {
    private final ApplicationEventPublisher eventPublisher;

    private final StarBotBilibiliProperties properties;

    private final BilibiliApiUtil bilibili;

    private final LiveDataService liveDataService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Set<Long> uids = new HashSet<>();

    @Autowired
    public BilibiliBackupLivePushService(ApplicationEventPublisher eventPublisher, StarBotBilibiliProperties properties, BilibiliApiUtil bilibili, LiveDataService liveDataService) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.bilibili = bilibili;
        this.liveDataService = liveDataService;
    }

    /**
     * 启动备用直播推送
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceLoadCompleteEvent(StarBotDataSourceLoadCompleteEvent event) {
        if (!properties.getLive().isBackupLivePush()) {
            return;
        }

        int interval = properties.getLive().getBackupLivePushInterval();
        if (interval < 10) {
            log.warn("备用直播推送检测频率设置过小, 可能会造成 API 访问被暂时封禁, 推荐将其设置为 10 以上的数值");
        }

        uids.addAll(event.getUsers().stream().filter(user -> LivePlatform.BILIBILI.getName().equals(user.getPlatform())).filter(this::hasLivePushEvent).map(PushUser::getUid).collect(Collectors.toSet()));

        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("backup-live-push");

            try {
                Map<Long, Room> liveInfos = bilibili.getLiveInfoByUids(uids);
                for (Map.Entry<Long, Room> entry : liveInfos.entrySet()) {
                    Long uid = entry.getKey();
                    Room liveInfo = entry.getValue();

                    synchronized (BilibiliBackupLivePushService.class) {
                        Optional<Boolean> optionalLastLiveStatus = liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), uid);
                        if (optionalLastLiveStatus.isEmpty()) {
                            log.error("备用直播推送未获取到历史直播状态信息, 请向开发者反馈该问题");

                            boolean nowLiveStatus = liveInfo.getLiveStatus() == 1;
                            liveDataService.setLiveStatus(LivePlatform.BILIBILI.getName(), uid, nowLiveStatus);
                            if (nowLiveStatus) {
                                liveDataService.setLiveStartTime(LivePlatform.BILIBILI.getName(), uid, liveInfo.getLiveStartTime());
                            }
                            continue;
                        }

                        Boolean lastLiveStatus = optionalLastLiveStatus.get();
                        if (!lastLiveStatus && liveInfo.getLiveStatus() == 1) {
                            log.warn("备用直播推送捕获到开播事件, 若该日志大量出现, 说明直播间连接已被数据风控");
                            eventPublisher.publishEvent(new BilibiliLiveOnEvent(liveInfo, Instant.now()));
                        } else if (lastLiveStatus && liveInfo.getLiveStatus() != 1) {
                            log.warn("备用直播推送捕获到下播事件, 若该日志大量出现, 说明直播间连接已被数据风控");
                            eventPublisher.publishEvent(new BilibiliLiveOffEvent(liveInfo, Instant.now()));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("备用直播推送异常", e);
            }
        }, interval, interval, TimeUnit.SECONDS);

        log.info("备用直播推送服务已启动");
    }

    /**
     * 新增备用直播推送检测 UID
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceAddEvent(StarBotDataSourceAddEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (hasLivePushEvent(user)) {
            uids.add(user.getUid());
        }
    }

    /**
     * 移除备用直播推送检测 UID
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceRemoveEvent(StarBotDataSourceRemoveEvent event) {
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (hasLivePushEvent(user)) {
            uids.remove(user.getUid());
        }
    }

    /**
     * 新增或移除备用直播推送检测 UID
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onStarBotDataSourceUpdateEvent(StarBotDataSourceUpdateEvent event) {
        PushUser oldUser = event.getOldUser();
        PushUser user = event.getUser();

        if (!LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
            return;
        }

        if (!hasLivePushEvent(oldUser) && hasLivePushEvent(user)) {
            uids.add(user.getUid());
        } else if (hasLivePushEvent(oldUser) && !hasLivePushEvent(user)) {
            uids.remove(user.getUid());
        }
    }

    /**
     * 检查推送用户是否监听开播或下播事件
     * @return 是否监听开播或下播事件
     */
    private boolean hasLivePushEvent(PushUser user) {
        return user.getTargets().stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEvent)
                .anyMatch(event -> BilibiliLiveOnEvent.class.getName().equals(event)
                        || BilibiliLiveOffEvent.class.getName().equals(event));
    }
}
