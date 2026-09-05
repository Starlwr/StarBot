package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.event.live.BilibiliCommanderEvent;
import com.starlwr.bot.bilibili.log.BilibiliDebugFileLogger;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BilibiliEventParserTest {
    private final BilibiliEventParser parser = new BilibiliEventParser(
            new StarBotBilibiliProperties(),
            mock(BilibiliApiUtil.class),
            mock(BilibiliGiftService.class),
            mock(BilibiliDebugFileLogger.class)
    );

    private final LiveStreamerInfo source = new LiveStreamerInfo(511373704L, "测试主播", 27460077L);

    @Test
    void guardRenewalWithoutSendTimeUsesServerStartTime() {
        JSONObject message = JSON.parseObject("""
                {
                  "cmd": "USER_TOAST_MSG",
                  "data": {
                    "end_time": 1784017489,
                    "guard_level": 2,
                    "num": 12,
                    "op_type": 2,
                    "price": 1998000,
                    "role_name": "提督",
                    "start_time": 1784017487,
                    "toast_msg": "测试用户在主播的直播间续费了12个月提督",
                    "uid": 125867052,
                    "unit": "月",
                    "username": "测试用户"
                  }
                }
                """);

        StarBotBaseLiveEvent parsed = parser.parse(message, source).orElseThrow();
        BilibiliCommanderEvent event = assertInstanceOf(BilibiliCommanderEvent.class, parsed);

        assertEquals(Instant.ofEpochSecond(1784017487L).toEpochMilli(), event.getTimestamp());
        assertEquals(GuardOperateType.RENEWAL, event.getOperateType());
        assertEquals(12, event.getCount());
        assertEquals(1998.0, event.getPrice());
        assertEquals(125867052L, event.getSender().getUid());
    }

    @Test
    void guardStartTimeIsPreferredWhenBothServerTimesExist() {
        JSONObject message = JSON.parseObject("""
                {
                  "cmd": "USER_TOAST_MSG",
                  "send_time": 1784017489123,
                  "data": {
                    "guard_level": 2,
                    "num": 1,
                    "op_type": 1,
                    "price": 1998000,
                    "role_name": "提督",
                    "start_time": 1784017487,
                    "uid": 125867052,
                    "unit": "月",
                    "username": "测试用户"
                  }
                }
                """);

        StarBotBaseLiveEvent parsed = parser.parse(message, source).orElseThrow();

        assertEquals(Instant.ofEpochSecond(1784017487L).toEpochMilli(), parsed.getTimestamp());
    }

    @Test
    void guardWithoutAnyServerTimeUsesReceiveTime() {
        JSONObject message = JSON.parseObject("""
                {
                  "cmd": "USER_TOAST_MSG",
                  "data": {
                    "guard_level": 2,
                    "num": 1,
                    "op_type": 1,
                    "price": 1998000,
                    "role_name": "提督",
                    "uid": 125867052,
                    "unit": "月",
                    "username": "测试用户"
                  }
                }
                """);
        long before = System.currentTimeMillis();

        StarBotBaseLiveEvent parsed = parser.parse(message, source).orElseThrow();

        assertTrue(parsed.getTimestamp() >= before);
        assertTrue(parsed.getTimestamp() <= System.currentTimeMillis());
    }
}
