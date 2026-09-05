package com.starlwr.bot.bilibili.telemetry;

import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore;
import com.starlwr.bot.bilibili.credential.CredentialEnvelope;
import com.starlwr.bot.bilibili.credential.StoredCookie;
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline;
import com.starlwr.bot.bilibili.http.BilibiliHttpRequest;
import com.starlwr.bot.bilibili.http.BilibiliHttpResponse;
import com.starlwr.bot.bilibili.model.Cookies;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebLogSessionTest {
    @Test
    void retriesEntryThreeTimesAndClassifiesGatewayTimeout() {
        LiveTelemetryProperties properties = new LiveTelemetryProperties();
        properties.setWebLogStartMaxAttempts(3);
        BilibiliHttpPipeline http = mock(BilibiliHttpPipeline.class);
        BilibiliCredentialFileStore credentials = mock(BilibiliCredentialFileStore.class);
        PlayUrlProvider playUrls = mock(PlayUrlProvider.class);
        TelemetryTaskDispatcher dispatcher = mock(TelemetryTaskDispatcher.class);

        CredentialEnvelope envelope = new CredentialEnvelope();
        Cookies account = new Cookies();
        account.setDedeUserId("123");
        account.setBiliJct("csrf");
        envelope.setAccount(account);
        StoredCookie liveBuvid = new StoredCookie();
        liveBuvid.setName("LIVE_BUVID");
        liveBuvid.setValue("AUTO123");
        envelope.setCookies(List.of(liveBuvid));
        when(credentials.snapshot()).thenReturn(envelope);

        PlayUrlLease lease = new PlayUrlLease(1, 2, 3, 4, "https://example.invalid/live.m3u8",
                System.currentTimeMillis(), Instant.now().plusSeconds(300).getEpochSecond());
        when(playUrls.get(1, false)).thenReturn(lease);
        BilibiliHttpRequest request = new BilibiliHttpRequest("POST", URI.create("https://data.bilivideo.com/log/web/te9Kl"),
                Map.of(), new byte[0], com.starlwr.bot.bilibili.http.BilibiliBodyType.JSON,
                "bilibili-telemetry-weblog", "jvm", false);
        when(http.postJson(anyString(), anyMap(), any(), anyString()))
                .thenReturn(new BilibiliHttpResponse(request, 504, Map.of(), new byte[0], 1, 10));

        AtomicReference<WebLogFailure> terminal = new AtomicReference<>();
        LiveClientContext context = new LiveClientContext(1, 1, 2, 3, 4,
                System.currentTimeMillis(), 1, lease);
        WebLogSession session = new WebLogSession(context, properties, http, credentials, playUrls,
                new CsnSigner(), dispatcher, null, failure -> {
                    terminal.set(failure);
                    return Unit.INSTANCE;
                });

        session.start();

        verify(http, times(3)).postJson(anyString(), anyMap(), any(), anyString());
        assertEquals(WebLogFailureKind.HTTP_GATEWAY_TIMEOUT, terminal.get().getKind());
    }

    @Test
    void periodicGatewayTimeoutWaitsForNextHeartbeatInsteadOfImmediateReplay() {
        LiveTelemetryProperties properties = new LiveTelemetryProperties();
        BilibiliHttpPipeline http = mock(BilibiliHttpPipeline.class);
        BilibiliCredentialFileStore credentials = mock(BilibiliCredentialFileStore.class);
        PlayUrlProvider playUrls = mock(PlayUrlProvider.class);
        TelemetryTaskDispatcher dispatcher = mock(TelemetryTaskDispatcher.class);

        CredentialEnvelope envelope = new CredentialEnvelope();
        Cookies account = new Cookies();
        account.setDedeUserId("123");
        account.setBiliJct("csrf");
        envelope.setAccount(account);
        StoredCookie liveBuvid = new StoredCookie();
        liveBuvid.setName("LIVE_BUVID");
        liveBuvid.setValue("AUTO123");
        envelope.setCookies(List.of(liveBuvid));
        when(credentials.snapshot()).thenReturn(envelope);

        PlayUrlLease lease = new PlayUrlLease(1, 2, 3, 4, "https://example.invalid/live.m3u8",
                System.currentTimeMillis(), Instant.now().plusSeconds(300).getEpochSecond());
        when(playUrls.get(1, false)).thenReturn(lease);
        BilibiliHttpRequest request = new BilibiliHttpRequest("POST", URI.create("https://data.bilivideo.com/log/web/te9Kl"),
                Map.of(), new byte[0], com.starlwr.bot.bilibili.http.BilibiliBodyType.JSON,
                "bilibili-telemetry-weblog", "jvm", false);
        byte[] successBody = "{\"code\":0,\"data\":{\"sid\":\"sid\",\"stky\":\"key\",\"hbil\":60}}"
                .getBytes(StandardCharsets.UTF_8);
        BilibiliHttpResponse success = new BilibiliHttpResponse(request, 200, Map.of(), successBody, 1, 10);
        BilibiliHttpResponse timeout = new BilibiliHttpResponse(request, 504, Map.of(), new byte[0], 2, 10);
        when(http.postJson(anyString(), anyMap(), any(), anyString())).thenReturn(success, success, timeout);

        AtomicReference<Function0<Unit>> scheduledAction = new AtomicReference<>();
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(dispatcher.schedule(any(Duration.class), anyString(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    scheduledAction.set(invocation.getArgument(4));
                    return scheduledFuture;
                });

        AtomicReference<WebLogFailure> terminal = new AtomicReference<>();
        LiveClientContext context = new LiveClientContext(1, 1, 2, 3, 4,
                System.currentTimeMillis(), 1, lease);
        WebLogSession session = new WebLogSession(context, properties, http, credentials, playUrls,
                new CsnSigner(), dispatcher, null, failure -> {
                    terminal.set(failure);
                    return Unit.INSTANCE;
                });

        session.start();
        Function0<Unit> heartbeat = scheduledAction.get();
        assertNotNull(heartbeat);
        heartbeat.invoke();

        verify(http, times(3)).postJson(anyString(), anyMap(), any(), anyString());
        verify(dispatcher, times(2)).schedule(any(Duration.class), anyString(), anyLong(), any(), any());
        assertNull(terminal.get());
    }
}
