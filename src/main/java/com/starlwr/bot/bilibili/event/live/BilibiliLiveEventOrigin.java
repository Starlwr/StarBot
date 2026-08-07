package com.starlwr.bot.bilibili.event.live;

/** Origin metadata for consumers that need to distinguish realtime and fallback observations. */
public enum BilibiliLiveEventOrigin {
    REALTIME,
    BACKUP_PUSH
}
