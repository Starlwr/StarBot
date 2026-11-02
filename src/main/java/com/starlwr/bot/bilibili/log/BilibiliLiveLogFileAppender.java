package com.starlwr.bot.bilibili.log;

/** Daily live raw capture stream under debug/Live. */
public class BilibiliLiveLogFileAppender extends AbstractBilibiliRollingLogFileAppender {
    public BilibiliLiveLogFileAppender() {
        super("debug/Live/%d{yyyy-MM,aux}/starbot-live-%d{yyyy-MM-dd}.log");
    }
}
