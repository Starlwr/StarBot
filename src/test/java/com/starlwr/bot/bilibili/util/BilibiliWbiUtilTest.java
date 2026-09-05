package com.starlwr.bot.bilibili.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliWbiUtilTest {
    @Test
    void encodedQueryIsDefaultAndContainsOneWts() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("foo", "a+b c");
        params.put("bar", "中文");

        String query = BilibiliWbiUtil.getWbiSign(params, "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210", true, 1_700_000_000L);

        assertThat(query).contains("bar=%E4%B8%AD%E6%96%87", "foo=a%2Bb%20c", "wts=1700000000");
        assertThat(query.split("wts=", -1)).hasSize(2);
    }

    @Test
    void legacyModeOnlyChangesFinalQueryRepresentation() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("foo", "a+b c");

        String encoded = BilibiliWbiUtil.getWbiSign(params, "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210", true, 1_700_000_000L);
        String legacy = BilibiliWbiUtil.getWbiSign(params, "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210", false, 1_700_000_000L);

        assertThat(encoded.substring(encoded.indexOf("&w_rid=") + 8))
                .isEqualTo(legacy.substring(legacy.indexOf("&w_rid=") + 8));
        assertThat(legacy).contains("foo=a+b c&wts=1700000000");
    }
}
