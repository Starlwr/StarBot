package com.starlwr.bot.bilibili.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.starlwr.bot.bilibili.log.BilibiliDynamicLogFileAppender;
import com.starlwr.bot.bilibili.log.BilibiliLiveLogFileAppender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;

/**
 * StarBotBilibili 日志配置类
 */
@StarBotComponent
public class StarBotBilibiliLogConfig {
    @PostConstruct
    private void init() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        Appender<ILoggingEvent> liveMessageAppender = new BilibiliLiveLogFileAppender();
        liveMessageAppender.setName("LiveMessageAppender");
        liveMessageAppender.setContext(context);
        liveMessageAppender.start();

        Logger liveMessageLogger = context.getLogger("LiveMessageLogger");
        liveMessageLogger.setLevel(Level.DEBUG);
        liveMessageLogger.setAdditive(false);
        liveMessageLogger.addAppender(liveMessageAppender);

        Appender<ILoggingEvent> dynamicAppender = new BilibiliDynamicLogFileAppender();
        dynamicAppender.setName("DynamicAppender");
        dynamicAppender.setContext(context);
        dynamicAppender.start();

        Logger dynamicLogger = context.getLogger("DynamicLogger");
        dynamicLogger.setLevel(Level.DEBUG);
        dynamicLogger.setAdditive(false);
        dynamicLogger.addAppender(dynamicAppender);
    }
}
