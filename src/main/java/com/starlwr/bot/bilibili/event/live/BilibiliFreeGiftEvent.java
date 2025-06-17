package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.FreeGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 免费礼物事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>SEND_GIFT（投喂礼物）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"SEND_GIFT","danmu":{"area":0},"data":{"action":"投喂","batch_combo_id":"","beatId":"0","biz_source":"Live","broadcast_id":0,"coin_type":"silver","combo_resources_id":1,"combo_stay_time":5,"combo_total_coin":0,"crit_prob":0,"demarcation":2,"discount_price":0,"dmscore":275,"draw":0,"effect":3,"effect_block":1,"face":"https://i1.hdslb.com/bfs/face/cefcd9c8e87e84d2f283dd274f2e208f6dfe4d26.webp","face_effect_id":0,"face_effect_type":0,"face_effect_v2":{"id":5632012,"type":1},"float_sc_resource_id":0,"giftId":31738,"giftName":"粉丝团灯牌","giftType":5,"gift_info":{"effect_id":0,"gif":"https://i0.hdslb.com/bfs/live/816f8b7aa2132888fce928cdfb17b9cf21cc0823.gif","has_imaged_gift":0,"img_basic":"https://s1.hdslb.com/bfs/live/e051dfd4557678f8edcac4993ed00a0935cbd9cc.png","webp":"https://i0.hdslb.com/bfs/live/32b799120e1614fa6275b6d15da7a52b21dd019d.webp"},"gift_tag":[1201],"gold":0,"guard_level":0,"is_first":true,"is_join_receiver":false,"is_naming":false,"is_special_batch":0,"magnification":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":2,"icon_id":0,"is_lighted":1,"medal_color":2951253,"medal_color_border":16771156,"medal_color_end":10329087,"medal_color_start":2951253,"medal_level":30,"medal_name":"细猫","special":"","target_id":62017617},"name_color":"","num":1,"original_gift_name":"","price":1000,"rcost":122245460,"receive_user_info":{"uid":140378,"uname":"梦音茶糯"},"receiver_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/ea50e0c20017f5d5fab3d10d322a2a514a6c13c5.jpg","is_mystery":false,"name":"梦音茶糯","name_color":0,"name_color_str":"","official_info":{"desc":"","role":1,"title":"bilibili 知名虚拟UP主、直播高能主播","type":0},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/ea50e0c20017f5d5fab3d10d322a2a514a6c13c5.jpg","name":"梦音茶糯"}},"uid":140378},"remain":139,"rnd":"4624547221658941440","sender_uinfo":{"base":{"face":"https://i1.hdslb.com/bfs/face/cefcd9c8e87e84d2f283dd274f2e208f6dfe4d26.webp","is_mystery":false,"name":"特34","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i1.hdslb.com/bfs/face/cefcd9c8e87e84d2f283dd274f2e208f6dfe4d26.webp","name":"特34"}},"uid":32900809},"silver":0,"super":0,"super_batch_gift_num":0,"super_gift_num":0,"svga_block":0,"switch":true,"tag_image":"","tid":"4624547221658941440","timestamp":1743522408,"total_coin":1000,"uid":32900809,"uname":"特34","wealth_level":42}}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"SEND_GIFT","danmu":{"area":1},"data":{"action":"投喂","batch_combo_id":"","beatId":"","biz_source":"live","broadcast_id":0,"coin_type":"silver","combo_resources_id":1,"combo_stay_time":5,"combo_total_coin":0,"crit_prob":0,"demarcation":1,"discount_price":0,"dmscore":340,"draw":0,"effect":0,"effect_block":1,"face":"https://i1.hdslb.com/bfs/face/62503086205e98b4df44bf0dc0ec876e57a7bc86.jpg","face_effect_id":0,"face_effect_type":0,"face_effect_v2":{"id":0,"type":0},"float_sc_resource_id":0,"giftId":33987,"giftName":"人气票","giftType":5,"gift_info":{"effect_id":0,"gif":"https://i0.hdslb.com/bfs/live/a9f5d1f903582cff18f94014f51afaf012f0e2f9.gif","has_imaged_gift":0,"img_basic":"https://s1.hdslb.com/bfs/live/7164c955ec0ed7537491d189b821cc68f1bea20d.png","webp":"https://i0.hdslb.com/bfs/live/5a2012094bb875a04ea7d036edde3e9c00127ec2.webp"},"gift_tag":[1101],"gold":0,"guard_level":0,"is_first":true,"is_join_receiver":false,"is_naming":false,"is_special_batch":0,"magnification":1,"medal_info":{"anchor_roomid":0,"anchor_uname":"","guard_level":3,"icon_id":0,"is_lighted":1,"medal_color":2951253,"medal_color_border":6809855,"medal_color_end":10329087,"medal_color_start":2951253,"medal_level":29,"medal_name":"iMia","special":"","target_id":780791},"name_color":"","num":1,"original_gift_name":"","price":0,"rcost":62889010,"receive_user_info":{"uid":780791,"uname":"Mia米娅-"},"receiver_uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/f06ed5d32437a74b655fdeaeb7c4443e7ae2f7bb.jpg","is_mystery":false,"name":"Mia米娅-","name_color":0,"name_color_str":"","official_info":{"desc":"","role":7,"title":"bilibili直播高能主播","type":0},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/f06ed5d32437a74b655fdeaeb7c4443e7ae2f7bb.jpg","name":"Mia米娅-"}},"uid":780791},"remain":0,"rnd":"4623470873037303808","sender_uinfo":{"base":{"face":"https://i1.hdslb.com/bfs/face/62503086205e98b4df44bf0dc0ec876e57a7bc86.jpg","is_mystery":false,"name":"无声夜歌","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i1.hdslb.com/bfs/face/62503086205e98b4df44bf0dc0ec876e57a7bc86.jpg","name":"无声夜歌"}},"medal":{"color":2951253,"color_border":6809855,"color_end":10329087,"color_start":2951253,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":0,"is_light":1,"level":29,"name":"iMia","ruid":780791,"score":50477825,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#D47AFFFF","v2_medal_color_end":"#9660E5CC","v2_medal_color_level":"#6C00A099","v2_medal_color_start":"#9660E5CC","v2_medal_color_text":"#FFFFFFFF"},"uid":13038610},"silver":0,"super":0,"super_batch_gift_num":0,"super_gift_num":0,"svga_block":0,"switch":true,"tag_image":"","tid":"4623470873037303808","timestamp":1743265787,"total_coin":0,"uid":13038610,"uname":"无声夜歌","wealth_level":39}}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>主播开启优先展示本房间勋章时，原始数据中两个粉丝勋章信息不一致，应使用 sender_uinfo 中的信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliFreeGiftEvent extends FreeGiftEvent {
    public BilibiliFreeGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo);
    }

    public BilibiliFreeGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, instant);
    }
}
