package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.ConnectedEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
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
        super(LivePlatform.BILIBILI, source);
    }

    public BilibiliConnectedEvent(LiveStreamerInfo source, Instant instant) {
        super(LivePlatform.BILIBILI, source, instant);
    }
}
