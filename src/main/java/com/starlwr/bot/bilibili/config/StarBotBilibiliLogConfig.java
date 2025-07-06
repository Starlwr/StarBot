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
import org.springframework.context.annotation.Configuration;

/**
 * StarBotBilibili 日志配置类
 */
@Configuration
@StarBotComponent
public class StarBotBilibiliLogConfig {
    @PostConstruct
    public void init() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        Appender<ILoggingEvent> rawMessageAppender = new BilibiliLiveLogFileAppender();
        rawMessageAppender.setName("RawMessageAppender");
        rawMessageAppender.setContext(context);
        rawMessageAppender.start();

        Logger rawMessageLogger = context.getLogger("RawMessageLogger");
        rawMessageLogger.setLevel(Level.DEBUG);
        rawMessageLogger.setAdditive(false);
        rawMessageLogger.addAppender(rawMessageAppender);

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
