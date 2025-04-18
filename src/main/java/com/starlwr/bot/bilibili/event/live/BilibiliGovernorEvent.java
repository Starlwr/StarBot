package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.common.MembershipEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 开通总督事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>USER_TOAST_MSG（开通舰长、提督、总督）</li>
 * </ul>
 * <h4>示例：</h4>
 * <p>参见 {@link BilibiliCaptainEvent}</p>
 * <h4>备注：</h4>
 * <p>无粉丝勋章信息，无荣耀等级信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliGovernorEvent extends MembershipEvent {
    /**
     * 操作类型
     */
    private GuardOperateType operateType;

    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit);
        this.operateType = operateType;
    }

    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit, instant);
        this.operateType = operateType;
    }
}
