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
 * <h3>Bilibili 开通舰长事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>USER_TOAST_MSG_V2（开通舰长、提督、总督）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"USER_TOAST_MSG_V2","data":{"sender_uinfo":{"uid":404239497,"base":{"name":"洛洛溪水宝宝","face":""}},"receiver_uinfo":{"uid":426636991,"base":{"name":"羊羊提不起劲","face":"https://i1.hdslb.com/bfs/face/8a28edbea7311013cae61b228bc5795e9670c939.jpg"}},"guard_info":{"guard_level":3,"role_name":"舰长","room_guard_count":24,"op_type":2,"start_time":1789456843,"end_time":1789456843},"pay_info":{"payflow_id":"2609151520110632194971554","price":168000,"num":1,"unit":"月"},"gift_info":{"gift_id":10003},"effect_info":{"effect_id":397,"room_effect_id":590,"face_effect_id":44,"room_gift_effect_id":0,"room_group_effect_id":1337,"ship_effect_id":590},"toast_msg":"&lt;%洛洛溪水宝宝%&gt; 在主播羊羊提不起劲的直播间续费了舰长，今天是TA陪伴主播的第30天","option":{"anchor_show":true,"user_show":true,"is_group":0,"is_show":0,"source":0,"svga_block":0,"color":"#00D1F1"}}}</pre>
 * <h4>备注：</h4>
 * <p>无粉丝勋章信息，无荣耀等级信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliCaptainEvent extends BilibiliMembershipEvent {
    public BilibiliCaptainEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit) {
        super(source, sender, GuardType.Captain, operateType, price, count, unit);
    }

    public BilibiliCaptainEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit, Instant instant) {
        super(source, sender, GuardType.Captain, operateType, price, count, unit, instant);
    }
}
