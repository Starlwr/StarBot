package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.enums.GuardType;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.MembershipEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Bilibili 大航海事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliMembershipEvent extends MembershipEvent {
    /**
     * 操作类型
     */
    private GuardOperateType operateType;

    /**
     * 大航海类型
     */
    private GuardType type;

    public BilibiliMembershipEvent(LiveStreamerInfo source, UserInfo sender, GuardType type, GuardOperateType operateType, Double price, Integer count, String unit) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit);
        this.type = type;
        this.operateType = operateType;
    }

    public BilibiliMembershipEvent(LiveStreamerInfo source, UserInfo sender, GuardType type, GuardOperateType operateType, Double price, Integer count, String unit, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit, instant);
        this.type = type;
        this.operateType = operateType;
    }
}
