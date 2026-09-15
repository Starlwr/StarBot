package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import com.google.protobuf.CodedOutputStream;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardType;
import com.starlwr.bot.bilibili.event.live.BilibiliEnterRoomEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliFollowEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliFreeGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliPaidGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliShareEvent;
import com.starlwr.bot.bilibili.model.BilibiliUserInfo;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Bilibili 直播事件解析器测试
 * <p>
 * 通过手动构造 INTERACT_WORD_V2 与 SEND_GIFT_V2 protobuf 数据验证字段解析与事件转换，
 * 同时使用真实数据覆盖进场、关注、分享、付费礼物、免费礼物和一条消息包含多个盲盒结果的场景。
 * BilibiliApiUtil 与 BilibiliGiftService 使用 Mockito mock，避免测试依赖网络请求
 */
class BilibiliEventParserTest {
    /**
     * 真实进场数据，来自 INTERACT_WORD_V2.log
     */
    private static final String REAL_INTERACT_ENTER = "CNXghQYSCVBORVVNQTM3MyIBASgBMPS/7Ao4zu3k1AZA6q34s4Y0YgB4h+WknpP98OgYmgEAsgFkCNXghQYSVwoJUE5FVU1BMzczEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS80NjJlZTMzYjFmYzVmOWE1NTEyYmQ0NzRlODg1NTczYWRmN2E5NzE2LmpwZyICCAoyALoBAMIBAA==";

    /**
     * 真实关注数据，来自 INTERACT_WORD_V2.log
     */
    private static final String REAL_INTERACT_FOLLOW = "CP36uwoSD+i9r+ezluWwj+m8oOmFsSIBASgCMPS/7Ao4x+/k1AZA4caHtIY0SgBiAHjdzKe+r4Tx6BiaAQCyAdMBCP36uwoSyQEKD+i9r+ezluWwj+m8oOmFsRJKaHR0cHM6Ly9pMS5oZHNsYi5jb20vYmZzL2ZhY2UvNDVlZjQ0MmFhOTYxNTk2NmFhNDg1Y2Q2N2ZhZTdiYjViMTA2MjYxMC5qcGcyXQoP6L2v57OW5bCP6byg6YWxEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80NWVmNDQyYWE5NjE1OTY2YWE0ODVjZDY3ZmFlN2JiNWIxMDYyNjEwLmpwZzoLIP///////////wEyALoBAA==";

    /**
     * 真实分享数据，来自用户提供的 INTERACT_WORD_V2 数据
     */
    private static final String REAL_INTERACT_SHARE = "CJ3N6wwSCW1pa3VmaWxjayICAwEoAzDwxdkOOLL/484GQKHWmMPXM0oxCOeVgKuwraYGEBUaCeWBmueMq+eahCDLqGkoy6hpMJK7ygI4y6hpQAFg8MXZDmjsE2IAeKbf5aPU38DSGJoBALIBrgIInc3rDBK9AQoJbWlrdWZpbGNrEkpodHRwczovL2kyLmhkc2xiLmNvbS9iZnMvZmFjZS82YTBkZDM2YmE3ZmExOGU4NGM2NTI3Yzg3YmViYTAyYTVkMmRlM2Y5LmpwZzJXCgltaWt1ZmlsY2sSSmh0dHBzOi8vaTIuaGRzbGIuY29tL2Jmcy9mYWNlLzZhMGRkMzZiYTdmYTE4ZTg0YzY1MjdjODdiZWJhMDJhNWQyZGUzZjkuanBnOgsg////////////ARplCgnlgZrnjKvnmoQQFRjLqGkgkrvKAijLqGkwy6hpSAFQ55WAq7CtpgZg7BN6CSMzRkI0RjY5OYIBCSMzRkI0RjY5OYoBCSMzRkI0RjY5OZIBByNGRkZGRkaaAQkjM0ZCNEY2RTYyALoBAA==";

