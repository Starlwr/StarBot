package com.starlwr.bot.bilibili.event;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.common.EnterRoomEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 进入房间事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>INTERACT_WORD（进房、关注、分享）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":33,"identities":[1],"is_mystery":false,"is_spread":0,"msg_type":1,"privilege_type":0,"relation_tail":{"tail_guide_text":"曾经活跃过，近期与你互动较少","tail_icon":"https://i0.hdslb.com/bfs/live/bb88734558c6383a4cfb5fa16c9749d5290d95e8.png","tail_type":4},"roomid":24853527,"score":1743188666084,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743188666,"trigger_time":1743188664868013600,"uid":180864557,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":""},"guard":{"expired_str":"","level":0},"uid":180864557,"wealth":{"dm_icon_key":"","level":24}},"uname":"冷月丶残星丶","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":33,"fans_medal":{"anchor_roomid":5561470,"guard_level":0,"icon_id":0,"is_lighted":1,"medal_color":12478086,"medal_color_border":12478086,"medal_color_end":12478086,"medal_color_start":12478086,"medal_level":14,"medal_name":"iMia","score":58255,"special":"","target_id":780791},"identities":[3,1],"is_mystery":false,"is_spread":0,"msg_type":1,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":5561470,"score":1743258324841,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743190069,"trigger_time":1743190068756264000,"uid":180864557,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":""},"guard":{"expired_str":"","level":0},"medal":{"color":12478086,"color_border":12478086,"color_end":12478086,"color_start":12478086,"guard_icon":"","guard_level":0,"honor_icon":"","id":467746,"is_light":1,"level":14,"name":"iMia","ruid":780791,"score":58255,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#C85DC499","v2_medal_color_end":"#C85DC499","v2_medal_color_level":"#59005699","v2_medal_color_start":"#C85DC499","v2_medal_color_text":"#FFFFFFFF"},"uid":180864557,"wealth":{"dm_icon_key":"","level":24}},"uname":"冷月丶残星丶","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>无大航海</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":10,"fans_medal":{"anchor_roomid":5561470,"guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":12478086,"medal_color_border":12632256,"medal_color_end":12632256,"medal_color_start":12632256,"medal_level":16,"medal_name":"iMia","score":134654,"special":"","target_id":780791},"identities":[1],"is_mystery":false,"is_spread":0,"msg_type":1,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":5561470,"score":1743257412707,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743257412,"trigger_time":1743257411576849400,"uid":622970139,"uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/fef20397b99190670ab385b541c121118f124c92.webp","is_mystery":false,"name":"平川_K","name_color":0,"name_color_str":""},"guard":{"expired_str":"","level":0},"medal":{"color":12478086,"color_border":12632256,"color_end":12632256,"color_start":12632256,"guard_icon":"","guard_level":0,"honor_icon":"","id":467746,"is_light":0,"level":16,"name":"iMia","ruid":780791,"score":134654,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#919298CC","v2_medal_color_end":"#919298CC","v2_medal_color_level":"#6C6C7299","v2_medal_color_start":"#919298CC","v2_medal_color_text":"#FFFFFFFF"},"uid":622970139,"wealth":{"dm_icon_key":"","level":8}},"uname":"平川_K","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>有大航海</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":68,"fans_medal":{"anchor_roomid":5561470,"guard_level":3,"icon_id":0,"is_lighted":1,"medal_color":398668,"medal_color_border":6809855,"medal_color_end":6850801,"medal_color_start":398668,"medal_level":27,"medal_name":"iMia","score":50110567,"special":"","target_id":780791},"identities":[6,3,1],"is_mystery":false,"is_spread":0,"msg_type":1,"privilege_type":3,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":5561470,"score":1793378106316,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743257539,"trigger_time":1743257538220115000,"uid":34308408,"uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/926d287b7094f43dd0e76d52e6b054392363b65c.jpg","is_mystery":false,"name":"晓枫达","name_color":0,"name_color_str":"#00D1F1"},"guard":{"expired_str":"2026-03-20 23:59:59","level":3},"medal":{"color":398668,"color_border":6809855,"color_end":6850801,"color_start":398668,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":467746,"is_light":1,"level":27,"name":"iMia","ruid":780791,"score":50110567,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"uid":34308408,"wealth":{"dm_icon_key":"","level":32}},"uname":"晓枫达","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>流量包推广，不含有粉丝勋章及荣耀等级信息</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":3,"fans_medal":{"anchor_roomid":0,"guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":0,"medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","score":0,"special":"","target_id":0},"identities":[1],"is_mystery":false,"is_spread":1,"msg_type":1,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":5561470,"score":1743258993518,"spread_desc":"流量包推广","spread_info":"#FF649E","tail_icon":0,"tail_text":"","timestamp":1743258983,"trigger_time":1743258983509426700,"uid":2545922,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/29f00b7b60bff9df9cb8ff3d3068b53e2aaf7209.jpg","is_mystery":false,"name":"没睡醒的灵不凌","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/29f00b7b60bff9df9cb8ff3d3068b53e2aaf7209.jpg","name":"没睡醒的灵不凌"}},"uid":2545922},"uname":"没睡醒的灵不凌","uname_color":""}}</pre>
 *     </li>
 * </ol>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEnterRoomEvent extends EnterRoomEvent {
    /**
     * 是否来自推广
     */
    private boolean fromPromotion;

    /**
     * 推广来源（流量包推广等）
     */
    private String promotionSource;

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender, boolean fromPromotion, String promotionSource) {
        super(LivePlatform.BILIBILI, source, sender);
        this.fromPromotion = fromPromotion;
        this.promotionSource = promotionSource;
    }

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender, boolean fromPromotion, String promotionSource, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
        this.fromPromotion = fromPromotion;
        this.promotionSource = promotionSource;
    }
}
