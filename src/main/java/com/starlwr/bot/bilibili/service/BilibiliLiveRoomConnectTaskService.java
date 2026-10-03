package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Account-level connection/reconnection scheduler. It spreads a 1000-room
 * startup or outage across time and never sleeps a live-room worker thread.
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveRoomConnectTaskService {
    private final StarBotBilibiliProperties properties;
    private final ScheduledThreadPoolExecutor scheduler;
    // Identity matters when an old connector and its replacement have the same UID.
    private final Map<BilibiliLiveRoomConnector, PendingConnection> pending = new IdentityHashMap<>();
    private final Set<BilibiliLiveRoomConnector> deferred = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicLong nextConnectionSlotMillis = new AtomicLong();
    private volatile boolean started;

    private static final class PendingConnection { private ScheduledFuture<?> future; }

    public BilibiliLiveRoomConnectTaskService(StarBotBilibiliProperties properties) {
        this.properties = properties;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "bilibili-connect-scheduler");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = new ScheduledThreadPoolExecutor(2, factory);
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    @Order(0)
    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public synchronized void onStarBotDataSourceLoadCompleteEvent() {
        if (!properties.getLive().isEnableConnectLiveRoom()) {
            log.warn("未启用直播间连接, 将不会连接到直播间, 数据抓取等服务不可用");
            return;
        }
        started = true;
        deferred.forEach(connector -> schedule(connector, 0));
        deferred.clear();
        log.info("Bilibili 全局连接调度器已启动: pending={}, intervalMs={}",
                pending.size(), properties.getLive().getLiveRoomConnectInterval());
    }

    public boolean add(BilibiliLiveRoomConnector connector) {
        return schedule(connector, 0);
    }

    public synchronized boolean schedule(BilibiliLiveRoomConnector connector, long minimumDelayMillis) {
        if (scheduler.isShutdown()) return false;
        if (!started) return deferred.add(connector);
        if (pending.containsKey(connector)) return false;
        long now = System.currentTimeMillis();
        long earliest = now + Math.max(0, minimumDelayMillis);
        long interval = Math.max(1, properties.getLive().getLiveRoomConnectInterval());
        long slot = nextConnectionSlotMillis.getAndUpdate(previous -> Math.max(previous + interval, earliest + interval));
        long scheduledAt = Math.max(earliest, slot);
        long delay = Math.max(0, scheduledAt - now);
        PendingConnection task = new PendingConnection();
        task.future = scheduler.schedule(() -> {
            synchronized (this) {
                // Registration completes under this same lock before a zero-delay task can run.
                if (pending.get(connector) != task || scheduler.isShutdown()) return;
                pending.remove(connector);
            }
            if (!properties.getLive().isEnableConnectLiveRoom()) return;
            Up up = connector.getUp();
            log.debug("执行直播间连接任务: uid={}, room={}, pending={}", up.getUid(), up.getRoomIdString(), pendingCount());
            connector.connect();
        }, delay, TimeUnit.MILLISECONDS);
        pending.put(connector, task);
        return true;
    }

    public synchronized boolean remove(BilibiliLiveRoomConnector connector) {
        boolean removedDeferred = deferred.remove(connector);
        PendingConnection task = pending.remove(connector);
        boolean cancelled = task != null && task.future.cancel(false);
        return removedDeferred || cancelled;
    }

    public synchronized int pendingCount() { return pending.size(); }

    @PreDestroy
    public synchronized void close() {
        pending.values().forEach(task -> task.future.cancel(false));
        pending.clear();
        deferred.clear();
        scheduler.shutdownNow();
    }
}
