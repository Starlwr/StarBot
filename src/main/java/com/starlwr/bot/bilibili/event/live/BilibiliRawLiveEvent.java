package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/** Raw event retained when Bilibili introduces a command without a stable schema. */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRawLiveEvent extends StarBotBaseLiveEvent {
    private String command;
    private String payload;

    public BilibiliRawLiveEvent(LiveStreamerInfo source, String command, String payload) {
        super(LivePlatform.BILIBILI, source, Instant.now());
        this.command = command;
        this.payload = payload;
    }
}
