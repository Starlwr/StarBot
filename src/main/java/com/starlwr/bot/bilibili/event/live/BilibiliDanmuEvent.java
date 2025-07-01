package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.DanmuEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <h3>Bilibili 弹幕事件</h3>
 * <h4>触发条件：</h4>
 *     <ul>
 *         <li>DANMU_MSG（收到弹幕）</li>
 *     </ul>
 * <h4>示例：</h4>
 * <ol>
 *     <li>
 *         <p>无粉丝勋章</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,4,25,14893055,1743186488891,1743184716,0,"8c1850a6",0,0,0,"",0,"{}","{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":14893055,\"dm_type\":0,\"font_size\":25,\"player_mode\":4,\"show_player_type\":0,\"content\":\"结婚考虑的现实因素更多\",\"user_hash\":\"2350403750\",\"emoticon_unique\":\"\",\"bulge_display\":0,\"recommend_score\":1,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"6a6a6b38920b23116f8ee8706767e6ea4351\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i0.hdslb.com/bfs/face/b2f39c031e307e8d4b468d6617084e0479508e17.jpg","is_mystery":false,"name":"冰绒のReisen-IAだけ","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/b2f39c031e307e8d4b468d6617084e0479508e17.jpg","name":"冰绒のReisen-IAだけ"}},"guard_leader":{"is_guard_leader":false},"title":{"old_title_css_id":"title-111-1","title_css_id":"title-111-1"},"uid":130728}},{"activity_identity":"","activity_source":0,"not_show":0},0],"结婚考虑的现实因素更多",[130728,"冰绒のReisen-IAだけ",0,0,0,10000,1,"#00D1F1"],[],[21,0,5805790,">50000",0],["title-111-1","title-111-1"],0,3,null,{"ct":"DEC0B20A","ts":1743186488},0,0,null,null,0,884,[33],null]}</pre>
 *     </li>
 *     <li>
 *         <p>有粉丝勋章</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,5566168,1743186597724,425932214,0,"c1bf6338",0,0,0,"",0,"{}","{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":5566168,\"dm_type\":0,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"还不睡吗\",\"user_hash\":\"3250545464\",\"emoticon_unique\":\"\",\"bulge_display\":0,\"recommend_score\":2,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"068f8aa8c817677f44bf2f4eff67e6ea4262\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i2.hdslb.com/bfs/face/7e72c58637ff26df68fb30939de078d2bbbfcdbe.jpg","is_mystery":false,"name":"七水合硫酸镁","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i2.hdslb.com/bfs/face/7e72c58637ff26df68fb30939de078d2bbbfcdbe.jpg","name":"七水合硫酸镁"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":9272486,"color_border":9272486,"color_end":9272486,"color_start":9272486,"guard_icon":"","guard_level":0,"honor_icon":"","id":418510,"is_light":1,"level":12,"name":"希多士","ruid":591892279,"score":26271,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#596FE099","v2_medal_color_end":"#596FE099","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#596FE099","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":413340509}},{"activity_identity":"","activity_source":0,"not_show":0},0],"还不睡吗",[413340509,"七水合硫酸镁",0,0,0,10000,1,""],[12,"希多士","希月萌奈",22889484,9272486,"",0,9272486,9272486,9272486,0,1,591892279],[9,0,9868950,">50000",0],["",""],0,0,null,{"ct":"5513C433","ts":1743186597},0,0,null,null,0,104,[9],null]}</pre>
 *     </li>
 *     <li>
 *         <p>无大航海</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,16777215,1743255641206,1743254351,0,"d61026dc",0,0,0,"",0,"{}","{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":16777215,\"dm_type\":0,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"我来帮米娅做\",\"user_hash\":\"3591382748\",\"emoticon_unique\":\"\",\"bulge_display\":0,\"recommend_score\":3,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"4190d7694f34d484416d3bc43867e7f86582\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i0.hdslb.com/bfs/face/522f12bea3ae319f38760e9fa0e94573f16be20f.jpg","is_mystery":false,"name":"主包少女重度依赖","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/522f12bea3ae319f38760e9fa0e94573f16be20f.jpg","name":"主包少女重度依赖"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":398668,"color_border":398668,"color_end":6850801,"color_start":398668,"guard_icon":"","guard_level":0,"honor_icon":"","id":467746,"is_light":1,"level":26,"name":"iMia","ruid":780791,"score":50053258,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":324230571}},{"activity_identity":"","activity_source":0,"not_show":0},0],"我来帮米娅做",[324230571,"主包少女重度依赖",0,0,0,10000,1,""],[26,"iMia","Mia米娅-",5561470,398668,"",0,398668,398668,6850801,0,1,780791],[19,0,6406234,">50000",0],["",""],0,0,null,{"ct":"49CF1C99","ts":1743255641},0,0,null,null,0,520,[31],null]}</pre>
 *     </li>
 *     <li>
 *         <p>有大航海</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,4,25,14893055,1743255627732,1743255579,0,"0e1afbed",0,0,5,"#1453BAFF,#4C2263A2,#3353BAFF",0,"{}","{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":14893055,\"dm_type\":0,\"font_size\":25,\"player_mode\":4,\"show_player_type\":0,\"content\":\"晚上坏\",\"user_hash\":\"236649453\",\"emoticon_unique\":\"\",\"bulge_display\":0,\"recommend_score\":7,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"091d66c8bef2d09d0663a103c967e7f8171\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"http://i2.hdslb.com/bfs/face/7e83939563f4e842f0ee93e3fc022d2741f63394.jpg","is_mystery":false,"name":"神の騎士","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"http://i2.hdslb.com/bfs/face/7e83939563f4e842f0ee93e3fc022d2741f63394.jpg","name":"神の騎士"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":1725515,"color_border":6809855,"color_end":5414290,"color_start":1725515,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":467746,"is_light":1,"level":24,"name":"iMia","ruid":780791,"score":50011044,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#5FC7F4FF","v2_medal_color_end":"#43B3E3CC","v2_medal_color_level":"#00308C99","v2_medal_color_start":"#43B3E3CC","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":1603898}},{"activity_identity":"","activity_source":0,"not_show":0},43],"晚上坏",[1603898,"神の騎士",0,0,0,10000,1,"#00D1F1"],[24,"iMia","Mia米娅-",5561470,1725515,"",0,6809855,1725515,5414290,3,1,780791],[20,0,6406234,">50000",0],["",""],0,3,null,{"ct":"4C2F9271","ts":1743255627},0,0,null,null,0,663,[27],null]}</pre>
 *     </li>
 *     <li>
 *         <p>含表情</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,5566168,1743186718656,-995131711,0,"d1ce32d3",0,0,0,"",0,"{}","{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":5566168,\"dm_type\":0,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"开除鲸籍[dog]\",\"user_hash\":\"3519951571\",\"emoticon_unique\":\"\",\"bulge_display\":0,\"recommend_score\":8,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":{\"[dog]\":{\"count\":1,\"descript\":\"[dog]\",\"emoji\":\"[dog]\",\"emoticon_id\":208,\"emoticon_unique\":\"emoji_208\",\"height\":20,\"url\":\"http://i0.hdslb.com/bfs/live/4428c84e694fbf4e0ef6c06e958d9352c3582740.png\",\"width\":20}},\"is_audited\":false,\"id_str\":\"0b41f6adaab21e97271beeb59267e6eb874\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i1.hdslb.com/bfs/face/80b02148a583b6c119c2904b8f7baa3a4b17b4d0.jpg","is_mystery":false,"name":"铁心机仆Fred","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i1.hdslb.com/bfs/face/80b02148a583b6c119c2904b8f7baa3a4b17b4d0.jpg","name":"铁心机仆Fred"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":9272486,"color_border":9272486,"color_end":9272486,"color_start":9272486,"guard_icon":"","guard_level":0,"honor_icon":"","id":418510,"is_light":1,"level":11,"name":"希多士","ruid":591892279,"score":18400,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#596FE099","v2_medal_color_end":"#596FE099","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#596FE099","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":455420449}},{"activity_identity":"","activity_source":0,"not_show":0},0],"开除鲸籍[dog]",[455420449,"铁心机仆Fred",0,0,0,10000,1,""],[11,"希多士","希月萌奈",22889484,9272486,"",0,9272486,9272486,9272486,0,1,591892279],[0,0,9868950,">50000",0],["",""],0,0,null,{"ct":"E4BB77A4","ts":1743186718},0,0,null,null,0,104,[6],null]}</pre>
 *     </li>
 *     <li>
 *         <p>含回复</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,4,25,14893055,1743180856166,1104485029,0,"ca719f66",0,0,5,"#1453BAFF,#4C2263A2,#3353BAFF",0,"{}","{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":14893055,\"dm_type\":0,\"font_size\":25,\"player_mode\":4,\"show_player_type\":0,\"content\":\"一样\",\"user_hash\":\"3396444006\",\"emoticon_unique\":\"\",\"bulge_display\":0,\"recommend_score\":0,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"53dfdecdc10163e85fcc3ee5c767e6d48391\",\"icon\":null,\"show_reply\":true,\"reply_mid\":1116027827,\"reply_uname\":\"糖糕豆奶蛋包卷\",\"reply_uname_color\":\"#FB7299\",\"reply_is_mystery\":false,\"reply_type_enum\":1,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"base":{"face":"https://i0.hdslb.com/bfs/face/8968ca89a6953b19c2cbf13d583900ace707b0a9.jpg","is_mystery":false,"name":"属米的小鼠鼠","name_color":0,"name_color_str":"","official_info":{"desc":"","role":0,"title":"","type":-1},"origin_info":{"face":"https://i0.hdslb.com/bfs/face/8968ca89a6953b19c2cbf13d583900ace707b0a9.jpg","name":"属米的小鼠鼠"}},"guard_leader":{"is_guard_leader":false},"medal":{"color":398668,"color_border":6809855,"color_end":6850801,"color_start":398668,"guard_icon":"https://i0.hdslb.com/bfs/live/143f5ec3003b4080d1b5f817a9efdca46d631945.png","guard_level":3,"honor_icon":"","id":467746,"is_light":1,"level":26,"name":"iMia","ruid":780791,"score":50035859,"typ":0,"user_receive_count":0,"v2_medal_color_border":"#58A1F8FF","v2_medal_color_end":"#4775EFCC","v2_medal_color_level":"#000B7099","v2_medal_color_start":"#4775EFCC","v2_medal_color_text":"#FFFFFFFF"},"title":{"old_title_css_id":"","title_css_id":""},"uid":3493142844148332}},{"activity_identity":"","activity_source":0,"not_show":0},43],"一样",[3493142844148332,"属米的小鼠鼠",0,0,0,10000,1,"#00D1F1"],[26,"iMia","Mia米娅-",5561470,398668,"",0,6809855,398668,6850801,3,1,780791],[19,0,6406234,">50000",0],["",""],0,3,null,{"ct":"41677036","ts":1743180856},0,0,null,null,0,612,[30],null]}</pre>
 *     </li>
 *     <li>
 *         <p>无发送用户详细信息</p>
 *         <pre>{"cmd":"DANMU_MSG","dm_v2":"","info":[[0,1,25,16777215,1751116494922,-1846969021,0,"8e37b001",0,0,0,"",1,{"bulge_display":1,"emoticon_unique":"upower_[舰长_挥手]","height":20,"in_player_area":1,"is_dynamic":0,"url":"http://i0.hdslb.com/bfs/emote/4d066e650678fd68ba402d61c0db16c2656f9ab1.png","width":20},"{}",{"extra":"{\"send_from_me\":false,\"master_player_hidden\":false,\"mode\":0,\"color\":16777215,\"dm_type\":1,\"font_size\":25,\"player_mode\":1,\"show_player_type\":0,\"content\":\"[舰长_挥手]\",\"user_hash\":\"2386014209\",\"emoticon_unique\":\"upower_[舰长_挥手]\",\"bulge_display\":1,\"recommend_score\":0,\"main_state_dm_color\":\"\",\"objective_state_dm_color\":\"\",\"direction\":0,\"pk_direction\":0,\"quartet_direction\":0,\"anniversary_crowd\":0,\"yeah_space_type\":\"\",\"yeah_space_url\":\"\",\"jump_to_url\":\"\",\"space_type\":\"\",\"space_url\":\"\",\"animation\":{},\"emots\":null,\"is_audited\":false,\"id_str\":\"4cb34f12a7677da80c4ec94a73685fea565\",\"icon\":null,\"show_reply\":true,\"reply_mid\":0,\"reply_uname\":\"\",\"reply_uname_color\":\"\",\"reply_is_mystery\":false,\"reply_type_enum\":0,\"hit_combo\":0,\"esports_jump_url\":\"\"}","mode":0,"show_player_type":0,"user":{"title":{"old_title_css_id":"","title_css_id":""},"uid":1566785056}},{"activity_identity":"","activity_source":0,"not_show":0},0],"[舰长_挥手]",[1566785056,"",0,0,0,10000,1,""],[],[10,0,9868950,">50000",0],["",""],0,0,null,{"ct":"826D078A","ts":1751116494},0,0,null,null,0,363,[23],null]}</pre>
 *     </li>
 * </ol>
