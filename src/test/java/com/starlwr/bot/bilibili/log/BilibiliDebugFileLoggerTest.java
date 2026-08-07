package com.starlwr.bot.bilibili.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliDebugFileLoggerTest {
    @Test
    void fileDuplicatesUseAnIndependentNineHundredSecondPolicy() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getNetwork().setFileDeduplicate(true);
        properties.getNetwork().setFileDeduplicateSeconds(900);

        List<String> messages = capture("DynamicLogger", logger -> {
            logger.dynamic("DYNAMIC_TYPE_AV", "123", "{\"id\":\"123\"}");
            logger.dynamic("DYNAMIC_TYPE_AV", "123", "{\"id\":\"123\"}");
            logger.dynamic("DYNAMIC_TYPE_AV", "123", "{\"id\":\"123\"}");
        }, properties);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0).contains("category=dynamic type=DYNAMIC_TYPE_AV dynamicId=123 payload="));
        assertTrue(messages.get(1).contains("status=UNCHANGED windowSeconds=900"));
    }

    @Test
    void fileCategoriesSupportPerMessageTypeSelectors() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getNetwork().setFileCategories(Set.of("live:DANMU_MSG"));

        List<String> messages = capture("LiveMessageLogger", logger -> {
            logger.live("ONLINE_RANK_V3", 100L, "rank");
            logger.live("DANMU_MSG", 100L, "danmu");
        }, properties);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("category=live type=DANMU_MSG room=100 payload=danmu"));
    }

    private List<String> capture(String loggerName, Consumer<BilibiliDebugFileLogger> action,
                                 StarBotBilibiliProperties properties) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            action.accept(new BilibiliDebugFileLogger(properties));
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            appender.stop();
        }
    }
}
