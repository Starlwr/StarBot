package com.starlwr.bot.bilibili.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.FileAppender;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RawMessageLogFileAppender extends AppenderBase<ILoggingEvent> {
    private final Map<String, AsyncAppender> appenders = new ConcurrentHashMap<>();

    @Override
    protected void append(ILoggingEvent event) {
        String type = (String) event.getArgumentArray()[0];

        AsyncAppender asyncAppender = appenders.computeIfAbsent(type, this::createAppenderForType);
        asyncAppender.doAppend(event);
    }

    private AsyncAppender createAppenderForType(String type) {
        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();

        fileAppender.setName("FILE-" + type);
        fileAppender.setFile("RawMessageDebug/" + type + ".log");
        fileAppender.setAppend(true);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(getContext());
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.setContext(getContext());
        fileAppender.start();

        AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setName("ASYNC-FILE-" + type);
        asyncAppender.setContext(getContext());
        asyncAppender.setQueueSize(512);
        asyncAppender.setDiscardingThreshold(0);
        asyncAppender.setNeverBlock(true);
        asyncAppender.addAppender(fileAppender);
        asyncAppender.start();

        return asyncAppender;
    }
}
