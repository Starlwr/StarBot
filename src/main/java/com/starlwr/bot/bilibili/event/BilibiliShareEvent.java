package com.starlwr.bot.bilibili.event;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.common.ShareEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 分享事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>INTERACT_WORD（进房、关注、分享）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":132,"fans_medal":{"anchor_roomid":0,"guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":0,"medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","score":0,"special":"","target_id":0},"identities":[1],"is_mystery":false,"is_spread":0,"msg_type":3,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":21422786,"score":1743265711011,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743265711,"trigger_time":1743265710995459000,"uid":180864557,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","name":"冷月丶残星丶"}},"guard":{"expired_str":"","level":0},"uid":180864557},"uname":"冷月丶残星丶","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":132,"fans_medal":{"anchor_roomid":5561470,"guard_level":0,"icon_id":0,"is_lighted":1,"medal_color":12478086,"medal_color_border":12478086,"medal_color_end":12478086,"medal_color_start":12478086,"medal_level":14,"medal_name":"iMia","score":59155,"special":"","target_id":780791},"identities":[3,1],"is_mystery":false,"is_spread":0,"msg_type":3,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":5561470,"score":1743334157833,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743265002,"trigger_time":1743265002822091000,"uid":180864557,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","name":"冷月丶残星丶"}},"guard":{"expired_str":"","level":0},"medal":{"color":12478086,"color_border":12478086,"color_end":12478086,"color_start":12478086,"guard_icon":"","guard_level":0,"honor_icon":"","id":0,"is_light":1,"level":14,"name":"iMia","ruid":780791,"score":59155,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#C85DC499","v2_medal_color_end":"#C85DC499","v2_medal_color_level":"#59005699","v2_medal_color_start":"#C85DC499","v2_medal_color_text":"#FFFFFFFF"},"uid":180864557},"uname":"冷月丶残星丶","uname_color":""}}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>无大航海信息，无荣耀等级信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliShareEvent extends ShareEvent {
    public BilibiliShareEvent(LiveStreamerInfo source, UserInfo sender) {
        super(LivePlatform.BILIBILI, source, sender);
    }

    public BilibiliShareEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
    }
}