*/
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliDanmuEvent extends DanmuEvent {
    /**
     * 回复的用户信息
     */
    private UserInfo reply;

    /**
     * 弹幕中包含的表情信息
     */
    private List<BilibiliEmojiInfo> emojis;

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content) {
        super(LivePlatform.BILIBILI, source, sender, content);
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, instant);
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content) {
        super(LivePlatform.BILIBILI, source, sender, content);
        this.reply = reply;
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, instant);
        this.reply = reply;
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText);
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText, instant);
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, String contentText) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText);
        this.reply = reply;
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, String contentText, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText, instant);
        this.reply = reply;
        this.emojis = new ArrayList<>();
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, List<BilibiliEmojiInfo> emojis) {
        super(LivePlatform.BILIBILI, source, sender, content);
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, List<BilibiliEmojiInfo> emojis, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, instant);
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, List<BilibiliEmojiInfo> emojis) {
        super(LivePlatform.BILIBILI, source, sender, content);
        this.reply = reply;
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, List<BilibiliEmojiInfo> emojis, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, instant);
        this.reply = reply;
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText, List<BilibiliEmojiInfo> emojis) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText);
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText, List<BilibiliEmojiInfo> emojis, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText, instant);
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, String contentText, List<BilibiliEmojiInfo> emojis) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText);
        this.reply = reply;
        this.emojis = emojis;
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, UserInfo reply, String content, String contentText, List<BilibiliEmojiInfo> emojis, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText, instant);
        this.reply = reply;
        this.emojis = emojis;
    }
}
