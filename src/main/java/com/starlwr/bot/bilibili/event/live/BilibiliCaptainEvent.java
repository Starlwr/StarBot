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
 * <h3>Bilibili 开通舰长事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>USER_TOAST_MSG（开通舰长、提督、总督）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>开通舰长</p>
 *         <pre>{"cmd":"USER_TOAST_MSG","data":{"anchor_show":true,"color":"#00D1F1","dmscore":204,"effect_id":397,"end_time":1743521272,"face_effect_id":44,"gift_id":10003,"group_name":"","group_op_type":0,"group_role_name":"","guard_level":3,"is_group":0,"is_show":0,"num":1,"op_type":1,"payflow_id":"2504012327241862175246702","price":138000,"role_name":"舰长","room_effect_id":590,"room_gift_effect_id":0,"room_group_effect_id":1337,"source":0,"start_time":1743521272,"svga_block":0,"target_guard_count":168,"toast_msg":"<%有一颗童心的开拓者%> 在主播钉宫妮妮Ninico的直播间开通了舰长，今天是TA陪伴主播的第1天","uid":3493134891747524,"unit":"月","user_show":true,"username":"有一颗童心的开拓者"},"msg_id":"28818744307358208:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743521272554}</pre>
 *     </li>
 *     <li>
 *         <p>138 续费舰长</p>
 *         <pre>{"cmd":"USER_TOAST_MSG","data":{"anchor_show":true,"color":"#00D1F1","dmscore":306,"effect_id":397,"end_time":1743264044,"face_effect_id":44,"gift_id":10003,"group_name":"","group_op_type":0,"group_role_name":"","guard_level":3,"is_group":0,"is_show":0,"num":1,"op_type":2,"payflow_id":"2503300000006702573905850","price":138000,"role_name":"舰长","room_effect_id":590,"room_gift_effect_id":0,"room_group_effect_id":1337,"source":0,"start_time":1743264044,"svga_block":0,"target_guard_count":131,"toast_msg":"<%路人Kamito%> 在主播Mia米娅-的直播间续费了舰长，今天是TA陪伴主播的第1021天","uid":2067390,"unit":"月","user_show":true,"username":"路人Kamito"},"msg_id":"28683882618948096:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743264044297}</pre>
 *     </li>
 *     <li>
 *         <p>168 续费舰长</p>
 *         <pre>{"cmd":"USER_TOAST_MSG","data":{"anchor_show":true,"color":"#00D1F1","dmscore":408,"effect_id":397,"end_time":1743520712,"face_effect_id":44,"gift_id":10003,"group_name":"","group_op_type":0,"group_role_name":"","guard_level":3,"is_group":0,"is_show":0,"num":1,"op_type":2,"payflow_id":"2504012318144982131206432","price":168000,"role_name":"舰长","room_effect_id":590,"room_gift_effect_id":0,"room_group_effect_id":1337,"source":0,"start_time":1743520712,"svga_block":0,"target_guard_count":184,"toast_msg":"<%冷雨殇澜%> 在主播梦音茶糯的直播间续费了舰长，今天是TA陪伴主播的第809天","uid":373573120,"unit":"月","user_show":true,"username":"冷雨殇澜"},"msg_id":"28818450927870464:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743520712977}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>无粉丝勋章信息，无荣耀等级信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliCaptainEvent extends MembershipEvent {
    /**
     * 操作类型
     */
    private GuardOperateType operateType;

    public BilibiliCaptainEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit);
        this.operateType = operateType;
    }

    public BilibiliCaptainEvent(LiveStreamerInfo source, UserInfo sender, GuardOperateType operateType, Double price, Integer count, String unit, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit, instant);
        this.operateType = operateType;
    }
}
