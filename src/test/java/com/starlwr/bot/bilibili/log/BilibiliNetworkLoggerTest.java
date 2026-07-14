package com.starlwr.bot.bilibili.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliNetworkLoggerTest {
    private static final String HTTP_LOGGER = "com.starlwr.bot.bilibili.network.http";

    @Test
    void rawDebugModePreservesReplayDataWithoutTruncation() {
        StarBotBilibiliProperties properties = properties(true, 0);
        String message = capture(properties, "refresh_token=secret-value", Map.of("Cookie", "SESSDATA=session-value"));

        assertTrue(message.contains("secret-value"));
        assertTrue(message.contains("session-value"));
        assertFalse(message.contains("truncated"));
    }

    @Test
    void defaultDebugModeRedactsSensitiveData() {
        StarBotBilibiliProperties properties = properties(false, 16_384);
        String message = capture(properties, "refresh_token=secret-value", Map.of("Cookie", "SESSDATA=session-value"));

        assertFalse(message.contains("secret-value"));
        assertFalse(message.contains("session-value"));
        assertTrue(message.contains("<redacted>"));
    }

    @Test
    void consoleCategoriesFilterCollectionTypes() {
        StarBotBilibiliProperties properties = properties(false, 16_384);
        properties.getNetwork().setConsoleCategories(Set.of("dynamic"));

        List<String> messages = captureAll(properties, logger -> {
            logger.httpRequest("bilibili-api#1", "GET", "https://api.live.bilibili.com/room/v1/Room/get_info", Map.of(), null);
            logger.httpRequest("bilibili-api#1", "GET", "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all", Map.of(), null);
        });

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("category=dynamic"));
    }

    @Test
    void duplicateDebugPayloadPrintsOneChangeNoticeThenSuppresses() {
        StarBotBilibiliProperties properties = properties(false, 16_384);

        List<String> messages = captureAll(properties, logger -> {
            logger.httpRequest("bilibili-api#1", "GET", "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all", Map.of(), null);
            logger.httpRequest("bilibili-api#1", "GET", "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all", Map.of(), null);
            logger.httpRequest("bilibili-api#1", "GET", "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all", Map.of(), null);
        });

        assertEquals(2, messages.size());
        assertTrue(messages.get(0).contains("category=dynamic"));
        assertTrue(messages.get(1).contains("日志内容未变化"));
    }

    private StarBotBilibiliProperties properties(boolean includeSensitive, int maxBodyLength) {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getNetwork().setHttpLogEnabled(true);
        properties.getNetwork().setIncludeSensitiveData(includeSensitive);
        properties.getNetwork().setLogMaxBodyLength(maxBodyLength);
        return properties;
    }

    private String capture(StarBotBilibiliProperties properties, String body, Map<String, String> headers) {
        return captureAll(properties,
                logger -> logger.httpRequest("test", "POST", "https://example.test", headers, body)).get(0);
    }

    private List<String> captureAll(StarBotBilibiliProperties properties, Consumer<BilibiliNetworkLogger> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(HTTP_LOGGER);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.accept(new BilibiliNetworkLogger(properties));
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
