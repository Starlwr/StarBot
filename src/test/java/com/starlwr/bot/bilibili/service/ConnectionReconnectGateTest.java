package com.starlwr.bot.bilibili.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionReconnectGateTest {
    @Test
    void admitsOnlyOneConcurrentReconnectRequest() throws Exception {
        ConnectionReconnectGate gate = new ConnectionReconnectGate();
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(32);
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 32; index++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (gate.trySchedule()) admitted.incrementAndGet();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, admitted.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
