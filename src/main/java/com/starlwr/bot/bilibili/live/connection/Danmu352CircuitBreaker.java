package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.api.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import com.starlwr.bot.bilibili.risk.BrowserIdentityRegistry;

/** Account-wide getDanmu admission. Healthy sockets never participate in this gate. */
@Slf4j
@StarBotComponent
public class Danmu352CircuitBreaker {
    private final int threshold;
    private final long[] intervals;
    private final LongSupplier clock;
    private BooleanSupplier browserReady = () -> false;
    private int consecutive352, stage, admitted;
    private boolean open, probing;
    private boolean browserWarmed;
    private long nextProbeAt;

    @Autowired
    public Danmu352CircuitBreaker(StarBotBilibiliProperties properties, BrowserIdentityRegistry registry) {
        this(properties, System::currentTimeMillis);
        browserReady = () -> registry.lastVerified() != null;
    }
    Danmu352CircuitBreaker(StarBotBilibiliProperties properties, LongSupplier clock) {
        threshold = Math.max(1, properties.getLive().getDanmu352Threshold());
        intervals = properties.getLive().danmu352ProbeIntervalsMillis(); this.clock = clock;
    }
    public synchronized boolean isOpen() { return open; }
    public synchronized boolean jvmProbeDue() { return !open || clock.getAsLong() >= nextProbeAt; }
    public synchronized boolean requiresHealthProof(boolean browser) { return open && (!browser || !browserWarmed); }
    public synchronized long nextRetryHintMillis() {
        return probing || admitted > 0 || (open && browserReady.getAsBoolean()) ? 250 : Math.max(1, nextProbeAt - clock.getAsLong());
    }
    public synchronized boolean tryAcquireExecutionSlot() {
        if (open && (probing || admitted > 0 || (!browserReady.getAsBoolean() && clock.getAsLong() < nextProbeAt))) return false;
        admitted++;
        if (open) { probing = true; log.info("直播弹幕 -352 探测放行一个任务: stage={}", stage + 1); }
        return true;
    }
    /** Browser recovery does not prove recovery of the refused JVM identity. */
    public synchronized void onIdentityOutcome(boolean browser) {
        if (!browser) { complete(null); return; }
        if (admitted > 0) admitted--;
        probing = false;
        browserWarmed = true;
    }
    /** Soft pause at the first real refusal, without discarding queued work. */
    public synchronized void observeJvmRejection() {
        consecutive352++;
        if (consecutive352 == threshold) log.warn("getDanmuInfo JVM 身份累计 {} 次明确拒绝，风险状态已确认", threshold);
        if (!open) {
            open = true; stage = 0; nextProbeAt = clock.getAsLong() + intervals[0];
            log.warn("getDanmuInfo JVM 身份明确 -352，暂停普通准入；验证后放行浏览器通道，健康 WS 保持");
        }
    }
    /** Exactly once per getDanmu attempt, after WBI/browser retries. */
    public synchronized void onConnectFailure(Throwable error) { complete(error); }
    public synchronized void onProbeOutcome(Throwable error) { complete(error); }
    public synchronized void onConnectSuccess() { complete(null); }
    public synchronized void onExecutionSkipped() {
        if (admitted > 0) admitted--;
        if (probing) probing = false;
    }
    private void complete(Throwable error) {
        if (admitted > 0) admitted--;
        if (error != null && !browserReady.getAsBoolean()) browserWarmed = false;
        if (open) {
            // Drain pre-open requests; they cannot masquerade as the single probe.
            if (!probing) return;
            probing = false;
            if (error == null) {
                open = false; consecutive352 = 0; stage = 0;
                log.info("直播弹幕 -352 熔断闭合: getDanmuInfo 明确成功");
            } else {
                if (isRisk352(error)) stage = Math.min(stage + 1, intervals.length - 1);
                // Network failure neither proves recovery nor escalates risk.
                nextProbeAt = clock.getAsLong() + intervals[stage];
                log.warn("直播弹幕探测未恢复: explicit352={}, nextInMs={}", isRisk352(error), intervals[stage]);
            }
        } else if (isRisk352(error)) {
            if (++consecutive352 >= threshold) {
                open = true; stage = 0; nextProbeAt = clock.getAsLong() + intervals[0];
                log.warn("直播弹幕 -352 熔断开启: 连续明确 -352 达到 {} 次；健康连接保持，任务原位等待", threshold);
            }
        } else consecutive352 = 0;
    }
    private static boolean isRisk352(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause())
            if (e instanceof ResponseCodeException r && r.getCode() == -352) return true;
        return false;
    }
}
