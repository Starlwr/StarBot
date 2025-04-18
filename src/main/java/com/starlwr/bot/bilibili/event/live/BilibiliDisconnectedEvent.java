package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.common.DisconnectedEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Bilibili 连接断开事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliDisconnectedEvent extends DisconnectedEvent {
    public BilibiliDisconnectedEvent(LiveStreamerInfo source) {
        super(LivePlatform.BILIBILI, source);
    }

    public BilibiliDisconnectedEvent(LiveStreamerInfo source, Instant instant) {
        super(LivePlatform.BILIBILI, source, instant);
    }
}
