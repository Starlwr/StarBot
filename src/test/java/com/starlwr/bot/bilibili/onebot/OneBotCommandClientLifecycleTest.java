package com.starlwr.bot.bilibili.onebot;

import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OneBotCommandClientLifecycleTest {
    @Test
    void closeIsAnExplicitBeanDestroyCallback() throws Exception {
        assertTrue(OneBotCommandClient.class.getMethod("close").isAnnotationPresent(PreDestroy.class));
    }
}
