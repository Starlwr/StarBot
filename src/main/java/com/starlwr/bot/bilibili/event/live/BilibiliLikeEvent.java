package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LikeEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 点赞事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>LIKE_INFO_V3_CLICK（收到点赞）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"LIKE_INFO_V3_CLICK","data":{"contribution_info":{"grade":0},"dmscore":12,"fans_medal":{"anchor_roomid":0,"guard_level":0,"icon_id":0,"is_lighted":0,"medal_color":0,"medal_color_border":0,"medal_color_end":0,"medal_color_start":0,"medal_level":0,"medal_name":"","score":0,"special":"","target_id":0},"identities":[1],"is_mystery":false,"like_icon":"https://i0.hdslb.com/bfs/live/23678e3d90402bea6a65251b3e728044c21b1f0f.png","like_text":"为主播点赞了","msg_type":6,"show_area":0,"uid":1353290369,"uinfo":{"base":{"face":"https://i2.hdslb.com/bfs/face/85c18bfd0c1fbe7283f329bbe67d777af6ba985c.jpg","is_mystery":false,"name":"感觉不如藤田琴音","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/85c18bfd0c1fbe7283f329bbe67d777af6ba985c.jpg","name":"感觉不如藤田琴音"}},"guard":{"expired_str":"","level":0},"uid":1353290369},"uname":"感觉不如藤田琴音","uname_color":""}}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"LIKE_INFO_V3_CLICK","data":{"contribution_info":{"grade":0},"dmscore":136,"fans_medal":{"anchor_roomid":0,"guard_level":3,"icon_id":0,"is_lighted":1,"medal_color":398668,"medal_color_border":6809855,"medal_color_end":6850801,"medal_color_start":398668,"medal_level":25,"medal_name":"小早凉","score":50022067,"special":"","target_id":518817},"identities":[6,3,1],"is_mystery":false,"like_icon":"https://i0.hdslb.com/bfs/live/23678e3d90402bea6a65251b3e728044c21b1f0f.png","like_text":"为主播点赞了","msg_type":6,"show_area":0,"uid":284587437,"uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/6ccd9abf572efb3a977427172353d96c2b914b10.jpg","is_mystery":false,"name":"幻月光时","name_color":0,"name_color_str":"#00D1F1","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/6ccd9abf572efb3a977427172353d96c2b914b10.jpg","name":"幻月光时"}},"guard":{"expired_str":"2025-04-29 23:59:59","level":3},"medal":{"color":398668,"color_border":6809855,"color_end":6850801,"color_start":398668,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":0,"is_light":1,"level":25,"name":"小早凉","ruid":518817,"score":50022067,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"uid":284587437},"uname":"幻月光时","uname_color":""}}</pre>
 *     </li>
 * </ol>
 * <h4>备注：</h4>
 * <p>无荣耀等级信息，无时间戳信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLikeEvent extends LikeEvent {
    public BilibiliLikeEvent(LiveStreamerInfo source, UserInfo sender) {
        super(LivePlatform.BILIBILI, source, sender);
    }

    public BilibiliLikeEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
    }
}
