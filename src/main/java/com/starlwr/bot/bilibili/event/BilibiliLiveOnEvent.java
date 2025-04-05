package com.starlwr.bot.bilibili.event;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.common.LiveOnEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 开播事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>LIVE（开播）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"LIVE","live_key":"591274000023608446","voice_background":"","sub_session_key":"591274000023608446sub_time:1743685980","live_platform":"pc_link","live_model":0,"roomid":5561470,"live_time":1743685980}</pre>
 * <h4>备注：</h4>
 * <p>无 live_time 字段不是真正开播</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLiveOnEvent extends LiveOnEvent {
    public BilibiliLiveOnEvent(LiveStreamerInfo source) {
        super(Platform.BILIBILI, source);
    }

    public BilibiliLiveOnEvent(LiveStreamerInfo source, Instant instant) {
        super(Platform.BILIBILI, source, instant);
    }
}
