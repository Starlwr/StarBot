package com.starlwr.bot.bilibili.util;

import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity;
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore;
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline;
import com.starlwr.bot.bilibili.http.BilibiliHttpRequest;
import com.starlwr.bot.bilibili.http.BilibiliHttpResponse;
import com.starlwr.bot.bilibili.http.BilibiliBodyType;
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger;
import com.starlwr.bot.bilibili.risk.BilibiliRiskProperties;
import com.starlwr.bot.bilibili.risk.GaiaChallengeProvider;
import com.starlwr.bot.core.support.http.HttpService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliApiRetryTest {
    @Test
    void retriesTransientTimeoutCodeWithoutInvokingCredentialOrGaiaMutations() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getNetwork().setApiRetryMaxTimes(2);
        properties.getNetwork().setApiRetryInterval(0);
        BilibiliHttpPipeline pipeline = mock(BilibiliHttpPipeline.class);
        BilibiliCredentialFileStore credentialStore = mock(BilibiliCredentialFileStore.class);
        GaiaChallengeProvider gaia = mock(GaiaChallengeProvider.class);
        BilibiliHttpRequest request = new BilibiliHttpRequest("GET", URI.create("https://api.bilibili.com/x/test"),
                Map.of(), new byte[0], BilibiliBodyType.NONE, "test", "jvm", false);
        BilibiliHttpResponse timeout = new BilibiliHttpResponse(request, 200, Map.of(),
                "{\"code\":1024,\"message\":\"timeout\"}".getBytes(), 1, 1);
        BilibiliHttpResponse success = new BilibiliHttpResponse(request, 200, Map.of(),
                "{\"code\":0,\"data\":{\"ok\":true}}".getBytes(), 1, 1);
        when(pipeline.get(anyString(), anyMap(), anyString())).thenReturn(timeout, success);

        BilibiliApiService api = new BilibiliApiService(properties, mock(HttpService.class),
                mock(BilibiliBrowserIdentity.class), new BilibiliNetworkLogger(properties), pipeline,
                credentialStore, new BilibiliRiskProperties(), gaia,
                mock(com.starlwr.bot.bilibili.risk.BrowserRiskExecutor.class));
        api.init();

        assertTrue(api.requestBilibiliApi(request.getUri().toString(), "GET", Map.of(), Map.of())
                .getBooleanValue("ok"));
        verify(pipeline, times(2)).get(anyString(), anyMap(), anyString());
        verify(gaia, never()).submit(org.mockito.ArgumentMatchers.any());
        verify(credentialStore, never()).saveCookies(org.mockito.ArgumentMatchers.any());
    }
}
