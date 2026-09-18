package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.ShareEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 分享事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>INTERACT_WORD_V2（进房、关注、分享）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"INTERACT_WORD_V2","data":{"pb":"CJ3N6wwSCW1pa3VmaWxjayICAwEoAzDwxdkOOLL/484GQKHWmMPXM0oxCOeVgKuwraYGEBUaCeWBmueMq+eahCDLqGkoy6hpMJK7ygI4y6hpQAFg8MXZDmjsE2IAeKbf5aPU38DSGJoBALIBrgIInc3rDBK9AQoJbWlrdWZpbGNrEkpodHRwczovL2kyLmhkc2xiLmNvbS9iZnMvZmFjZS82YTBkZDM2YmE3ZmExOGU4NGM2NTI3Yzg3YmViYTAyYTVkMmRlM2Y5LmpwZzJXCgltaWt1ZmlsY2sSSmh0dHBzOi8vaTIuaGRzbGIuY29tL2Jmcy9mYWNlLzZhMGRkMzZiYTdmYTE4ZTg0YzY1MjdjODdiZWJhMDJhNWQyZGUzZjkuanBnOgsg////////////ARplCgnlgZrnjKvnmoQQFRjLqGkgkrvKAijLqGkwy6hpSAFQ55WAq7CtpgZg7BN6CSMzRkI0RjY5OYIBCSMzRkI0RjY5OYoBCSMzRkI0RjY5OZIBByNGRkZGRkaaAQkjM0ZCNEY2RTYyALoBAA=="}}</pre>
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
