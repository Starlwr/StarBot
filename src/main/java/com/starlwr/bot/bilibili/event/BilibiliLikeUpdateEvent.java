package com.starlwr.bot.bilibili.event;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.common.LikeUpdateEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 点赞数更新事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>LIKE_INFO_V3_UPDATE（点赞数更新）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"LIKE_INFO_V3_UPDATE","data":{"click_count":23037}}</pre>
 * <h4>备注：</h4>
 * <p>无时间戳信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLikeUpdateEvent extends LikeUpdateEvent {
    public BilibiliLikeUpdateEvent(LiveStreamerInfo source, Integer count) {
        super(LivePlatform.BILIBILI, source, count);
    }

    public BilibiliLikeUpdateEvent(LiveStreamerInfo source, Integer count, Instant instant) {
        super(LivePlatform.BILIBILI, source, count, instant);
    }
}
