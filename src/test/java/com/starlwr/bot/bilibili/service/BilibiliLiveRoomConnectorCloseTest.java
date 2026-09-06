package com.starlwr.bot.bilibili.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliLiveRoomConnectorCloseTest {
    @Test
    void closeDiagnosticsAcceptAnAbsentReason() {
        Map<String, Object> details = BilibiliLiveRoomConnector.closeLogDetails(
                CloseStatus.NO_CLOSE_FRAME, "broadcastlv.chat.bilibili.com:443", 3,
                12_000, Instant.ofEpochMilli(10_000), 2_000);

        assertEquals(CloseStatus.NO_CLOSE_FRAME.getCode(), details.get("code"));
        assertTrue(details.containsKey("reason"));
        assertNull(details.get("reason"));
    }
}
