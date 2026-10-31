package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.api.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class Danmu352CircuitBreakerTest {
    @Test void browserRecoveryReleasesQueueWithoutClosingJvmCircuit() {
        var registry = new com.starlwr.bot.bilibili.risk.BrowserIdentityRegistry();
        var gate = new Danmu352CircuitBreaker(new StarBotBilibiliProperties(), registry);
        assertTrue(gate.tryAcquireExecutionSlot());
        gate.observeJvmRejection();
        gate.onConnectFailure(risk());
        assertTrue(gate.isOpen()); assertFalse(gate.tryAcquireExecutionSlot());
        registry.recordVerified(new com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot(java.util.Map.of(), "b", "42", "verified"));
        assertTrue(gate.tryAcquireExecutionSlot());
        assertFalse(gate.tryAcquireExecutionSlot(), "only one browser recovery in flight");
        assertTrue(gate.requiresHealthProof(true));
        gate.onIdentityOutcome(true);
        assertTrue(gate.isOpen(), "browser success cannot release JVM lane");
        assertFalse(gate.requiresHealthProof(true));
        assertTrue(gate.tryAcquireExecutionSlot()); gate.onIdentityOutcome(true);
        registry.clear();
        assertFalse(gate.tryAcquireExecutionSlot(), "stale overlay cannot bypass JVM cooldown");
    }
    private final AtomicLong now = new AtomicLong(1000);
    private final StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
    private final Danmu352CircuitBreaker breaker = new Danmu352CircuitBreaker(properties, now::get);
    private ResponseCodeException risk() { return new ResponseCodeException(-352, "risk"); }
    private void open() {
        for (int i = 0; i < 3; i++) { assertTrue(breaker.tryAcquireExecutionSlot()); breaker.onConnectFailure(risk()); }
        assertTrue(breaker.isOpen());
    }
    @Test void onlyExplicit352TriggersAndLadderIsScheduledAfterOutcome() {
        breaker.onConnectFailure(new RuntimeException("timeout")); assertFalse(breaker.isOpen());
        open();
        for (long wait : new long[]{60_000, 300_000, 900_000, 1_800_000, 1_800_000}) {
            assertEquals(wait, breaker.nextRetryHintMillis());
            now.addAndGet(wait - 1); assertFalse(breaker.tryAcquireExecutionSlot());
            now.incrementAndGet(); assertTrue(breaker.tryAcquireExecutionSlot());
            now.addAndGet(3_600_000); assertFalse(breaker.tryAcquireExecutionSlot(), "slow CAPTCHA cannot allow another probe");
            breaker.onConnectFailure(risk());
        }
    }
    @Test void networkProbeFailureDoesNotReopenQueueOrEscalateLadder() {
        open(); now.addAndGet(60_000); assertTrue(breaker.tryAcquireExecutionSlot());
        breaker.onConnectFailure(new RuntimeException("network"));
        assertTrue(breaker.isOpen()); assertEquals(60_000, breaker.nextRetryHintMillis());
        now.addAndGet(60_000); assertTrue(breaker.tryAcquireExecutionSlot());
        breaker.onProbeOutcome(null); assertFalse(breaker.isOpen());
    }
    @Test void outstandingInitialRequestsCannotAdvanceProbeLadder() {
        for (int i=0;i<5;i++) assertTrue(breaker.tryAcquireExecutionSlot());
        for (int i=0;i<3;i++) breaker.onConnectFailure(risk());
        now.addAndGet(60_000); assertFalse(breaker.tryAcquireExecutionSlot());
        breaker.onConnectFailure(risk()); breaker.onConnectFailure(risk());
        assertTrue(breaker.tryAcquireExecutionSlot());
        breaker.onConnectFailure(risk()); assertEquals(300_000, breaker.nextRetryHintMillis());
    }
}
