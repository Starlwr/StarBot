package com.starlwr.bot.bilibili.log;

/** Daily LiveDebug capture stream. */
public class BilibiliLiveLogFileAppender extends AbstractBilibiliRollingLogFileAppender {
    public BilibiliLiveLogFileAppender() {
        super("LiveDebug/%d{yyyy-MM,aux}/starbot-live-%d{yyyy-MM-dd}.log");
    }
}
