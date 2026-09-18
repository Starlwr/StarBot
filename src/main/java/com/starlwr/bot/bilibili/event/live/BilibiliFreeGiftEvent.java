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
 *     <li>SEND_GIFT_V2（投喂礼物）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"SEND_GIFT_V2","data":{"pb":"CLrAswQSBumlvOOCnBpKaHR0cHM6Ly9pMi5oZHNsYi5jb20vYmZzL2ZhY2UvZGEyNjlkNTVjYjNlMzJmMjc1NzJhYTkwOTI3OTA5MTM5NjU0M2ZhOS5qcGdCKAi6wLMEKB0yCeiFv+eOqeW5tDjVkLQBQNWQtAFI/7f2BFDVkLQBWAFS4AQI+vcBEg/nsonkuJ3lm6Lnga/niYwYASACKOgHOOgHQgZzaWx2ZXJKEzQ4MTMzOTIzNzY4MTcxNjc4NzJQqpzs1AZoCngFhQEAAIA/kgEG5oqV5ZaCmAEBwAHpjpwP6gETCgznmb3npLxCYWlsaWkQ5d/aDIoC3gEI5d/aDBLWAQoM55m956S8QmFpbGlpEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS84NjAzZWNjMzAyMzY2OGM1YzVkNTcwN2Q0MWFiZDc5ZjlkMjlmYWQ2LmpwZzJaCgznmb3npLxCYWlsaWkSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlLzg2MDNlY2MzMDIzNjY4YzVjNWQ1NzA3ZDQxYWJkNzlmOWQyOWZhZDYuanBnOh4IBxIaYmlsaWJpbGnnm7Tmkq3pq5jog73kuLvmkq2SAgcIjODXAhABmgLlAQpKaHR0cHM6Ly9zMS5oZHNsYi5jb20vYmZzL2xpdmUvZTA1MWRmZDQ1NTc2NzhmOGVkY2FjNDk5M2VkMDBhMDkzNWNiZDljYy5wbmcSS2h0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzMyYjc5OTEyMGUxNjE0ZmE2Mjc1YjZkMTVkYTdhNTJiMjFkZDAxOWQud2VicCpKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvODE2ZjhiN2FhMjEzMjg4OGZjZTkyOGNkZmIxN2I5Y2YyMWNjMDgyMy5naWaqAhQIARIHCPW38AIQAhIHCIzg1wIQAVgBagIIT3qxAgi6wLMEEsYBCgbppbzjgpwSSmh0dHBzOi8vaTIuaGRzbGIuY29tL2Jmcy9mYWNlL2RhMjY5ZDU1Y2IzZTMyZjI3NTcyYWE5MDkyNzkwOTEzOTY1NDNmYTkuanBnMlQKBumlvOOCnBJKaHR0cHM6Ly9pMi5oZHNsYi5jb20vYmZzL2ZhY2UvZGEyNjlkNTVjYjNlMzJmMjc1NzJhYTkwOTI3OTA5MTM5NjU0M2ZhOS5qcGc6GggHEhYyMDIy5bm05bqm5beF5bOw5Li75pKtGmEKCeeZveekvOeUnBAFGMCBgwYgwIGDBijAgYMGMJ739QJQ5d/aDGAfegkjOTE5Mjk4Q0OCAQkjOTE5Mjk4Q0OKAQkjOTE5Mjk4Q0OSAQcjRkZGRkZGmgEJIzkxOTI5OEU2"}}</pre>
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
