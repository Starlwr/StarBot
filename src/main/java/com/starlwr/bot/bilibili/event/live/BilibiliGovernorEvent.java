package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.enums.GuardType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
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
public class BilibiliGovernorEvent extends BilibiliMembershipEvent {
    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit) {
        super(source, sender, GuardType.Governor, operateType, price, count, unit);
    }

    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit, Instant instant) {
        super(source, sender, GuardType.Governor, operateType, price, count, unit, instant);
    }
}
