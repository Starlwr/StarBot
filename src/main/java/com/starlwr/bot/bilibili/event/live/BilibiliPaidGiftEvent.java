package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.PaidGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 付费礼物事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>SEND_GIFT_V2（投喂礼物）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"SEND_GIFT_V2","data":{"pb":"CNKUgLHHuqYGEgpDZXJpc2VsdW5lGkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZ0IAUpcFCLzzARIP57KJ5Lid5Zui54Gv54mMGAEgAShkMGQ4ZEIEZ29sZEoTNDgxMjg4ODc1MTcyNDMzODE3NlCg8uTUBlgBYkViYXRjaDpnaWZ0OmNvbWJvX2lkOjM1NDY4Mzc1MTQ0NTU2MzQ6MTg5MzA2MjkyMjozMTE2NDoxNzg4NDI2NTI4LjYwNTdoCnBkeAWFAQAAgD+IAQGSAQbmipXlloLAAa+JEuoBFAoM5Y+v5Y+v5bCP5a6FEIqy14YHigLMAQiKsteGBxLDAQoM5Y+v5Y+v5bCP5a6FEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS9lNDRjYjIwMmJhZTdmNGU0ODhhNGRmNjdiMTQ2OTZmY2IyNDc0YzAyLmpwZzJaCgzlj6/lj6/lsI/lroUSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlL2U0NGNiMjAyYmFlN2Y0ZTQ4OGE0ZGY2N2IxNDY5NmZjYjI0NzRjMDIuanBnOgsg////////////AZICBwiM4NcCEAGaAuUBCkpodHRwczovL3MxLmhkc2xiLmNvbS9iZnMvbGl2ZS9lMDUxZGZkNDU1NzY3OGY4ZWRjYWM0OTkzZWQwMGEwOTM1Y2JkOWNjLnBuZxJLaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvMzJiNzk5MTIwZTE2MTRmYTYyNzViNmQxNWRhN2E1MmIyMWRkMDE5ZC53ZWJwKkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvbGl2ZS84MTZmOGI3YWEyMTMyODg4ZmNlOTI4Y2RmYjE3YjljZjIxY2MwODIzLmdpZqoCFAgBEgcI9bfwAhACEgcIjODXAhABWAFqAgggessBCNKUgLHHuqYGEr8BCgpDZXJpc2VsdW5lEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZzJYCgpDZXJpc2VsdW5lEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZzoLIP///////////wE="}}</pre>
 * <h4>备注：</h4>
 * <p>主播开启优先展示本房间勋章时，原始数据中两个粉丝勋章信息不一致，应使用 sender_uinfo 中的信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliPaidGiftEvent extends PaidGiftEvent {
    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, instant);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, value);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, value, instant);
    }
}
