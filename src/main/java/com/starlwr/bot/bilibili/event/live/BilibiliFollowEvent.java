package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.FollowEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 关注事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>INTERACT_WORD_V2（进房、关注、分享）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"INTERACT_WORD_V2","data":{"pb":"CP36uwoSD+i9r+ezluWwj+m8oOmFsSIBASgCMPS/7Ao4x+/k1AZA4caHtIY0SgBiAHjdzKe+r4Tx6BiaAQCyAdMBCP36uwoSyQEKD+i9r+ezluWwj+m8oOmFsRJKaHR0cHM6Ly9pMS5oZHNsYi5jb20vYmZzL2ZhY2UvNDVlZjQ0MmFhOTYxNTk2NmFhNDg1Y2Q2N2ZhZTdiYjViMTA2MjYxMC5qcGcyXQoP6L2v57OW5bCP6byg6YWxEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80NWVmNDQyYWE5NjE1OTY2YWE0ODVjZDY3ZmFlN2JiNWIxMDYyNjEwLmpwZzoLIP///////////wEyALoBAA=="}}</pre>
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
