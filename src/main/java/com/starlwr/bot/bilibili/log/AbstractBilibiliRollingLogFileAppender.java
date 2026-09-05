package com.starlwr.bot.bilibili.log;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;

import java.nio.charset.StandardCharsets;

/** Daily UTF-8 debug log stream matching the Core NetworkDebug/EventDebug layout. */
abstract class AbstractBilibiliRollingLogFileAppender extends AppenderBase<ILoggingEvent> {
    private final String fileNamePattern;
    private AsyncAppender delegate;

    protected AbstractBilibiliRollingLogFileAppender(String fileNamePattern) {
        this.fileNamePattern = fileNamePattern;
    }

    @Override
    public void start() {
        RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<>();
        rolling.setName(getName() + "RollingFile");
        rolling.setContext(getContext());

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(getContext());
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- [%20.20t] : %msg%n");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        rolling.setEncoder(encoder);

        TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
        policy.setContext(getContext());
        policy.setParent(rolling);
        policy.setFileNamePattern(fileNamePattern);
        policy.start();
        rolling.setRollingPolicy(policy);
        rolling.start();

        delegate = new AsyncAppender();
        delegate.setName(getName() + "Async");
        delegate.setContext(getContext());
        delegate.setQueueSize(8192);
        delegate.setDiscardingThreshold(0);
        delegate.setNeverBlock(false);
        delegate.addAppender(rolling);
        delegate.start();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        delegate.doAppend(event);
    }

    @Override
    public void stop() {
        if (delegate != null) {
            delegate.stop();
        }
        super.stop();
    }
}
