package com.starlwr.bot.bilibili.event;

import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.common.EmojiEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 表情事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>DANMU_MSG（收到弹幕）</li>
 * </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,65532,1743186852478,1743186846,0,"8474c88d",0,0,0,"",1,{"bulge_display":1,"emoticon_unique":"upower_[不问天_赞]","height":20,"in_player_area":1,"is_dynamic":0,"url":"https://i0.hdslb.com/bfs/emote/667ee5cc4471eb8d38b2817e5359e2c604882268.png","width":20},"{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":65532,\"dm_type\":1,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"[不问天_赞]\",\"user_hash\":\"2222246029\",\"emoticon_unique\":\"upower_[不问天_赞]\",\"bulge_display\":1,\"recommend_score\":0,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"2315567e616d1e204fa4a89f2a67e6eb7762\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i2.hdslb.com/bfs/face/d6f5b5bdae7e13704f843e353a1852c2fc79244b.jpg","is_mystery":false,"name":"milet孤3Avicii","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/d6f5b5bdae7e13704f843e353a1852c2fc79244b.jpg","name":"milet孤3Avicii"}},"guard_leader":{"is_guard_leader":false},"title":{"old_title_css_id":"","title_css_id":""},"uid":14620190}},{"activity_identity":"","activity_source":0,"not_show":0},0],"[不问天_赞]",[14620190,"milet孤3Avicii",0,0,0,10000,1,""],[],[21,0,5805790,">50000",0],["",""],0,0,null,{"ct":"4A9FB1C1","ts":1743186852},0,0,null,null,0,66,[11],null]}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,9920249,1743180859932,-1287578308,0,"12f7ff6f",0,0,0,"",1,{"bulge_display":1,"emoticon_unique":"room_5561470_15046","height":162,"in_player_area":1,"is_dynamic":0,"url":"http://i0.hdslb.com/bfs/live/78c8b16e61a0bac834d0639ee2874e8905a36f2f.png","width":162},"{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":9920249,\"dm_type\":1,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"流汗娅\",\"user_hash\":\"318242671\",\"emoticon_unique\":\"room_5561470_15046\",\"bulge_display\":1,\"recommend_score\":0,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"03eecb79830727ed026188ec7467e6d45077\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i2.hdslb.com/bfs/face/159205500e12ba7cc4b29aafdece589bd2d14009.webp","is_mystery":false,"name":"糖糕豆奶蛋包卷","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/159205500e12ba7cc4b29aafdece589bd2d14009.webp","name":"糖糕豆奶蛋包卷"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":398668,"color_border":398668,"color_end":6850801,"color_start":398668,"guard_icon":"","guard_level":0,"honor_icon":"","id":467746,"is_light":1,"level":26,"name":"iMia","ruid":780791,"score":50031990,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":1116027827}},{"activity_identity":"","activity_source":0,"not_show":0},0],"流汗娅",[1116027827,"糖糕豆奶蛋包卷",0,0,0,10000,1,""],[26,"iMia","Mia米娅-",5561470,398668,"",0,398668,398668,6850801,0,1,780791],[15,0,6406234,">50000",0],["",""],0,0,null,{"ct":"2C48B3EA","ts":1743180859},0,0,null,null,0,330,[28],null]}</pre>
 *     </li>
 *     <li>
 *         <p>无大航海</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,5566168,1743257366952,1743255186,0,"a025ca77",0,0,0,"",1,{"bulge_display":1,"emoticon_unique":"room_5561470_71409","height":162,"in_player_area":1,"is_dynamic":1,"url":"https://i0.hdslb.com/bfs/garb/4ba95f36ae0b653e3adef6a12b29379d97dca254.png","width":162},"{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":5566168,\"dm_type\":1,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"打call\",\"user_hash\":\"2686831223\",\"emoticon_unique\":\"room_5561470_71409\",\"bulge_display\":1,\"recommend_score\":0,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"7110258a8d35d8575bd585970a67e7ff8811\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","is_mystery":false,"name":"冷月丶残星丶","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/b7ba7ac8ece70c638f9c11ea7df2c3d48023609b.jpg","name":"冷月丶残星丶"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":12478086,"color_border":12478086,"color_end":12478086,"color_start":12478086,"guard_icon":"","guard_level":0,"honor_icon":"","id":467746,"is_light":1,"level":14,"name":"iMia","ruid":780791,"score":58255,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#C85DC499","v2_medal_color_end":"#C85DC499","v2_medal_color_level":"#59005699","v2_medal_color_start":"#C85DC499","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":180864557}},{"activity_identity":"","activity_source":0,"not_show":0},0],"打call",[180864557,"冷月丶残星丶",0,0,0,10000,1,""],[14,"iMia","Mia米娅-",5561470,12478086,"",0,12478086,12478086,12478086,0,1,780791],[16,0,6406234,">50000",0],["",""],0,0,null,{"ct":"CF68E99F","ts":1743257366},0,0,null,null,0,363,[24],null]}</pre>
 *     </li>
 *     <li>
 *         <p>有大航海</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,4,25,14893055,1743257376434,-1549892728,0,"e35ddc4e",0,0,5,"#1453BAFF,#4C2263A2,#3353BAFF",1,{"bulge_display":1,"emoticon_unique":"room_5561470_15525","height":162,"in_player_area":1,"is_dynamic":0,"url":"http://i0.hdslb.com/bfs/live/a1e90660eab38978700ad68ed6acda04c2026c97.png","width":162},"{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":14893055,\"dm_type\":1,\"font_size\":25,\"player_mode\":4,\"show_player_type\":0,\"content\":\"打call娅\",\"user_hash\":\"3814579278\",\"emoticon_unique\":\"room_5561470_15525\",\"bulge_display\":1,\"recommend_score\":0,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"2862ff002f88adfe52b97dfef867e7ff6980\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i2.hdslb.com/bfs/face/4c01a18f8f554e2fb8142c3f8ae268648b00a908.jpg","is_mystery":false,"name":"白夜yukira","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/4c01a18f8f554e2fb8142c3f8ae268648b00a908.jpg","name":"白夜yukira"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":398668,"color_border":6809855,"color_end":6850801,"color_start":398668,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":467746,"is_light":1,"level":27,"name":"iMia","ruid":780791,"score":50097414,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":779351}},{"activity_identity":"","activity_source":0,"not_show":0},43],"打call娅",[779351,"白夜yukira",1,0,0,10000,1,"#00D1F1"],[27,"iMia","Mia米娅-",5561470,398668,"",0,6809855,398668,6850801,3,1,780791],[50,0,16746162,7994,0],["",""],0,3,null,{"ct":"4C8A6DB7","ts":1743257376},0,0,null,null,0,748,[33],null]}</pre>
 *     </li>
 * </ol>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEmojiEvent extends EmojiEvent {
    public BilibiliEmojiEvent(LiveStreamerInfo source, UserInfo sender, BilibiliEmojiInfo emoji) {
        super(Platform.BILIBILI, source, sender, emoji);
    }

    public BilibiliEmojiEvent(LiveStreamerInfo source, UserInfo sender, BilibiliEmojiInfo emoji, Instant instant) {
        super(Platform.BILIBILI, source, sender, emoji, instant);
    }
}
