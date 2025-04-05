package com.starlwr.bot.bilibili.event;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.common.ConnectedEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Bilibili 连接成功事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliConnectedEvent extends ConnectedEvent {
    public BilibiliConnectedEvent(LiveStreamerInfo source) {
        super(Platform.BILIBILI, source);
    }

    public BilibiliConnectedEvent(LiveStreamerInfo source, Instant instant) {
        super(Platform.BILIBILI, source, instant);
    }
}
