package com.starlwr.bot.bilibili.log;

/** Daily dynamic raw capture stream under debug/Dynamic. */
public class BilibiliDynamicLogFileAppender extends AbstractBilibiliRollingLogFileAppender {
    public BilibiliDynamicLogFileAppender() {
        super("debug/Dynamic/%d{yyyy-MM,aux}/starbot-dynamic-%d{yyyy-MM-dd}.log");
    }
}
