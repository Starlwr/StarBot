package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger;
import com.starlwr.bot.bilibili.model.*;
import com.starlwr.bot.bilibili.protocol.DanmakuPacketCodec;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliLiveRoomLifecycleTest {
    @Test void failedSendDetachesAndReconnectsEvenWithoutTransportCallbacks() throws Exception {
        Fixture f = new Fixture(); f.establish(); Object context = f.context();
        doThrow(new java.io.IOException("send failure")).when(f.session).sendMessage(any());
        ReflectionTestUtils.invokeMethod(f.connector, "sendOperation", context, 24, new byte[0]);
        assertNull(ReflectionTestUtils.getField(context, "session"));
        assertFalse(f.open.get());
        verify(f.tasks, times(1)).schedule(eq(f.connector), anyLong());
        f.connector.disconnect();
    }

    @Test void failingDiagnosticsCannotPreventHandshakeCleanupOrReconnect() throws Exception {
        Fixture f = new Fixture(); f.autoEstablish = false;
        doThrow(new IllegalStateException("diagnostic failure")).when(f.networkLog).httpFailure(any(), any());
        f.connector.connect(); f.workers.remove().run();
        assertTrue(f.future.isCancelled());
        assertNull(ReflectionTestUtils.getField(f.context(), "connectionFuture"));
        verify(f.tasks, times(1)).schedule(eq(f.connector), anyLong());
        f.connector.disconnect();
    }

    @Test void maintenanceHasOnlyOnePendingJobAndBecomesInertAfterDisconnect() throws Exception {
        Fixture f = new Fixture(); f.establish(); Object context = f.context(); f.workers.clear();
        ReflectionTestUtils.setField(f.connector, "status", ConnectStatus.CONNECTED);
        java.util.concurrent.atomic.AtomicBoolean gate = (java.util.concurrent.atomic.AtomicBoolean)
                ReflectionTestUtils.getField(context, "heartbeatQueued");
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        Runnable action = runs::incrementAndGet;
        for(int i=0;i<100;i++) ReflectionTestUtils.invokeMethod(f.connector, "submitMaintenance", context, gate, action);
        assertEquals(1, f.workers.size());
        f.connector.disconnect(); f.workers.remove().run();
        assertEquals(0, runs.get()); assertFalse(gate.get());
    }
    @Test void establishesBeforeQueuedAuthAndDetachesOnDisconnect() throws Exception {
        Fixture f = new Fixture(); f.establish();
        Object context = f.context();
        assertSame(f.session, ReflectionTestUtils.getField(context, "session"));
        assertEquals(1, f.workers.size());
        f.connector.disconnect();
        assertNull(ReflectionTestUtils.getField(context, "session"));
        assertNull(f.context());
        assertFalse(f.open.get());
        f.workers.remove().run();
        verifyNoInteractions(f.account);
        f.connector.connect(); assertTrue(f.workers.isEmpty());
    }

    @Test void copiesOnlyMessageBytesBeforeQueueingInsteadOfRetainingTheTransportBuffer() throws Exception {
        Fixture f = new Fixture(); f.establish(); f.workers.clear();
        byte[] packet = f.codec.encode(5, "{\"cmd\":\"TEST\"}".getBytes(StandardCharsets.UTF_8), 1, 1);
        byte[] backing = new byte[8 * 1024 * 1024];
        ByteBuffer transport = ByteBuffer.wrap(backing); transport.put(packet).flip();
        f.handler.handleMessage(f.session, new BinaryMessage(transport));
        Arrays.fill(backing, (byte) 0); // Simulate Tomcat reusing its receive buffer immediately.
        Runnable queued = f.workers.remove();
        for (var field : queued.getClass().getDeclaredFields()) {
            field.setAccessible(true); Object value = field.get(queued);
            assertFalse(value instanceof WebSocketSession || value instanceof WebSocketMessage<?> || value instanceof ByteBuffer);
            if (value instanceof byte[] copied) assertEquals(packet.length, copied.length);
        }
        queued.run();
        verify(f.parser).parseMany(argThat(json -> "TEST".equals(json.getString("cmd"))), eq(f.up));
        f.connector.disconnect();
    }

    @Test void remoteCloseClearsNativeSessionAndSchedulesOnceEvenWithRepeatedCallbacks() throws Exception {
        Fixture f = new Fixture(); f.establish();
        Object context = f.context();
        f.open.set(false);
        f.handler.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        f.handler.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        assertNull(ReflectionTestUtils.getField(context, "session"));
        assertNull(ReflectionTestUtils.getField(context, "connectionFuture"));
        verify(f.tasks, times(1)).schedule(eq(f.connector), anyLong());
        f.workers.remove().run(); verifyNoInteractions(f.account);
        f.connector.disconnect();
    }

    @Test void transportErrorClosesAndReleasesEvenWhenNoCloseCallbackArrives() throws Exception {
        Fixture f = new Fixture(); f.establish(); Object context = f.context();
        f.handler.handleTransportError(f.session, new java.io.IOException("simulated transport failure"));
        assertNull(ReflectionTestUtils.getField(context, "session"));
        assertFalse(f.open.get());
        assertEquals(ConnectStatus.ERROR, f.connector.getStatus());
        verify(f.tasks, times(1)).schedule(eq(f.connector), anyLong());
        f.connector.disconnect();
    }

    @Test void timedOutHandshakeCancelsFutureAndClosesLateSession() throws Exception {
        Fixture f = new Fixture(); f.autoEstablish = false;
        f.connector.connect(); f.workers.remove().run();
        Object context = f.context();
        assertTrue(f.future.isCancelled());
        assertNull(ReflectionTestUtils.getField(context, "connectionFuture"));
        f.handler.afterConnectionEstablished(f.session);
        assertFalse(f.open.get()); assertTrue(f.workers.isEmpty());
        verify(f.tasks, times(1)).schedule(eq(f.connector), anyLong());
        f.connector.disconnect();
    }

    @Test void removalCancelsQueuedConnectAndDuplicateRequestsDoNotAccumulate() throws Exception {
        Fixture f = new Fixture();
        for(int i=0;i<100;i++) f.connector.connect();
        assertEquals(1, f.workers.size());
        f.connector.disconnect(); f.workers.remove().run();
        verifyNoInteractions(f.api, f.client);
    }

    @Test void closedGenerationCannotResurrectAfterReplacement() throws Exception {
        Fixture f = new Fixture(); f.establish(); WebSocketHandler old = f.handler;
        f.open.set(false); old.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        f.workers.clear();
        f.connector.connect(); f.workers.remove().run(); // New generation binds the same test session.
        Object current = f.context();
        old.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        assertSame(current, f.context());
        assertSame(f.session, ReflectionTestUtils.getField(current, "session"));
        f.connector.disconnect();
    }

    private static final class Fixture {
        final Queue<Runnable> workers = new ArrayDeque<>();
        final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        final BilibiliLiveRoomConnectTaskService tasks = mock(BilibiliLiveRoomConnectTaskService.class);
        final BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        final BilibiliAccountService account = mock(BilibiliAccountService.class);
        final BilibiliEventParser parser = mock(BilibiliEventParser.class);
        final StandardWebSocketClient client = mock(StandardWebSocketClient.class);
        final BilibiliNetworkLogger networkLog = mock(BilibiliNetworkLogger.class);
        final WebSocketSession session = mock(WebSocketSession.class);
        final AtomicBoolean open = new AtomicBoolean(true);
        final Up up = new Up(1L, "test", 2L);
        final DanmakuPacketCodec codec = new DanmakuPacketCodec();
        final BilibiliLiveRoomConnector connector;
        WebSocketHandler handler;
        CompletableFuture<WebSocketSession> future;
        boolean autoEstablish = true;
        Fixture() throws Exception {
            when(networkLog.httpRequest(anyString(), anyString(), anyString(), anyMap(), isNull()))
                    .thenReturn(new BilibiliNetworkLogger.HttpTrace(1L, "test", "live-ws", "GET", "https://example.invalid", System.nanoTime()));
            doAnswer(call -> { workers.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
            when(api.getLiveRoomConnectInfo(2L)).thenReturn(new ConnectInfo("test", List.of(new ConnectAddress("example.invalid", 80, 443, 80))));
            when(session.isOpen()).thenAnswer(ignored -> open.get());
            doAnswer(ignored -> { open.set(false); return null; }).when(session).close(any(CloseStatus.class));
            when(tasks.schedule(any(), anyLong())).thenReturn(true);
            BilibiliFailureIncidentReporter incidents = mock(BilibiliFailureIncidentReporter.class);
            when(incidents.record(any())).thenReturn(new BilibiliFailureIncidentReporter.Decision(false, false));
            when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class))).thenAnswer(call -> {
                handler = call.getArgument(0); future = new CompletableFuture<>();
                if(autoEstablish) { handler.afterConnectionEstablished(session); future.complete(session); }
                return future;
            });
            connector = new BilibiliLiveRoomConnector(executor, mock(TaskScheduler.class), mock(ApplicationEventPublisher.class),
                    new StarBotBilibiliProperties(), mock(LiveDataService.class), account, tasks, parser, api,
                    networkLog, codec, client, incidents, up);
        }
        void establish() { connector.connect(); workers.remove().run(); }
        Object context() { return ReflectionTestUtils.getField(connector, "activeConnection"); }
    }
}
