package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.common.FollowEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 关注事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>INTERACT_WORD（进房、关注、分享）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":9,"fans_medal":{"anchor_roomid":0,"guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":0,"medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","score":0,"special":"","target_id":0},"identities":[1],"is_mystery":false,"is_spread":0,"msg_type":2,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":22889484,"score":1743186946995,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743186946,"trigger_time":1743186944985055500,"uid":3546838380579390,"uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/74dd0bff0d2eafd319328c42cc21b92ace8108f6.jpg","is_mystery":false,"name":"星回数星星","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/74dd0bff0d2eafd319328c42cc21b92ace8108f6.jpg","name":"星回数星星"}},"guard":{"expired_str":"","level":0},"uid":3546838380579390},"uname":"星回数星星","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"INTERACT_WORD","data":{"contribution":{"grade":0},"contribution_v2":{"grade":0,"rank_type":"","text":""},"core_user_type":0,"dmscore":81,"fans_medal":{"anchor_roomid":1756264361,"guard_level":0,"icon_id":0,"is_lighted":1,"medal_color":13081892,"medal_color_border":13081892,"medal_color_end":13081892,"medal_color_start":13081892,"medal_level":20,"medal_name":"未晨年","score":2234725,"special":"","target_id":1773661633},"identities":[3,1],"is_mystery":false,"is_spread":0,"msg_type":2,"privilege_type":0,"relation_tail":{"tail_guide_text":"","tail_icon":"","tail_type":0},"roomid":22889484,"score":1745430955510,"spread_desc":"","spread_info":"","tail_icon":0,"tail_text":"","timestamp":1743186230,"trigger_time":1743186228484817200,"uid":3546719954406222,"uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/f4e4d5d4ca8b8720dd83f45ac800897deed2b595.jpg","is_mystery":false,"name":"元気森林代言人","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/f4e4d5d4ca8b8720dd83f45ac800897deed2b595.jpg","name":"元気森林代言人"}},"guard":{"expired_str":"","level":0},"medal":{"color":13081892,"color_border":13081892,"color_end":13081892,"color_start":13081892,"guard_icon":"","guard_level":0,"honor_icon":"","id":0,"is_light":1,"level":20,"name":"未晨年","ruid":1773661633,"score":2234725,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#DC6B6B99","v2_medal_color_end":"#DC6B6B99","v2_medal_color_level":"#81001F99","v2_medal_color_start":"#DC6B6B99","v2_medal_color_text":"#FFFFFFFF"},"uid":3546719954406222},"uname":"元気森林代言人","uname_color":""}}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>无大航海信息，无荣耀等级信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliFollowEvent extends FollowEvent {
    public BilibiliFollowEvent(LiveStreamerInfo source, UserInfo sender) {
        super(LivePlatform.BILIBILI, source, sender);
    }

    public BilibiliFollowEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
    }
}
