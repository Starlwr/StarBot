package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.RandomGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 盲盒礼物事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>SEND_GIFT（投喂礼物）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"SEND_GIFT","danmu":{"area":0},"data":{"action":"投喂","batch_combo_id":"b2811510-0224-49da-b922-19265d4b581b","batch_combo_send":{"action":"投喂","batch_combo_id":"b2811510-0224-49da-b922-19265d4b581b","batch_combo_num":1,"blind_gift":{"blind_gift_config_id":122,"from":0,"gift_action":"爆出","gift_tip_price":9000,"original_gift_id":34914,"original_gift_name":"整蛊盲盒","original_gift_price":9000},"gift_id":34919,"gift_name":"小丑气球","gift_num":1,"uid":16955759,"uname":"乱码有人用了"},"beatId":"","biz_source":"live","blind_gift":{"blind_gift_config_id":122,"from":0,"gift_action":"爆出","gift_tip_price":9000,"original_gift_id":34914,"original_gift_name":"整蛊盲盒","original_gift_price":9000},"broadcast_id":0,"coin_type":"gold","combo_resources_id":1,"combo_send":{"action":"投喂","combo_id":"36766664-92da-41d6-84eb-1c1edc220b08","combo_num":1,"gift_id":34919,"gift_name":"小丑气球","gift_num":1,"uid":16955759,"uname":"乱码有人用了"},"combo_stay_time":5,"combo_total_coin":9000,"crit_prob":0,"demarcation":2,"discount_price":9000,"dmscore":462,"draw":0,"effect":2,"effect_block":0,"face":"https://i0.hdslb.com/bfs/face/member/noface.jpg","face_effect_id":0,"face_effect_type":0,"face_effect_v2":{"id":0,"type":0},"float_sc_resource_id":0,"giftId":34919,"giftName":"小丑气球","giftType":0,"gift_info":{"effect_id":2123,"gif":"https://i0.hdslb.com/bfs/live/52a17ba3e314a2cf3a0d2046a55b759d24710d45.gif","has_imaged_gift":1,"img_basic":"https://s1.hdslb.com/bfs/live/c33b6f50e0d5ac40afb553b0359b0e819f813d12.png","webp":"https://i0.hdslb.com/bfs/live/ca95f8cb0d461c2be6c665fa2ddebc5b3abe7cd8.webp"},"gift_tag":[],"gold":0,"guard_level":0,"is_first":true,"is_join_receiver":false,"is_naming":false,"is_special_batch":0,"magnification":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":3,"icon_id":0,"is_lighted":1,"medal_color":398668,"medal_color_border":6809855,"medal_color_end":6850801,"medal_color_start":398668,"medal_level":26,"medal_name":"渡洁人","special":"","target_id":176166181},"name_color":"","num":1,"original_gift_name":"","price":9000,"rcost":121978340,"receive_user_info":{"uid":140378,"uname":"梦音茶糯"},"receiver_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/ea50e0c20017f5d5fab3d10d322a2a514a6c13c5.jpg","is_mystery":false,"name":"梦音茶糯","name_color":0,"name_color_str":"","official_info":{"desc":"","role":1,"title":"bilibili 知名虚拟UP主、直播高能主播","type":0},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/ea50e0c20017f5d5fab3d10d322a2a514a6c13c5.jpg","name":"梦音茶糯"}},"uid":140378},"remain":0,"rnd":"4623470640584770048","sender_uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/member/noface.jpg","is_mystery":false,"name":"乱码有人用了","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/member/noface.jpg","name":"乱码有人用了"}},"uid":16955759},"silver":0,"super":0,"super_batch_gift_num":1,"super_gift_num":1,"svga_block":0,"switch":true,"tag_image":"","tid":"4623470640584770048","timestamp":1743265731,"total_coin":9000,"uid":16955759,"uname":"乱码有人用了","wealth_level":29},"msg_id":"28684767299378688:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743265731691}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"SEND_GIFT","danmu":{"area":0},"data":{"action":"投喂","batch_combo_id":"78b57e95-3ac9-49f3-919a-e7f960630f0f","batch_combo_send":{"action":"投喂","batch_combo_id":"78b57e95-3ac9-49f3-919a-e7f960630f0f","batch_combo_num":1,"blind_gift":{"blind_gift_config_id":51,"from":0,"gift_action":"爆出","gift_tip_price":16000,"original_gift_id":32251,"original_gift_name":"心动盲盒","original_gift_price":15000},"gift_id":34628,"gift_name":"荣耀皇冠","gift_num":1,"uid":4105324,"uname":"羽於菟"},"beatId":"","biz_source":"live","blind_gift":{"blind_gift_config_id":51,"from":0,"gift_action":"爆出","gift_tip_price":16000,"original_gift_id":32251,"original_gift_name":"心动盲盒","original_gift_price":15000},"broadcast_id":0,"coin_type":"gold","combo_resources_id":1,"combo_send":{"action":"投喂","combo_id":"537bd0c1-bcaa-46fd-a1b8-29f493fb2150","combo_num":1,"gift_id":34628,"gift_name":"荣耀皇冠","gift_num":1,"uid":4105324,"uname":"羽於菟"},"combo_stay_time":5,"combo_total_coin":16000,"crit_prob":0,"demarcation":2,"discount_price":16000,"dmscore":952,"draw":0,"effect":2,"effect_block":0,"face":"https://i1.hdslb.com/bfs/face/86e8c3adf3f7f8f96d974fa6dd346a1e0eb7dade.webp","face_effect_id":0,"face_effect_type":0,"face_effect_v2":{"id":0,"type":0},"float_sc_resource_id":0,"giftId":34628,"giftName":"荣耀皇冠","giftType":0,"gift_info":{"effect_id":1875,"gif":"https://i0.hdslb.com/bfs/live/3ccd9f45162fe2a24fc5b4ad427f418fffeb4f02.gif","has_imaged_gift":1,"img_basic":"https://s1.hdslb.com/bfs/live/2fccdcc4b903dab1bb072feb93d8610673ea51cd.png","webp":"https://i0.hdslb.com/bfs/live/3cac28892eb898ec3f042d6b3c066fd998d53b74.webp"},"gift_tag":[],"gold":0,"guard_level":3,"is_first":true,"is_join_receiver":false,"is_naming":false,"is_special_batch":0,"magnification":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":0,"medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","special":"","target_id":0},"name_color":"#00D1F1","num":1,"original_gift_name":"","price":16000,"rcost":62892514,"receive_user_info":{"uid":780791,"uname":"Mia米娅-"},"receiver_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/f06ed5d32437a74b655fdeaeb7c4443e7ae2f7bb.jpg","is_mystery":false,"name":"Mia米娅-","name_color":0,"name_color_str":"","official_info":{"desc":"","role":7,"title":"bilibili直播高能主播","type":0},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/f06ed5d32437a74b655fdeaeb7c4443e7ae2f7bb.jpg","name":"Mia米娅-"}},"uid":780791},"remain":0,"rnd":"4623480221717312000","sender_uinfo":{"base":{"face":"https://i1.hdslb.com/bfs/face/86e8c3adf3f7f8f96d974fa6dd346a1e0eb7dade.webp","is_mystery":false,"name":"羽於菟","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i1.hdslb.com/bfs/face/86e8c3adf3f7f8f96d974fa6dd346a1e0eb7dade.webp","name":"羽於菟"}},"medal":{"color":1725515,"color_border":6809855,"color_end":5414290,"color_start":1725515,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":0,"is_light":1,"level":23,"name":"iMia","ruid":780791,"score":50006214,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#5FC7F4FF","v2_medal_color_end":"#43B3E3CC","v2_medal_color_level":"#00308C99","v2_medal_color_start":"#43B3E3CC","v2_medal_color_text":"#FFFFFFFF"},"uid":4105324},"silver":0,"super":0,"super_batch_gift_num":1,"super_gift_num":1,"svga_block":0,"switch":true,"tag_image":"","tid":"4623480221717312000","timestamp":1743268015,"total_coin":15000,"uid":4105324,"uname":"羽於菟","wealth_level":37},"msg_id":"28685964935698946:1000:1000","p_is_ack":true,"p_msg_type":1,"send_time":1743268016001}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>主播开启优先展示本房间勋章时，原始数据中两个粉丝勋章信息不一致，应使用 sender_uinfo 中的信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRandomGiftEvent extends RandomGiftEvent {
    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, instant);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value, instant);
    }
}
