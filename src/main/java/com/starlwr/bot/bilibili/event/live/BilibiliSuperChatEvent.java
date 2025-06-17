package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.SuperChatEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 醒目留言事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>SUPER_CHAT_MESSAGE（收到醒目留言）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"SUPER_CHAT_MESSAGE","data":{"background_bottom_color":"#427D9E","background_color":"#DBFFFD","background_color_end":"#29718B","background_color_start":"#4EA4C5","background_icon":"","background_image":"","background_price_color":"#7DA4BD","color_point":0.7,"dmscore":84,"end_time":1743782070,"gift":{"gift_id":12000,"gift_name":"醒目留言","num":1},"group_medal":{"is_lighted":0,"medal_id":0,"name":""},"id":12108845,"is_mystery":false,"is_ranked":0,"is_send_audit":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":"","medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","special":"","target_id":0},"message":"海海我只是个学生，但你在我心中是特别的，我唯独没有脆鲨牌，点歌p3你的记忆","message_font_color":"#A3F6FF","message_trans":"海海私はただの学生ですが、あなたは私の心の中で特別で、私はただ脆いサメの札がなくて、歌を注文してp 3あなたの記憶","price":52,"rate":1000,"start_time":1743781950,"time":120,"token":"79E6EF2D","trans_mark":0,"ts":1743781950,"uid":346665539,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/d7576dad444de6d78ae1c79a1fc65b2753596c7a.jpg","is_mystery":false,"name":"_光の救世主","name_color":0,"name_color_str":"#666666","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/d7576dad444de6d78ae1c79a1fc65b2753596c7a.jpg","name":"_光の救世主"}},"guard":{"expired_str":"","level":0},"title":{"old_title_css_id":"","title_css_id":""},"uid":346665539},"user_info":{"face":"https://i2.hdslb.com/bfs/face/d7576dad444de6d78ae1c79a1fc65b2753596c7a.jpg","face_frame":"","guard_level":0,"is_main_vip":1,"is_svip":0,"is_vip":0,"level_color":"#969696","manager":0,"name_color":"#666666","title":"","uname":"_光の救世主","user_level":2}},"is_report":true,"msg_id":"28955414842843137:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743781950913}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"SUPER_CHAT_MESSAGE","data":{"background_bottom_color":"#2A60B2","background_color":"#EDF5FF","background_color_end":"#405D85","background_color_start":"#3171D2","background_icon":"","background_image":"","background_price_color":"#7497CD","color_point":0.7,"dmscore":1008,"end_time":1743521261,"gift":{"gift_id":12000,"gift_name":"醒目留言","num":1},"group_medal":{"is_lighted":0,"medal_id":0,"name":""},"id":12087464,"is_mystery":false,"is_ranked":0,"is_send_audit":0,"medal_info":{"anchor_roomid":958617,"anchor_uname":"梦音茶糯","guard_level":2,"icon_id":0,"is_lighted":1,"medal_color":"#06154c","medal_color_border":16771156,"medal_color_end":6850801,"medal_color_start":398668,"medal_level":28,"medal_name":"梦音符","special":"","target_id":140378},"message":"在理，但是4月1日还没有结束，所以...这句话也是反的","message_font_color":"#A3F6FF","message_trans":"","price":30,"rate":1000,"start_time":1743521201,"time":60,"token":"FB5E88A9","trans_mark":0,"ts":1743521201,"uid":641809105,"uinfo":{"base":{"face":"https://i1.hdslb.com/bfs/face/b5e473d67e6c0b6c8ddd75150746174c713bc88a.jpg","is_mystery":false,"name":"路边的Pet","name_color":0,"name_color_str":"#E17AFF","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i1.hdslb.com/bfs/face/b5e473d67e6c0b6c8ddd75150746174c713bc88a.jpg","name":"路边的Pet"}},"guard":{"expired_str":"2025-04-08 23:59:59","level":2},"medal":{"color":398668,"color_border":16771156,"color_end":6850801,"color_start":398668,"guard_icon":"https://i0.hdslb.com/bfs/live/98a201c14a64e860a758f089144dcf3f42e7038c.png","guard_level":2,"honor_icon":"","id":0,"is_light":1,"level":28,"name":"梦音符","ruid":140378,"score":50237675,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":641809105},"user_info":{"face":"https://i1.hdslb.com/bfs/face/b5e473d67e6c0b6c8ddd75150746174c713bc88a.jpg","face_frame":"https://i0.hdslb.com/bfs/live/09937c3beb0608e267a50ac3c7125c3f2d709098.png","guard_level":2,"is_main_vip":0,"is_svip":0,"is_vip":0,"level_color":"#5896de","manager":0,"name_color":"#E17AFF","title":"","uname":"路边的Pet","user_level":21}},"is_report":true,"msg_id":"28818706909374465:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743521201223}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>无荣耀等级信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliSuperChatEvent extends SuperChatEvent {
    public BilibiliSuperChatEvent(LiveStreamerInfo source, UserInfo sender, String content, Double value) {
        super(LivePlatform.BILIBILI, source, sender, content, value);
    }

    public BilibiliSuperChatEvent(LiveStreamerInfo source, UserInfo sender, String content, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, value, instant);
    }
}