    /**
     * 真实付费礼物数据
     */
    private static final String REAL_PAID_GIFT = "CNKUgLHHuqYGEgpDZXJpc2VsdW5lGkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZ0IAUpcFCLzzARIP57KJ5Lid5Zui54Gv54mMGAEgAShkMGQ4ZEIEZ29sZEoTNDgxMjg4ODc1MTcyNDMzODE3NlCg8uTUBlgBYkViYXRjaDpnaWZ0OmNvbWJvX2lkOjM1NDY4Mzc1MTQ0NTU2MzQ6MTg5MzA2MjkyMjozMTE2NDoxNzg4NDI2NTI4LjYwNTdoCnBkeAWFAQAAgD+IAQGSAQbmipXlloLAAa+JEuoBFAoM5Y+v5Y+v5bCP5a6FEIqy14YHigLMAQiKsteGBxLDAQoM5Y+v5Y+v5bCP5a6FEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS9lNDRjYjIwMmJhZTdmNGU0ODhhNGRmNjdiMTQ2OTZmY2IyNDc0YzAyLmpwZzJaCgzlj6/lj6/lsI/lroUSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlL2U0NGNiMjAyYmFlN2Y0ZTQ4OGE0ZGY2N2IxNDY5NmZjYjI0NzRjMDIuanBnOgsg////////////AZICBwiM4NcCEAGaAuUBCkpodHRwczovL3MxLmhkc2xiLmNvbS9iZnMvbGl2ZS9lMDUxZGZkNDU1NzY3OGY4ZWRjYWM0OTkzZWQwMGEwOTM1Y2JkOWNjLnBuZxJLaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvMzJiNzk5MTIwZTE2MTRmYTYyNzViNmQxNWRhN2E1MmIyMWRkMDE5ZC53ZWJwKkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvbGl2ZS84MTZmOGI3YWEyMTMyODg4ZmNlOTI4Y2RmYjE3YjljZjIxY2MwODIzLmdpZqoCFAgBEgcI9bfwAhACEgcIjODXAhABWAFqAgggessBCNKUgLHHuqYGEr8BCgpDZXJpc2VsdW5lEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZzJYCgpDZXJpc2VsdW5lEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZzoLIP///////////wE=";

    /**
     * 真实免费礼物数据
     */
    private static final String REAL_FREE_GIFT = "CLrAswQSBumlvOOCnBpKaHR0cHM6Ly9pMi5oZHNsYi5jb20vYmZzL2ZhY2UvZGEyNjlkNTVjYjNlMzJmMjc1NzJhYTkwOTI3OTA5MTM5NjU0M2ZhOS5qcGdCKAi6wLMEKB0yCeiFv+eOqeW5tDjVkLQBQNWQtAFI/7f2BFDVkLQBWAFS4AQI+vcBEg/nsonkuJ3lm6Lnga/niYwYASACKOgHOOgHQgZzaWx2ZXJKEzQ4MTMzOTIzNzY4MTcxNjc4NzJQqpzs1AZoCngFhQEAAIA/kgEG5oqV5ZaCmAEBwAHpjpwP6gETCgznmb3npLxCYWlsaWkQ5d/aDIoC3gEI5d/aDBLWAQoM55m956S8QmFpbGlpEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS84NjAzZWNjMzAyMzY2OGM1YzVkNTcwN2Q0MWFiZDc5ZjlkMjlmYWQ2LmpwZzJaCgznmb3npLxCYWlsaWkSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlLzg2MDNlY2MzMDIzNjY4YzVjNWQ1NzA3ZDQxYWJkNzlmOWQyOWZhZDYuanBnOh4IBxIaYmlsaWJpbGnnm7Tmkq3pq5jog73kuLvmkq2SAgcIjODXAhABmgLlAQpKaHR0cHM6Ly9zMS5oZHNsYi5jb20vYmZzL2xpdmUvZTA1MWRmZDQ1NTc2NzhmOGVkY2FjNDk5M2VkMDBhMDkzNWNiZDljYy5wbmcSS2h0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzMyYjc5OTEyMGUxNjE0ZmE2Mjc1YjZkMTVkYTdhNTJiMjFkZDAxOWQud2VicCpKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvODE2ZjhiN2FhMjEzMjg4OGZjZTkyOGNkZmIxN2I5Y2YyMWNjMDgyMy5naWaqAhQIARIHCPW38AIQAhIHCIzg1wIQAVgBagIIT3qxAgi6wLMEEsYBCgbppbzjgpwSSmh0dHBzOi8vaTIuaGRzbGIuY29tL2Jmcy9mYWNlL2RhMjY5ZDU1Y2IzZTMyZjI3NTcyYWE5MDkyNzkwOTEzOTY1NDNmYTkuanBnMlQKBumlvOOCnBJKaHR0cHM6Ly9pMi5oZHNsYi5jb20vYmZzL2ZhY2UvZGEyNjlkNTVjYjNlMzJmMjc1NzJhYTkwOTI3OTA5MTM5NjU0M2ZhOS5qcGc6GggHEhYyMDIy5bm05bqm5beF5bOw5Li75pKtGmEKCeeZveekvOeUnBAFGMCBgwYgwIGDBijAgYMGMJ739QJQ5d/aDGAfegkjOTE5Mjk4Q0OCAQkjOTE5Mjk4Q0OKAQkjOTE5Mjk4Q0OSAQcjRkZGRkZGmgEJIzkxOTI5OEU2";

