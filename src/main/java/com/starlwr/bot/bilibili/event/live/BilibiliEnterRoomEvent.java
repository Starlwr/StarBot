package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.EnterRoomEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 进入房间事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>INTERACT_WORD_V2（进房、关注、分享）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"INTERACT_WORD_V2","data":{"pb":"CNXghQYSCVBORVVNQTM3MyIBASgBMPS/7Ao4zu3k1AZA6q34s4Y0YgB4h+WknpP98OgYmgEAsgFkCNXghQYSVwoJUE5FVU1BMzczEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS80NjJlZTMzYjFmYzVmOWE1NTEyYmQ0NzRlODg1NTczYWRmN2E5NzE2LmpwZyICCAoyALoBAMIBAA=="}}</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEnterRoomEvent extends EnterRoomEvent {
    /**
     * 是否来自推广
     */
    private boolean fromPromotion;

    /**
     * 推广来源（流量包推广等）
     */
    private String promotionSource;

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender, boolean fromPromotion, String promotionSource) {
        super(LivePlatform.BILIBILI, source, sender);
        this.fromPromotion = fromPromotion;
        this.promotionSource = promotionSource;
    }

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender, boolean fromPromotion, String promotionSource, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
        this.fromPromotion = fromPromotion;
        this.promotionSource = promotionSource;
    }
}
