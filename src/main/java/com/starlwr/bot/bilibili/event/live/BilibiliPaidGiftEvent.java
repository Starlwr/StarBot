package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.PaidGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 付费礼物事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>SEND_GIFT（投喂礼物）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"SEND_GIFT","danmu":{"area":1},"data":{"action":"投喂","batch_combo_id":"batch:gift:combo_id:180864557:3546576018475844:31036:1743522360.7703","batch_combo_send":{"action":"投喂","batch_combo_id":"batch:gift:combo_id:180864557:3546576018475844:31036:1743522360.7703","batch_combo_num":1,"gift_id":31036,"gift_name":"小花花","gift_num":1,"uid":180864557,"uname":"冷月丶残星丶"},"beatId":"0","biz_source":"Live","broadcast_id":0,"coin_type":"gold","combo_resources_id":1,"combo_send":{"action":"投喂","combo_id":"gift:combo_id:180864557:3546576018475844:31036:1743522360.7693","combo_num":1,"gift_id":31036,"gift_name":"小花花","gift_num":1,"uid":180864557,"uname":"冷月丶残星丶"},"combo_stay_time":5,"combo_total_coin":100,"crit_prob":0,"demarcation":1,"discount_price":100,"dmscore":42,"draw":0,"effect":0,"effect_block":0,"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","face_effect_id":0,"face_effect_type":0,"face_effect_v2":{"id":0,"type":0},"float_sc_resource_id":0,"giftId":31036,"giftName":"小花花","giftType":0,"gift_info":{"effect_id":0,"gif":"https://i0.hdslb.com/bfs/live/c806ee29394aab4877fa3d535daca5c66c631306.gif","has_imaged_gift":0,"img_basic":"https://s1.hdslb.com/bfs/live/8b40d0470890e7d573995383af8a8ae074d485d9.png","webp":"https://i0.hdslb.com/bfs/live/28357ba4cd566418730ca29da2c552efa7e4a390.webp"},"gift_tag":[],"gold":0,"guard_level":0,"is_first":true,"is_join_receiver":false,"is_naming":false,"is_special_batch":0,"magnification":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":0,"medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","special":"","target_id":0},"name_color":"","num":1,"original_gift_name":"","price":100,"rcost":5483419,"receive_user_info":{"uid":3546576018475844,"uname":"天羽优优Yuu"},"receiver_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/800fbd23fac23c7a615ef6a1c83243e9e8272105.jpg","is_mystery":false,"name":"天羽优优Yuu","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/800fbd23fac23c7a615ef6a1c83243e9e8272105.jpg","name":"天羽优优Yuu"}},"uid":3546576018475844},"remain":0,"rnd":"4624547020991011328","sender_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","name":"冷月丶残星丶"}},"uid":180864557},"silver":0,"super":0,"super_batch_gift_num":1,"super_gift_num":1,"svga_block":0,"switch":true,"tag_image":"","tid":"4624547020991011328","timestamp":1743522360,"total_coin":100,"uid":180864557,"uname":"冷月丶残星丶","wealth_level":24}}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"SEND_GIFT","danmu":{"area":1},"data":{"action":"投喂","batch_combo_id":"batch:gift:combo_id:180864557:140378:31036:1743519923.1744","batch_combo_send":{"action":"投喂","batch_combo_id":"batch:gift:combo_id:180864557:140378:31036:1743519923.1744","batch_combo_num":1,"gift_id":31036,"gift_name":"小花花","gift_num":1,"uid":180864557,"uname":"冷月丶残星丶"},"beatId":"0","biz_source":"Live","broadcast_id":0,"coin_type":"gold","combo_resources_id":1,"combo_send":{"action":"投喂","combo_id":"gift:combo_id:180864557:140378:31036:1743519923.1727","combo_num":1,"gift_id":31036,"gift_name":"小花花","gift_num":1,"uid":180864557,"uname":"冷月丶残星丶"},"combo_stay_time":5,"combo_total_coin":100,"crit_prob":0,"demarcation":1,"discount_price":100,"dmscore":336,"draw":0,"effect":0,"effect_block":0,"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","face_effect_id":0,"face_effect_type":0,"face_effect_v2":{"id":0,"type":0},"float_sc_resource_id":0,"giftId":31036,"giftName":"小花花","giftType":0,"gift_info":{"effect_id":0,"gif":"https://i0.hdslb.com/bfs/live/c806ee29394aab4877fa3d535daca5c66c631306.gif","has_imaged_gift":0,"img_basic":"https://s1.hdslb.com/bfs/live/8b40d0470890e7d573995383af8a8ae074d485d9.png","webp":"https://i0.hdslb.com/bfs/live/28357ba4cd566418730ca29da2c552efa7e4a390.webp"},"gift_tag":[],"gold":0,"guard_level":0,"is_first":true,"is_join_receiver":false,"is_naming":false,"is_special_batch":0,"magnification":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":0,"icon_id":0,"is_lighted":1,"medal_color":12478086,"medal_color_border":12478086,"medal_color_end":12478086,"medal_color_start":12478086,"medal_level":15,"medal_name":"iMia","special":"","target_id":780791},"name_color":"","num":1,"original_gift_name":"","price":100,"rcost":122243723,"receive_user_info":{"uid":140378,"uname":"梦音茶糯"},"receiver_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/ea50e0c20017f5d5fab3d10d322a2a514a6c13c5.jpg","is_mystery":false,"name":"梦音茶糯","name_color":0,"name_color_str":"","official_info":{"desc":"","role":1,"title":"bilibili 知名虚拟UP主、直播高能主播","type":0},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/ea50e0c20017f5d5fab3d10d322a2a514a6c13c5.jpg","name":"梦音茶糯"}},"uid":140378},"remain":0,"rnd":"4624536796980696064","sender_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","name":"冷月丶残星丶"}},"medal":{"color":9272486,"color_border":12632256,"color_end":12632256,"color_start":12632256,"guard_icon":"","guard_level":0,"honor_icon":"","id":0,"is_light":0,"level":11,"name":"梦音符","ruid":140378,"score":24011,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#919298CC","v2_medal_color_end":"#919298CC","v2_medal_color_level":"#6C6C7299","v2_medal_color_start":"#919298CC","v2_medal_color_text":"#FFFFFFFF"},"uid":180864557},"silver":0,"super":0,"super_batch_gift_num":1,"super_gift_num":1,"svga_block":0,"switch":true,"tag_image":"","tid":"4624536796980696064","timestamp":1743519923,"total_coin":100,"uid":180864557,"uname":"冷月丶残星丶","wealth_level":24}}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>主播开启优先展示本房间勋章时，原始数据中两个粉丝勋章信息不一致，应使用 sender_uinfo 中的信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliPaidGiftEvent extends PaidGiftEvent {
    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, instant);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, value);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, value, instant);
    }
}
