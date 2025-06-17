package com.starlwr.bot.bilibili.event.dynamic;

import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.dynamic.StarBotBaseDynamicEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Bilibili 动态更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliDynamicUpdateEvent extends StarBotBaseDynamicEvent {
    /**
     * 动态信息
     */
    private Dynamic dynamic;

    public BilibiliDynamicUpdateEvent(LiveStreamerInfo source, Dynamic dynamic) {
        super(LivePlatform.BILIBILI, source);
        this.dynamic = dynamic;
    }

    public BilibiliDynamicUpdateEvent(LiveStreamerInfo source, Dynamic dynamic, Instant instant) {
        super(LivePlatform.BILIBILI, source, instant);
        this.dynamic = dynamic;
    }
}
