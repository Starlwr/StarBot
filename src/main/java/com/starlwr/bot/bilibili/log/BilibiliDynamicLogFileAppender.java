package com.starlwr.bot.bilibili.log;

/** Daily DynamicDebug capture stream. */
public class BilibiliDynamicLogFileAppender extends AbstractBilibiliRollingLogFileAppender {
    public BilibiliDynamicLogFileAppender() {
        super("DynamicDebug/%d{yyyy-MM,aux}/starbot-dynamic-%d{yyyy-MM-dd}.log");
    }
}
