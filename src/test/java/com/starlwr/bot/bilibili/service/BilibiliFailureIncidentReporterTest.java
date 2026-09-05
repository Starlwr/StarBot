package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliFailureIncidentReporterTest {
    private final StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
    private final BilibiliFailureIncidentReporter reporter = new BilibiliFailureIncidentReporter(properties);

    @AfterEach
    void close() {
        reporter.close();
    }

    @Test
    void aggregatesThreeDistinctRoomsButKeepsFailureCategoriesSeparate() {
        properties.getLive().setDisconnectSummaryRoomThreshold(3);
        properties.getLive().setDisconnectSummaryWindowSeconds(15);
        properties.getLive().setDisconnectSummaryQuietSeconds(60);
        RuntimeException tls = new RuntimeException("Unexpected Status of SSLEngineResult after an unwrap() operation");

        var first = reporter.record(observation(BilibiliFailureIncidentReporter.Category.WS_TLS_ABNORMAL_CLOSE, 1, tls));
        var second = reporter.record(observation(BilibiliFailureIncidentReporter.Category.WS_TLS_ABNORMAL_CLOSE, 2, tls));
        var third = reporter.record(observation(BilibiliFailureIncidentReporter.Category.WS_TLS_ABNORMAL_CLOSE, 3, tls));

        assertFalse(first.suppressWarning());
        assertTrue(first.includeStack());
        assertFalse(second.suppressWarning());
        assertFalse(second.includeStack());
        assertTrue(third.suppressWarning());

        var gateway = reporter.record(observation(BilibiliFailureIncidentReporter.Category.TELEMETRY_HTTP_504, 4,
                new IllegalStateException("WebLog HTTP 504")));
        assertFalse(gateway.suppressWarning());
        assertTrue(gateway.includeStack());
    }

    @Test
    void recognizesNestedTlsAbnormalClose() {
        Throwable error = new RuntimeException("outer",
                new IllegalStateException("Unexpected Status of SSLEngineResult after an unwrap() operation"));
        assertTrue(BilibiliFailureIncidentReporter.isTlsAbnormalClose(1006, error));
        assertFalse(BilibiliFailureIncidentReporter.isTlsAbnormalClose(1000, error));
    }

    private BilibiliFailureIncidentReporter.Observation observation(
            BilibiliFailureIncidentReporter.Category category, long roomId, Throwable error) {
        return new BilibiliFailureIncidentReporter.Observation(category, roomId, "broadcastlv.chat.bilibili.com:443",
                roomId, 1_000, 100, error);
    }
}
