package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.api.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;

/**
 * 直播弹幕连接 -352 风控熔断器（账号级全局）。
 *
 * 语义（与用户约定一致）:
 * - 触发: 仅当 getDanmuInfo 明确返回 -352（WBI 重算重试链已在 API 层完成）连续达到阈值(默认 3 次)
 *   才进入熔断。网络错误、普通超时、服务端抖动一律不计入、不触发。
 * - 熔断期间: 存量健康 WS 连接不受影响（连接建立后不再依赖 getDanmuInfo）;
 *   新建与重连任务暂停等待（保持在各自队列中, 不取消、不重建、不重排）。
 * - 探测: 每个间隔有且仅有一个探测任务（借道既有连接调度队列的队首任务执行一次真实连接尝试）。
 *   间隔阶梯 1m → 5m → 15m → 30m, 30m 为上限, 持续 -352 则持续按 30m 等待。
 * - 解除: 探测结果非 -352（成功或其他错误）即闭合熔断, 恢复全部正常调度。
 */
@Slf4j
@StarBotComponent
public class Danmu352CircuitBreaker {

    private final int threshold;
    private final long[] probeIntervalMillis;

    private final AtomicInteger consecutive352 = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong nextProbeAtMillis = new AtomicLong();
    private final AtomicInteger probeStage = new AtomicInteger();
    private final Lock probeLock = new ReentrantLock();
    private volatile ScheduledThreadPoolExecutor probeScheduler;

    private enum State { CLOSED, OPEN }

    @Autowired
    public Danmu352CircuitBreaker(StarBotBilibiliProperties properties) {
        this.threshold = Math.max(1, properties.getLive().getDanmu352Threshold());
        this.probeIntervalMillis = properties.getLive().danmu352ProbeIntervalsMillis();
    }

    /**
     * 熔断期间任务被拦下后, 建议的重排等待时长(至下一次探测时刻), 供任务服务保持排队。
     */
    public long nextRetryHintMillis() {
        long next = nextProbeAtMillis.get() - System.currentTimeMillis();
        return Math.max(1_000, next);
    }

    /** 熔断是否打开: 打开时新建/重连任务应暂停等待。 */
    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    /**
     * 连接失败回报。仅明确的 -352 计入熔断判定; 其他错误重置计数但不影响熔断状态。
     */
    public void onConnectFailure(Throwable error) {
        if (isRisk352(error)) {
            int count = consecutive352.incrementAndGet();
            if (state.get() == State.OPEN) {
                // 熔断期间每次明确 -352 失败即一次探测失败: 阶梯升级
                probeStage.incrementAndGet();
                return;
            }
            if (count >= threshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
                probeStage.set(0);
                scheduleNextProbe();
                log.warn("直播弹幕 -352 熔断开启: 连续 -352 失败 {} 次 (阈值 {}), 暂停新建/重连, 探测间隔阶梯 {}s; 存量健康连接不受影响",
                        count, threshold, intervalSecondsSnapshot());
            }
        } else {
            consecutive352.set(0);
        }
    }

    /** 连接成功回报: 重置计数; 若处于熔断则立即闭合恢复。 */
    public void onConnectSuccess() {
        consecutive352.set(0);
        if (state.compareAndSet(State.OPEN, State.CLOSED)) {
            log.info("直播弹mu -352 熔断闭合: 连接成功, 恢复正常调度");
        }
    }

    /**
     * 熔断开启期间, 任务执行前的准入检查。返回 false 表示当前应继续等待(任务保持排队)。
     * 探测时刻到达时放行且仅放行一个任务。
     */
    public boolean tryAcquireExecutionSlot() {
        if (state.get() != State.OPEN) return true;
        probeLock.lock();
        try {
            if (state.get() != State.OPEN) return true;
            long now = System.currentTimeMillis();
            if (now < nextProbeAtMillis.get()) return false;
            // 预约下一次探测时间, 保证同间隔内只有一个任务被放行
            long interval = currentIntervalMillis();
            nextProbeAtMillis.set(now + interval);
            log.info("直播弹幕 -352 熔断探测: 放行一个连接任务作为探测 (stage={}, nextIn={}s)",
                    probeStage.get() + 1, TimeUnit.MILLISECONDS.toSeconds(interval));
            return true;
        } finally {
            probeLock.unlock();
        }
    }

    /**
     * 探测任务结果回报。仅持续 -352 继续熔断并进入下一阶梯; 其他任何结果(成功/网络错误)闭合熔断。
     */
    public void onProbeOutcome(Throwable error) {
        if (isRisk352(error)) {
            int stage = probeStage.incrementAndGet();
            log.warn("直播弹幕 -352 熔断探测仍被 -352 拦截: stage={}, 下次探测 {}s 后",
                    stage, TimeUnit.MILLISECONDS.toSeconds(currentIntervalMillis()));
            return;
        }
        consecutive352.set(0);
        if (state.compareAndSet(State.OPEN, State.CLOSED)) {
            probeStage.set(0);
            log.info("直播弹幕 -352 熔断闭合: 探测结果非 -352 ({}), 恢复全部正常调度",
                    error == null ? "成功" : error.getClass().getSimpleName());
        }
    }

    private void scheduleNextProbe() {
        nextProbeAtMillis.set(System.currentTimeMillis() + currentIntervalMillis());
    }

    private long currentIntervalMillis() {
        long[] ladder = probeIntervalMillis;
        int stage = Math.min(probeStage.get(), ladder.length - 1);
        return Math.max(1, ladder[stage]);
    }

    private String intervalSecondsSnapshot() {
        long[] ladder = probeIntervalMillis;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ladder.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(TimeUnit.MILLISECONDS.toSeconds(ladder[i]));
        }
        return sb.append("]").toString();
    }

    /** 仅明确的 getDanmuInfo 业务码 -352 视为风控信号; 网络/超时/抖动一律排除。 */
    private boolean isRisk352(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ResponseCodeException code && code.getCode() == -352) return true;
        }
        return false;
    }

    @PreDestroy
    public synchronized void close() {
        if (probeScheduler != null) probeScheduler.shutdownNow();
    }
}
