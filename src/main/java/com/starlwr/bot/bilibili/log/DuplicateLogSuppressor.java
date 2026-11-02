package com.starlwr.bot.bilibili.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Bounded, content-based duplicate suppression state. */
final class DuplicateLogSuppressor {
    private static final int MAX_ENTRIES = 8192;
    private final Map<String, State> states = new HashMap<>();
    private long operations;

    Result evaluate(String stableContent, long windowSeconds) {
        long boundedSeconds = Math.min(7 * 24 * 60 * 60L, Math.max(1, windowSeconds));
        long windowNanos = Duration.ofSeconds(boundedSeconds).toNanos();
        long now = System.nanoTime();
        String fingerprint = fingerprint(stableContent);

        synchronized (states) {
            cleanup(now, windowNanos);
            State state = states.get(fingerprint);
            if (state == null || now - state.lastSeenNanos > windowNanos) {
                states.put(fingerprint, new State(now));
                return new Result(Action.FULL, 0, fingerprint);
            }
            if (now - state.windowStartedNanos >= windowNanos) {
                int suppressed = state.suppressed + 1;
                state.windowStartedNanos = now;
                state.lastSeenNanos = now;
                state.suppressed = 0;
                state.noticeEmitted = false;
                return new Result(Action.SUMMARY, suppressed, fingerprint);
            }

            state.lastSeenNanos = now;
            state.suppressed++;
            if (!state.noticeEmitted) {
                state.noticeEmitted = true;
                return new Result(Action.NOTICE, 0, fingerprint);
            }
            return new Result(Action.SUPPRESS, 0, fingerprint);
        }
    }

    private void cleanup(long now, long windowNanos) {
        operations++;
        if (operations % 256 != 0 && states.size() < MAX_ENTRIES) {
            return;
        }
        states.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos > windowNanos * 2);
        if (states.size() >= MAX_ENTRIES) {
            int removeCount = states.size() - MAX_ENTRIES / 2;
            var iterator = states.keySet().iterator();
            while (removeCount-- > 0 && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private String fingerprint(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    enum Action { FULL, NOTICE, SUMMARY, SUPPRESS }

    record Result(Action action, int suppressed, String fingerprint) { }

    private static final class State {
        private long windowStartedNanos;
        private long lastSeenNanos;
        private int suppressed;
        private boolean noticeEmitted;

        private State(long now) {
            windowStartedNanos = now;
            lastSeenNanos = now;
        }
    }
}