    /**
     * 真实盲盒礼物数据
     */
    private static final String REAL_MULTIPLE_BLIND_GIFTS = "CJL8mxcSFeeZveekvOeahOmtlOazleiDluasoRpKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2ZhY2UvZDExY2MzMWJiZjQxNjVkNjYxNDNkNTdiYjc1ZjdlYWFlNzU2ZDhmMy5qcGciByNFMTdBRkYoAkInCO7xiwsoKTIG5oOz5rOVOIvC/QdAi8L9B0iEof8HUP/RnwNYAWADSiEIoQEQhZUCGgznvoHnu4rlrp3nm5IqBueIhuWHujDogQJSgwUIiJUCEgznlJzonJzlpZHnuqYYBSACKLiRAjC4kQI4iIkKQgRnb2xkShM0ODEzMzc5NTA4NTI0Njk5MTM3UK6E7NQGWAFiJGVjMjM4ZGI4LTI0MGMtNGQ4MC1iMTAxLWNhZmNmNGNkMjMxNmgKcJjXCngIhQEAAIA/iAEBkgEG5oqV5ZaCqAECwAHmg5wP6gETCgznmb3npLxCYWlsaWkQ5d/aDIoC3gEI5d/aDBLWAQoM55m956S8QmFpbGlpEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS84NjAzZWNjMzAyMzY2OGM1YzVkNTcwN2Q0MWFiZDc5ZjlkMjlmYWQ2LmpwZzJaCgznmb3npLxCYWlsaWkSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlLzg2MDNlY2MzMDIzNjY4YzVjNWQ1NzA3ZDQxYWJkNzlmOWQyOWZhZDYuanBnOh4IBxIaYmlsaWJpbGnnm7Tmkq3pq5jog73kuLvmkq2SAgIQApoC6gEKSmh0dHBzOi8vczEuaGRzbGIuY29tL2Jmcy9saXZlLzRiMzc5NmIyNjc2YWFmMmJjZDQzMjgzNzEwZDEzZTNiYjY2MzllNDIucG5nEktodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvbGl2ZS8yNGYzY2ZjODliZTU2M2U1ZGMyMWI5YjkxYjc5NTM3MmNjZTc3ZmQwLndlYnAY7ikgASpKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvNTlkMjIxZDc3Yjc3YjNjNDZjOWMwMmRmYzhkNDcxNDcxNDhjNWJlMS5naWagAriRAqoCAggCUoMFCIeVAhIM5pif5YWJ54K554K5GAUgAiignAEwoJwBOIiJCkIEZ29sZEoTNDgxMzM3OTUwODUyNDY5OTEzNlCuhOzUBlgBYiRmZWM3NGM5My03MGQwLTQxYzItODU0Ni1iMTFlNTJiZDM1MWVoCnCgjQZ4BYUBAACAP4gBAZIBBuaKleWWgqgBAsAB+P2bD+oBEwoM55m956S8QmFpbGlpEOXf2gyKAt4BCOXf2gwS1gEKDOeZveekvEJhaWxpaRJKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2ZhY2UvODYwM2VjYzMwMjM2NjhjNWM1ZDU3MDdkNDFhYmQ3OWY5ZDI5ZmFkNi5qcGcyWgoM55m956S8QmFpbGlpEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS84NjAzZWNjMzAyMzY2OGM1YzVkNTcwN2Q0MWFiZDc5ZjlkMjlmYWQ2LmpwZzoeCAcSGmJpbGliaWxp55u05pKt6auY6IO95Li75pKtkgICEAKaAuoBCkpodHRwczovL3MxLmhkc2xiLmNvbS9iZnMvbGl2ZS85MzRiOTYxMThkNzAzZjc5NTQ2NzYyMDA3MTEwODMwOGRhNjM3MTJhLnBuZxJLaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvMzI2NDFmNTBjMjdiYmNiYmY3MDM0ZTg2NjY4NDJjODdlZWE1MWYzZS53ZWJwGO0pIAEqSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzZiZjQxMGNlYzNhZmE5MGFmNDlhOTRkYzA4ZjM0MzQyNjE1NWIwZjQuZ2lmoAKgnAGqAgIIAlgBagIIPHqSAwiS/JsXEtUBChXnmb3npLznmoTprZTms5Xog5bmrKESSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlL2QxMWNjMzFiYmY0MTY1ZDY2MTQzZDU3YmI3NWY3ZWFhZTc1NmQ4ZjMuanBnMmMKFeeZveekvOeahOmtlOazleiDluasoRJKaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2ZhY2UvZDExY2MzMWJiZjQxNjVkNjYxNDNkNTdiYjc1ZjdlYWFlNzU2ZDhmMy5qcGc6CyD///////////8BGrIBCgnnmb3npLznlJwQOxiLwv0HIISh/wco1ND/BzCLwv0HSAFQ5d/aDFgCYNmd9whqSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9saXZlLzYyYWMwNmZkNzJiMDVmZTIyYmUyNjQyNmI5ZTFhOGUxZmMyYzZiODkucG5negkjRUM0RjZFOTmCAQkjRUM0RjZFOTmKAQcjRjE4MDg3kgEHI0ZGRkZGRpoBCSNFQzRGNkVFNg==";

