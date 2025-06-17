package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 下播事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>PREPARING（下播）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"PREPARING","msg_id":"28956786538635776:1000:1000","p_is_ack":true,"p_msg_type":1,"roomid":"5561470","send_time":1743784567215}</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLiveOffEvent extends LiveOffEvent {
    public BilibiliLiveOffEvent(LiveStreamerInfo source) {
        super(LivePlatform.BILIBILI, source);
    }

    public BilibiliLiveOffEvent(LiveStreamerInfo source, Instant instant) {
        super(LivePlatform.BILIBILI, source, instant);
    }
}
