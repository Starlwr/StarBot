package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.RandomGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * <h3>Bilibili 盲盒礼物事件</h3>
 * <h4>触发条件：</h4>
 * <ul>
 *     <li>SEND_GIFT_V2（投喂礼物）</li>
 * </ul>
 * <h4>示例：</h4>
 * <pre>{"cmd":"SEND_GIFT_V2","data":{"pb":"CJL8mxcSFeeZveekvOeahOmtlOazleiDluasoRpKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2ZhY2UvZDExY2MzMWJiZjQxNjVkNjYxNDNkNTdiYjc1ZjdlYWFlNzU2ZDhmMy5qcGciByNFMTdBRkYoAkInCO7xiwsoKTIG5oOz5rOVOIvC/QdAi8L9B0iEof8HUP/RnwNYAWADSiEIoQEQhZUCGgznvoHnu4rlrp3nm5IqBueIhuWHujDogQJSgwUIiJUCEgznlJzonJzlpZHnuqYYBSACKLiRAjC4kQI4iIkKQgRnb2xkShM0ODEzMzc5NTA4NTI0Njk5MTM3UK6E7NQGWAFiJGVjMjM4ZGI4LTI0MGMtNGQ4MC1iMTAxLWNhZmNmNGNkMjMxNmgKcJjXCngIhQEAAIA/iAEBkgEG5oqV5ZaCqAECwAHmg5wP6gETCgznmb3npLxCYWlsaWkQ5d/aDIoC3gEI5d/aDBLWAQoM55m956S8QmFpbGlpEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS84NjAzZWNjMzAyMzY2OGM1YzVkNTcwN2Q0MWFiZDc5ZjlkMjlmYWQ2LmpwZzJaCgznmb3npLxCYWlsaWkSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlLzg2MDNlY2MzMDIzNjY4YzVjNWQ1NzA3ZDQxYWJkNzlmOWQyOWZhZDYuanBnOh4IBxIaYmlsaWJpbGnnm7Tmkq3pq5jog73kuLvmkq2SAgIQApoC6gEKSmh0dHBzOi8vczEuaGRzbGIuY29tL2Jmcy9saXZlLzRiMzc5NmIyNjc2YWFmMmJjZDQzMjgzNzEwZDEzZTNiYjY2MzllNDIucG5nEktodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvbGl2ZS8yNGYzY2ZjODliZTU2M2U1ZGMyMWI5YjkxYjc5NTM3MmNjZTc3ZmQwLndlYnAY7ikgASpKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvNTlkMjIxZDc3Yjc3YjNjNDZjOWMwMmRmYzhkNDcxNDcxNDhjNWJlMS5naWagAriRAqoCAggCUoMFCIeVAhIM5pif5YWJ54K554K5GAUgAiignAEwoJwBOIiJCkIEZ29sZEoTNDgxMzM3OTUwODUyNDY5OTEzNlCuhOzUBlgBYiRmZWM3NGM5My03MGQwLTQxYzItODU0Ni1iMTFlNTJiZDM1MWVoCnCgjQZ4BYUBAACAP4gBAZIBBuaKleWWgqgBAsAB+P2bD+oBEwoM55m956S8QmFpbGlpEOXf2gyKAt4BCOXf2gwS1gEKDOeZveekvEJhaWxpaRJKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2ZhY2UvODYwM2VjYzMwMjM2NjhjNWM1ZDU3MDdkNDFhYmQ3OWY5ZDI5ZmFkNi5qcGcyWgoM55m956S8QmFpbGlpEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS84NjAzZWNjMzAyMzY2OGM1YzVkNTcwN2Q0MWFiZDc5ZjlkMjlmYWQ2LmpwZzoeCAcSGmJpbGliaWxp55u05pKt6auY6IO95Li75pKtkgICEAKaAuoBCkpodHRwczovL3MxLmhkc2xiLmNvbS9iZnMvbGl2ZS85MzRiOTYxMThkNzAzZjc5NTQ2NzYyMDA3MTEwODMwOGRhNjM3MTJhLnBuZxJLaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvMzI2NDFmNTBjMjdiYmNiYmY3MDM0ZTg2NjY4NDJjODdlZWE1MWYzZS53ZWJwGO0pIAEqSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzZiZjQxMGNlYzNhZmE5MGFmNDlhOTRkYzA4ZjM0MzQyNjE1NWIwZjQuZ2lmoAKgnAGqAgIIAlgBagIIPHqSAwiS/JsXEtUBChXnmb3npLznmoTprZTms5Xog5bmrKESSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlL2QxMWNjMzFiYmY0MTY1ZDY2MTQzZDU3YmI3NWY3ZWFhZTc1NmQ4ZjMuanBnMmMKFeeZveekvOeahOmtlOazleiDluasoRJKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2ZhY2UvZDExY2MzMWJiZjQxNjVkNjYxNDNkNTdiYjc1ZjdlYWFlNzU2ZDhmMy5qcGc6CyD///////////8BGrIBCgnnmb3npLznlJwQOxiLwv0HIISh/wco1ND/BzCLwv0HSAFQ5d/aDFgCYNmd9whqSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzYyYWMwNmZkNzJiMDVmZTIyYmUyNjQyNmI5ZTFhOGUxZmMyYzZiODkucG5negkjRUM0RjZFOTmCAQkjRUM0RjZFOTmKAQcjRjE4MDg3kgEHI0ZGRkZGRpoBCSNFQzRGNkVFNg=="}}</pre>
 * <h4>备注：</h4>
 * <p>主播开启优先展示本房间勋章时，原始数据中两个粉丝勋章信息不一致，应使用 sender_uinfo 中的信息</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRandomGiftEvent extends RandomGiftEvent {
    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, instant);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value, instant);
    }
}