    private BilibiliEventParser parser;

    private LiveStreamerInfo source;

    /**
     * 初始化无网络依赖的事件解析环境，默认关闭事件信息自动补全
     */
    @BeforeEach
    void setUp() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getLive().setCompleteEvent(false);
        parser = new BilibiliEventParser(properties, mock(BilibiliApiUtil.class), mock(BilibiliGiftService.class));
        source = new LiveStreamerInfo(1000L, "主播", 2000L);
    }

    /**
     * 测试直接解析手动构造的进场 protobuf 数据
     * <p>
     * 覆盖用户、推广来源、粉丝勋章自动补全、大航海、荣耀等级、时间戳及未知字段跳过。
     */
    @Test
    void parsesInteractWordV2FromDirectPbField() throws IOException {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getLive().setCompleteEvent(true);
        parser = new BilibiliEventParser(properties, mock(BilibiliApiUtil.class), mock(BilibiliGiftService.class));

        List<StarBotBaseLiveEvent> events = parser.parseEvents(
                command("INTERACT_WORD_V2", interaction(1, true, "流量包推广")), source);

        assertEquals(1, events.size());
        BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, events.get(0));
        assertTrue(event.isFromPromotion());
        assertEquals("流量包推广", event.getPromotionSource());
        assertEquals(1_760_100_000_000L, event.getTimestamp());

        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(42L, sender.getUid());
        assertEquals("操作用户", sender.getUname());
        assertEquals("https://example.com/face.png", sender.getFace());
        assertEquals(GuardType.Captain, sender.getGuard().getGuardType());
        assertNull(sender.getGuard().getIcon());
        assertEquals(1000L, sender.getFansMedal().getUid());
        assertEquals("主播", sender.getFansMedal().getUname());
        assertEquals(2000L, sender.getFansMedal().getRoomId());
        assertEquals("星星", sender.getFansMedal().getName());
        assertEquals(12, sender.getFansMedal().getLevel());
        assertTrue(sender.getFansMedal().getIsLighted());
        assertEquals(42, sender.getHonorLevel());
    }

    /**
     * 测试直接解析手动构造的付费礼物 protobuf 数据
     * <p>
     * 覆盖送礼用户、荣耀等级、粉丝勋章自动补全、大航海图标与礼物基础信息。
     */
    @Test
    void parsesPaidGiftFromDirectPbField() throws IOException {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getLive().setCompleteEvent(true);
        parser = new BilibiliEventParser(properties, mock(BilibiliApiUtil.class), mock(BilibiliGiftService.class));

        byte[] senderMedal = message(output -> {
            output.writeString(1, "星星");
            output.writeUInt32(2, 12);
            output.writeBool(9, true);
            output.writeUInt64(10, 1000L);
            output.writeString(13, "https://example.com/guard.png");
        });
        byte[] gift = gift(31036L, "小花花", 2, 100L, 100L, 200L,
                "gold", 1_760_000_000L, "https://example.com/gift.png");
        byte[] payload = broadcast(senderMedal, null, List.of(gift));

        List<StarBotBaseLiveEvent> events = parser.parseEvents(command(payload), source);

        assertEquals(1, events.size());
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class, events.get(0));
        assertEquals(0.2, event.getValue());
        assertEquals(31036L, event.getGiftInfo().getId());
        assertEquals("小花花", event.getGiftInfo().getName());
        assertEquals(0.1, event.getGiftInfo().getPrice());
        assertEquals(2, event.getGiftInfo().getCount());
        assertEquals("https://example.com/gift.png", event.getGiftInfo().getUrl());

        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(42L, sender.getUid());
        assertEquals("送礼人", sender.getUname());
        assertEquals("https://example.com/face.png", sender.getFace());
        assertEquals(GuardType.Captain, sender.getGuard().getGuardType());
        assertEquals("https://example.com/guard.png", sender.getGuard().getIcon());
        assertEquals(1000L, sender.getFansMedal().getUid());
        assertEquals("主播", sender.getFansMedal().getUname());
        assertEquals(2000L, sender.getFansMedal().getRoomId());
        assertEquals("星星", sender.getFansMedal().getName());
        assertEquals(12, sender.getFansMedal().getLevel());
        assertTrue(sender.getFansMedal().getIsLighted());
        assertEquals(42, sender.getHonorLevel());
    }

    /**
     * 测试一条 protobuf 消息中包含多个盲盒礼物结果
     * <p>
     * 验证每个 GiftItem 均生成独立事件，并正确计算盲盒价格、礼物价值与盈亏。
     */
    @Test
    void parsesEveryBlindGiftFromPbField() throws IOException {
        byte[] blindGift = message(output -> {
            output.writeUInt64(2, 35206L);
            output.writeString(3, "幸运盲盒");
            output.writeUInt64(6, 5000L);
        });
        byte[] firstGift = gift(35206L, "好运柚叶", 1, 2500L, 2500L, 5000L,
                "gold", 1_760_000_001L, "first.png");
        byte[] secondGift = gift(35208L, "星光铃铛", 8, 5200L, 5200L, 40000L,
                "gold", 1_760_000_002L, "second.png");
        byte[] payload = broadcast(null, blindGift, List.of(firstGift, secondGift));

        List<StarBotBaseLiveEvent> events = parser.parseEvents(command(payload), source);

        assertEquals(2, events.size());
        BilibiliRandomGiftEvent first = assertInstanceOf(BilibiliRandomGiftEvent.class, events.get(0));
        assertEquals(35206L, first.getRandomGiftInfo().getId());
        assertEquals("幸运盲盒", first.getRandomGiftInfo().getName());
        assertEquals(5.0, first.getRandomGiftInfo().getPrice());
        assertEquals(1, first.getRandomGiftInfo().getCount());
        assertEquals(5.0, first.getPrice());
        assertEquals(2.5, first.getValue());
        assertEquals(-2.5, first.getProfit());

        BilibiliRandomGiftEvent second = assertInstanceOf(BilibiliRandomGiftEvent.class, events.get(1));
        assertEquals(8, second.getGiftInfo().getCount());
        assertEquals(40.0, second.getPrice());
        assertEquals(41.6, second.getValue());
        assertEquals(1.6, second.getProfit());
    }

    /**
     * 测试解析真实进场数据
     */
    @Test
    void parsesRealInteractEnter() {
        List<StarBotBaseLiveEvent> events = parser.parseEvents(command("INTERACT_WORD_V2", REAL_INTERACT_ENTER), source);

        assertEquals(1, events.size());
        BilibiliEnterRoomEvent event = assertInstanceOf(BilibiliEnterRoomEvent.class, events.get(0));
        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(12677205L, sender.getUid());
        assertEquals("PNEUMA373", sender.getUname());
        assertEquals("https://i0.hdslb.com/bfs/face/462ee33b1fc5f9a5512bd474e885573adf7a9716.jpg", sender.getFace());
        assertNull(sender.getFansMedal());
        assertNull(sender.getGuard());
        assertEquals(10, sender.getHonorLevel());
        assertFalse(event.isFromPromotion());
        assertNull(event.getPromotionSource());
        assertEquals(1_788_425_934_000L, event.getTimestamp());
    }

    /**
     * 测试解析真实关注数据
     */
    @Test
    void parsesRealInteractFollow() {
        List<StarBotBaseLiveEvent> events = parser.parseEvents(command("INTERACT_WORD_V2", REAL_INTERACT_FOLLOW), source);

        assertEquals(1, events.size());
        BilibiliFollowEvent event = assertInstanceOf(BilibiliFollowEvent.class, events.get(0));
        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(21953917L, sender.getUid());
        assertEquals("软糖小鼠酱", sender.getUname());
        assertEquals("https://i1.hdslb.com/bfs/face/45ef442aa9615966aa485cd67fae7bb5b1062610.jpg", sender.getFace());
        assertNull(sender.getFansMedal());
        assertNull(sender.getGuard());
        assertNull(sender.getHonorLevel());
        assertEquals(1_788_426_183_000L, event.getTimestamp());
    }

    /**
     * 测试解析真实分享数据及粉丝勋章
     */
    @Test
    void parsesRealInteractShare() {
        List<StarBotBaseLiveEvent> events = parser.parseEvents(command("INTERACT_WORD_V2", REAL_INTERACT_SHARE), source);

        assertEquals(1, events.size());
        BilibiliShareEvent event = assertInstanceOf(BilibiliShareEvent.class, events.get(0));
        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(26928797L, sender.getUid());
        assertEquals("mikufilck", sender.getUname());
        assertEquals("https://i2.hdslb.com/bfs/face/6a0dd36ba7fa18e84c6527c87beba02a5d2de3f9.jpg", sender.getFace());
        assertEquals(3546384651258599L, sender.getFansMedal().getUid());
        assertNull(sender.getFansMedal().getUname());
        assertNull(sender.getFansMedal().getRoomId());
        assertNull(sender.getFansMedal().getFace());
        assertEquals("做猫的", sender.getFansMedal().getName());
        assertEquals(21, sender.getFansMedal().getLevel());
        assertTrue(sender.getFansMedal().getIsLighted());
        assertNull(sender.getGuard());
        assertEquals(1_775_828_914_000L, event.getTimestamp());
    }

    /**
     * 测试解析真实付费礼物数据
     */
    @Test
    void parsesRealPaidGift() {
        List<StarBotBaseLiveEvent> events = parser.parseEvents(command(REAL_PAID_GIFT), source);

        assertEquals(1, events.size());
        BilibiliPaidGiftEvent event = assertInstanceOf(BilibiliPaidGiftEvent.class, events.get(0));
        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(3546837514455634L, sender.getUid());
        assertEquals("Ceriselune", sender.getUname());
        assertEquals(32, sender.getHonorLevel());
        assertEquals(31164L, event.getGiftInfo().getId());
        assertEquals("粉丝团灯牌", event.getGiftInfo().getName());
        assertEquals(0.1, event.getGiftInfo().getPrice());
        assertEquals(1, event.getGiftInfo().getCount());
        assertEquals(0.1, event.getValue());
    }

    /**
     * 测试解析真实免费礼物数据
     */
    @Test
    void parsesRealFreeGift() {
        List<StarBotBaseLiveEvent> events = parser.parseEvents(command(REAL_FREE_GIFT), source);

        assertEquals(1, events.size());
        BilibiliFreeGiftEvent event = assertInstanceOf(BilibiliFreeGiftEvent.class, events.get(0));
        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, event.getSender());
        assertEquals(9232442L, sender.getUid());
        assertEquals("饼゜", sender.getUname());
        assertNull(sender.getGuard());
        assertEquals("白礼甜", sender.getFansMedal().getName());
        assertEquals(5, sender.getFansMedal().getLevel());
        assertFalse(sender.getFansMedal().getIsLighted());
        assertEquals(79, sender.getHonorLevel());
        assertEquals(31738L, event.getGiftInfo().getId());
        assertEquals("粉丝团灯牌", event.getGiftInfo().getName());
        assertEquals(0.0, event.getGiftInfo().getPrice());
        assertEquals(1, event.getGiftInfo().getCount());
    }

    /**
     * 测试解析真实盲盒礼物数据
     */
    @Test
    void parsesRealMultipleBlindGifts() {
        List<StarBotBaseLiveEvent> events = parser.parseEvents(command(REAL_MULTIPLE_BLIND_GIFTS), source);

        assertEquals(2, events.size());
        BilibiliRandomGiftEvent first = assertInstanceOf(BilibiliRandomGiftEvent.class, events.get(0));
        BilibiliRandomGiftEvent second = assertInstanceOf(BilibiliRandomGiftEvent.class, events.get(1));

        BilibiliUserInfo sender = assertInstanceOf(BilibiliUserInfo.class, first.getSender());
        assertEquals(48692754L, sender.getUid());
        assertEquals("白礼的魔法胖次", sender.getUname());
        assertEquals(GuardType.Commander, sender.getGuard().getGuardType());
        assertEquals("白礼甜", sender.getFansMedal().getName());
        assertEquals(59, sender.getFansMedal().getLevel());
        assertTrue(sender.getFansMedal().getIsLighted());
        assertEquals("https://i0.hdslb.com/bfs/live/62ac06fd72b05fe22be26426b9e1a8e1fc2c6b89.png", sender.getGuard().getIcon());
        assertEquals(60, sender.getHonorLevel());

        assertEquals(35461L, first.getRandomGiftInfo().getId());
        assertEquals("羁绊宝盒", first.getRandomGiftInfo().getName());
        assertEquals(33.0, first.getRandomGiftInfo().getPrice());
        assertEquals(5, first.getRandomGiftInfo().getCount());
        assertEquals(35464L, first.getGiftInfo().getId());
        assertEquals("甜蜜契约", first.getGiftInfo().getName());
        assertEquals(35.0, first.getGiftInfo().getPrice());
        assertEquals(5, first.getGiftInfo().getCount());
        assertEquals(165.0, first.getPrice());
        assertEquals(175.0, first.getValue());
        assertEquals(10.0, first.getProfit());

        assertEquals(35461L, second.getRandomGiftInfo().getId());
        assertEquals(35463L, second.getGiftInfo().getId());
        assertEquals("星光点点", second.getGiftInfo().getName());
        assertEquals(20.0, second.getGiftInfo().getPrice());
        assertEquals(5, second.getGiftInfo().getCount());
        assertEquals(165.0, second.getPrice());
        assertEquals(100.0, second.getValue());
        assertEquals(-65.0, second.getProfit());
    }

    /**
     * 测试缺少 pb 字段的 SEND_GIFT_V2 消息不会生成事件
     */
    @Test
    void ignoresSendGiftV2WithoutPbPayload() {
        JSONObject command = new JSONObject();
        command.put("cmd", "SEND_GIFT_V2");
        command.put("data", new JSONObject());

        assertTrue(parser.parseEvents(command, source).isEmpty());
    }

    /**
     * 测试缺少 pb 字段的 INTERACT_WORD_V2 消息不会生成事件
     */
    @Test
    void ignoresInteractWordV2WithoutPbPayload() {
        JSONObject command = new JSONObject();
        command.put("cmd", "INTERACT_WORD_V2");
        command.put("data", new JSONObject());

        assertTrue(parser.parseEvents(command, source).isEmpty());
    }

    /**
     * 测试非法 Base64 的 INTERACT_WORD_V2 消息不会生成事件
     */
    @Test
    void ignoresInteractWordV2WithInvalidPbPayload() {
        assertTrue(parser.parseEvents(command("INTERACT_WORD_V2", "invalid-payload"), source).isEmpty());
    }

    /**
     * 将 protobuf 字节数据封装为 SEND_GIFT_V2 原始消息
     *
     * @param payload protobuf 字节数据
     * @return SEND_GIFT_V2 原始消息
     */
    private static JSONObject command(byte[] payload) {
        return command("SEND_GIFT_V2", payload);
    }

    /**
     * 将 protobuf 字节数据封装为指定类型的原始消息
     *
     * @param type 消息类型
     * @param payload protobuf 字节数据
     * @return 原始消息
     */
    private static JSONObject command(String type, byte[] payload) {
        return command(type, Base64.getEncoder().encodeToString(payload));
    }

    /**
     * 将 Base64 编码的 protobuf 数据封装为 SEND_GIFT_V2 原始消息
     *
     * @param payload Base64 编码的 protobuf 数据
     * @return SEND_GIFT_V2 原始消息
     */
    private static JSONObject command(String payload) {
        return command("SEND_GIFT_V2", payload);
    }

    /**
     * 将 Base64 编码的 protobuf 数据封装为指定类型的原始消息
     *
     * @param type 消息类型
     * @param payload Base64 编码的 protobuf 数据
     * @return 原始消息
     */
    private static JSONObject command(String type, String payload) {
        JSONObject command = new JSONObject();
        command.put("cmd", type);
        JSONObject data = new JSONObject();
        data.put("pb", payload);
        command.put("data", data);
        return command;
    }

    /**
     * 构造 InteractWordV2 protobuf 数据
     *
     * @param msgType 操作类型
     * @param spread 是否来自推广
     * @param spreadDesc 推广来源
     * @return InteractWordV2 protobuf 字节数据
     */
    private static byte[] interaction(int msgType, boolean spread, String spreadDesc) throws IOException {
        byte[] base = message(output -> {
            output.writeString(1, "操作用户");
            output.writeString(2, "https://example.com/face.png");
        });
        byte[] userMedal = message(output -> {
            output.writeString(1, "星星");
            output.writeUInt32(2, 12);
            output.writeBool(9, true);
            output.writeUInt64(10, 1000L);
            output.writeUInt32(11, 3);
            output.writeString(13, "https://example.com/guard.png");
        });
        byte[] wealth = message(output -> output.writeUInt32(1, 42));
        byte[] guard = message(output -> {
            output.writeUInt32(1, 3);
            output.writeString(2, "2026-12-31 23:59:59");
        });
        byte[] userInfo = message(output -> {
            output.writeUInt64(1, 42L);
            output.writeByteArray(2, base);
            output.writeByteArray(3, userMedal);
            output.writeByteArray(4, wealth);
            output.writeByteArray(6, guard);
        });
        byte[] fansMedal = message(output -> {
            output.writeInt64(1, 1000L);
            output.writeInt64(2, 12);
            output.writeString(3, "星星");
            output.writeInt64(8, 1);
            output.writeInt64(9, 3);
        });
        return message(output -> {
            output.writeUInt64(1, 42L);
            output.writeString(2, "操作用户");
            output.writeUInt64(4, 1);
            output.writeUInt64(5, msgType);
            output.writeUInt64(6, 2000L);
            output.writeUInt64(7, 1_760_100_000L);
            output.writeByteArray(9, fansMedal);
            output.writeUInt64(10, spread ? 1 : 0);
            output.writeString(13, spreadDesc);
            output.writeUInt64(16, 3);
            output.writeByteArray(22, userInfo);
            output.writeString(99, "未知字段应被忽略");
        });
    }

    /**
     * 构造 SendGiftBroadcast protobuf 数据
     *
     * @param senderMedal 送礼用户的粉丝勋章数据
     * @param blindGift 盲盒原始礼物数据
     * @param gifts 礼物结果列表
     * @return SendGiftBroadcast protobuf 字节数据
     */
    private static byte[] broadcast(byte[] senderMedal, byte[] blindGift, List<byte[]> gifts) throws IOException {
        byte[] wealth = message(output -> output.writeUInt64(1, 42));
        byte[] senderUinfo = message(output -> {
            output.writeUInt64(1, 42L);
            if (senderMedal != null) {
                output.writeByteArray(3, senderMedal);
            }
        });
        return message(output -> {
            output.writeUInt64(1, 42L);
            output.writeString(2, "送礼人");
            output.writeString(3, "https://example.com/face.png");
            output.writeUInt32(5, 3);
            if (blindGift != null) {
                output.writeByteArray(9, blindGift);
            }
            for (byte[] gift : gifts) {
                output.writeByteArray(10, gift);
            }
            output.writeByteArray(13, wealth);
            output.writeByteArray(15, senderUinfo);
            output.writeString(99, "未知字段应被忽略");
        });
    }

    /**
     * 构造 GiftItem protobuf 数据
     *
     * @param id 礼物 ID
     * @param name 礼物名称
     * @param count 礼物数量
     * @param price 礼物原价
     * @param discountPrice 礼物折后价
     * @param totalCoin 礼物总价
     * @param coinType 瓜子类型
     * @param timestamp 发送时间戳
     * @param imageUrl 礼物图片地址
     * @return GiftItem protobuf 字节数据
     */
    private static byte[] gift(long id, String name, int count, long price, long discountPrice,
                               long totalCoin, String coinType, long timestamp, String imageUrl) throws IOException {
        byte[] giftMaterial = message(output -> output.writeString(1, imageUrl));
        return message(output -> {
            output.writeUInt64(1, id);
            output.writeString(2, name);
            output.writeUInt32(3, count);
            output.writeUInt32(4, 0);
            output.writeUInt64(5, price);
            output.writeUInt64(6, discountPrice);
            output.writeUInt64(7, totalCoin);
            output.writeString(8, coinType);
            output.writeString(9, "transaction-id");
            output.writeUInt64(10, timestamp);
            output.writeString(12, "random-id");
            output.writeString(18, "投喂");
            output.writeByteArray(35, giftMaterial);
        });
    }

    /**
     * 使用指定写入逻辑构造 protobuf 消息
     *
     * @param writer protobuf 字段写入逻辑
     * @return protobuf 字节数据
     */
    private static byte[] message(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    /**
     * protobuf 字段写入逻辑
     */
    @FunctionalInterface
    private interface Writer {
        void write(CodedOutputStream output) throws IOException;
    }
}
