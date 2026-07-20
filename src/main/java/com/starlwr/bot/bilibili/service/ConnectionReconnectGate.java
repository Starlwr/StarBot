package com.starlwr.bot.bilibili.service;

import java.util.concurrent.atomic.AtomicBoolean;

final class ConnectionReconnectGate {
    private final AtomicBoolean scheduled = new AtomicBoolean();

    boolean trySchedule() {
        return scheduled.compareAndSet(false, true);
    }
}
