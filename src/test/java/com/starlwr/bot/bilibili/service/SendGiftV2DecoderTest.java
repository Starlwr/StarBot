package com.starlwr.bot.bilibili.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SendGiftV2DecoderTest {
    @Test
    void decodesRepeatedGiftsAnd64BitAmounts() {
        byte[] gift1 = message(fieldVarint(1, 31036), fieldString(2, "小花花"),
                fieldVarint(3, 2), fieldVarint(6, 4_294_967_296L), fieldVarint(7, 4_294_967_300L), fieldString(8, "gold"));
        byte[] gift2 = message(fieldVarint(1, 31037), fieldString(2, "大花花"), fieldVarint(3, 1));
        byte[] senderBase = message(fieldString(1, "测试用户"));
        byte[] sender = message(fieldVarint(1, 1234567890123L), fieldBytes(2, senderBase));
        byte[] root = message(fieldBytes(15, sender), fieldBytes(10, gift1), fieldBytes(99, new byte[]{1, 2, 3}), fieldBytes(10, gift2));

        SendGiftV2Decoder.Result result = new SendGiftV2Decoder().decode(Base64.getEncoder().encodeToString(root));

        assertEquals(1234567890123L, result.getSenderUid());
        assertEquals("测试用户", result.getSenderName());
        assertEquals(2, result.getGifts().size());
        assertEquals(4_294_967_296L, result.getGifts().get(0).getDiscountPrice());
        assertEquals(31037L, result.getGifts().get(1).getId());
        assertTrue(result.getUnknownFieldCount() >= 1);
        assertArrayEquals(root, result.getRaw());
    }

    private static byte[] message(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) out.writeBytes(field);
        return out.toByteArray();
    }
    private static byte[] fieldString(int n, String value) { return fieldBytes(n, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static byte[] fieldBytes(int n, byte[] value) { return concat(varint((n << 3) | 2), varint(value.length), value); }
    private static byte[] fieldVarint(int n, long value) { return concat(varint(n << 3), varint(value)); }
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7fL) != 0) { out.write((int)((value & 0x7f) | 0x80)); value >>>= 7; }
        out.write((int)value); return out.toByteArray();
    }
    private static byte[] concat(byte[]... arrays) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] a : arrays) out.writeBytes(a); return out.toByteArray(); }
}
