package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import java.util.*;
import java.util.concurrent.*;

/** One dispatcher; waiting entries retain their identity, deadline and order. */
@Slf4j
@StarBotComponent
public class BilibiliLiveRoomConnectTaskService {
    private final StarBotBilibiliProperties properties;
    private final Danmu352CircuitBreaker circuitBreaker;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<BilibiliLiveRoomConnector, PendingConnection> pending = new IdentityHashMap<>();
    private final PriorityQueue<PendingConnection> queue = new PriorityQueue<>(Comparator
            .comparingLong((PendingConnection p) -> p.readyAt).thenComparingLong(p -> p.order));
    private ScheduledFuture<?> wakeup;
    private long wakeupAt = Long.MAX_VALUE, sequence, nextSlot;
    private boolean started;
    private record PendingConnection(BilibiliLiveRoomConnector connector, long readyAt, long order) {}
    public BilibiliLiveRoomConnectTaskService(StarBotBilibiliProperties properties, Danmu352CircuitBreaker breaker) {
        this.properties = properties; circuitBreaker = breaker;
        scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "bilibili-connect-scheduler"); thread.setDaemon(true); return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }
    @Order(0) @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public synchronized void onStarBotDataSourceLoadCompleteEvent() {
        if (!properties.getLive().isEnableConnectLiveRoom() || scheduler.isShutdown()) return;
        started = true; arm(); log.info("Bilibili 全局连接调度器已启动: pending={}", pending.size());
    }
    public boolean add(BilibiliLiveRoomConnector connector) { return schedule(connector, 0); }
    public synchronized boolean schedule(BilibiliLiveRoomConnector connector, long delay) {
        if (scheduler.isShutdown() || pending.containsKey(connector)) return false;
        PendingConnection entry = new PendingConnection(connector, System.currentTimeMillis() + Math.max(0, delay), sequence++);
        pending.put(connector, entry); queue.add(entry); arm(); return true;
    }
    /** A verified browser lane can become available before the next JVM probe. */
    public synchronized void refreshAdmission() {
        if (wakeup != null) wakeup.cancel(false);
        wakeup = null; wakeupAt = Long.MAX_VALUE; arm();
    }
    private void arm() {
        if (!started || queue.isEmpty() || scheduler.isShutdown()) return;
        long at = Math.max(queue.peek().readyAt, nextSlot);
        if (circuitBreaker.isOpen()) at = Math.max(at, System.currentTimeMillis() + circuitBreaker.nextRetryHintMillis());
        if (wakeup != null && !wakeup.isDone() && wakeupAt <= at) return;
        if (wakeup != null) wakeup.cancel(false);
        wakeupAt = at;
        wakeup = scheduler.schedule(this::dispatch, Math.max(0, at - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    }
    private void dispatch() {
        BilibiliLiveRoomConnector connector;
        synchronized (this) {
            wakeup = null; wakeupAt = Long.MAX_VALUE;
            if (scheduler.isShutdown() || queue.isEmpty() || !properties.getLive().isEnableConnectLiveRoom()) return;
            if (Math.max(queue.peek().readyAt, nextSlot) > System.currentTimeMillis()
                    || !circuitBreaker.tryAcquireExecutionSlot()) { arm(); return; }
            PendingConnection entry = queue.remove(); pending.remove(entry.connector);
            connector = entry.connector;
            nextSlot = System.currentTimeMillis() + Math.max(1, properties.getLive().getLiveRoomConnectInterval());
            arm();
        }
        try { connector.connect(); }
        catch (RuntimeException error) { log.warn("提交直播间连接任务失败", error); }
    }
    public synchronized boolean remove(BilibiliLiveRoomConnector connector) {
        PendingConnection entry = pending.remove(connector);
        if (entry == null) return false;
        queue.remove(entry);
        if (queue.isEmpty() && wakeup != null) { wakeup.cancel(false); wakeup = null; wakeupAt = Long.MAX_VALUE; }
        return true;
    }
    public synchronized int pendingCount() { return pending.size(); }
    @PreDestroy public synchronized void close() {
        if (wakeup != null) wakeup.cancel(false);
        queue.clear(); pending.clear(); scheduler.shutdownNow();
    }
}
