package com.starlwr.bot.bilibili.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketBufferPropertiesTest {
    @Test void defaultRemainsEightMiB() {
        StarBotBilibiliProperties.Live properties = new StarBotBilibiliProperties.Live();
        assertEquals(8192, properties.getWebSocketBufferSizeInKB());
        assertEquals(8 * 1024 * 1024, properties.getWebSocketBufferSizeBytes());
    }
    @Test void bindsExternalSizeInKB() {
        var source = new MapConfigurationPropertySource(Map.of("starbot.bilibili.live.web-socket-buffer-size-in-kb", "2048"));
        var properties = new Binder(source).bind("starbot.bilibili", StarBotBilibiliProperties.class).orElseThrow(AssertionError::new);
        assertEquals(2048, properties.getLive().getWebSocketBufferSizeInKB());
        assertEquals(2 * 1024 * 1024, properties.getLive().getWebSocketBufferSizeBytes());
    }
    @ParameterizedTest @ValueSource(ints = {0, -1, 2097152, Integer.MAX_VALUE})
    void rejectsInvalidOrOverflowingBufferSize(int size) {
        StarBotBilibiliProperties.Live properties = new StarBotBilibiliProperties.Live();
        properties.setWebSocketBufferSizeInKB(size);
        assertThrows(IllegalArgumentException.class, properties::getWebSocketBufferSizeBytes);
    }
}
