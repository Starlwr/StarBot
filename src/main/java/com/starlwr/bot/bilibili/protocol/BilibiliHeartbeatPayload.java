package com.starlwr.bot.bilibili.protocol;

import java.nio.charset.StandardCharsets;

/** Browser-compatible WebSocket heartbeat payload for Bilibili live rooms. */
public final class BilibiliHeartbeatPayload {
    public static final String BROWSER_OBJECT_TEXT = "[object Object]";

    private static final byte[] BROWSER_OBJECT = BROWSER_OBJECT_TEXT.getBytes(StandardCharsets.UTF_8);

    private BilibiliHeartbeatPayload() {
    }

    public static byte[] bytes() {
        return BROWSER_OBJECT.clone();
    }
}
