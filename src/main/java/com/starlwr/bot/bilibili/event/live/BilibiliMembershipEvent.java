package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.live.gift.GuardOperateType;
import com.starlwr.bot.bilibili.live.gift.GuardType;
import com.starlwr.bot.core.event.live.common.MembershipEvent;
import com.starlwr.bot.core.event.model.LivePlatform;
import com.starlwr.bot.core.event.model.LiveStreamerInfo;
import com.starlwr.bot.core.event.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/** Common Bilibili event base for captain, commander and governor changes. */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliMembershipEvent extends MembershipEvent {
    private GuardOperateType operateType;
    private GuardType type;

    public BilibiliMembershipEvent(LiveStreamerInfo source, UserInfo sender, GuardType type,
                                  GuardOperateType operateType, Double price, Integer count, String unit) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit);
        this.type = type;
        this.operateType = operateType;
    }

    public BilibiliMembershipEvent(LiveStreamerInfo source, UserInfo sender, GuardType type,
                                  GuardOperateType operateType, Double price, Integer count, String unit,
                                  Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit, instant);
        this.type = type;
        this.operateType = operateType;
    }
}
