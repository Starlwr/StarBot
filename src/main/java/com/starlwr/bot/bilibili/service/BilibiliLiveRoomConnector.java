package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger;
import com.starlwr.bot.bilibili.model.ConnectAddress;
import com.starlwr.bot.bilibili.model.ConnectInfo;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.protocol.DanmakuFrame;
import com.starlwr.bot.bilibili.protocol.DanmakuPacketCodec;
import com.starlwr.bot.bilibili.protocol.BilibiliHeartbeatPayload;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.FixedSizeSetQueue;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.brotli.dec.BrotliInputStream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bilibili 直播间连接器
 */
@Slf4j
@ClientEndpoint
public class BilibiliLiveRoomConnector {
    private final ThreadPoolTaskExecutor executor;

    private final TaskScheduler taskScheduler;

    private final ApplicationEventPublisher eventPublisher;

    private final StarBotBilibiliProperties properties;

    private final LiveDataService liveDataService;

    private final BilibiliAccountService accountService;

    private final BilibiliLiveRoomConnectTaskService taskService;

    private final BilibiliEventParser eventParser;

    private final BilibiliApiUtil bilibili;

    private final BilibiliNetworkLogger networkLog;

    private final DanmakuPacketCodec packetCodec;

    private final StandardWebSocketClient webSocketClient;

    private final BilibiliFailureIncidentReporter incidentReporter;

    private final AtomicInteger addressCursor = new AtomicInteger();

    private final AtomicLong generationSequence = new AtomicLong();

    private final Object lifecycleLock = new Object();

    private volatile ConnectionContext activeConnection;

    private volatile boolean stopping;

    private FutureTask<Void> connectTask;

    @Getter
    private final Up up;

    @Getter
    private volatile ConnectStatus status;

    private final FixedSizeSetQueue<DanmuDTO> latestDanmus = new FixedSizeSetQueue<>(30);

    private final FixedSizeSetQueue<String> latestAckMessages = new FixedSizeSetQueue<>(2000);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class DanmuDTO {
        private long uid;
        private String content;
        private long timestamp;
    }

    public BilibiliLiveRoomConnector(ThreadPoolTaskExecutor executor, TaskScheduler taskScheduler, ApplicationEventPublisher eventPublisher, StarBotBilibiliProperties properties, LiveDataService liveDataService, BilibiliAccountService accountService, BilibiliLiveRoomConnectTaskService taskService, BilibiliEventParser eventParser, BilibiliApiUtil bilibili, BilibiliNetworkLogger networkLog, DanmakuPacketCodec packetCodec, StandardWebSocketClient webSocketClient, BilibiliFailureIncidentReporter incidentReporter, Up up) {
        this.executor = executor;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.liveDataService = liveDataService;
        this.accountService = accountService;
        this.taskService = taskService;
        this.eventParser = eventParser;
        this.bilibili = bilibili;
        this.networkLog = networkLog;
        this.packetCodec = packetCodec;
        this.webSocketClient = webSocketClient;
        this.incidentReporter = incidentReporter;

        this.up = up;

        this.status = ConnectStatus.INIT;
    }

    private enum DisconnectCause { NONE, LOCAL, HEARTBEAT_TIMEOUT, RISK, AUTH, TRANSPORT }

    private static final class ConnectionContext {
        private final long generation;
        private final ConnectInfo connectInfo;
        private final String url;
        private final String host;
        private final int hostIndex;
        private final int hostCount;
        private final Instant attemptStartedAt = Instant.now();
        private final ConnectionReconnectGate reconnectGate = new ConnectionReconnectGate();
        private final AtomicBoolean closeObserved = new AtomicBoolean();
        private final Object sendLock = new Object();
        private final AtomicBoolean heartbeatQueued = new AtomicBoolean();
        private final AtomicBoolean riskQueued = new AtomicBoolean();
        private volatile Instant connectedAt;
        private volatile Instant lastHeartbeatSentAt;
        private volatile Instant lastHeartbeatAckAt = Instant.now();
        private volatile Instant lastRiskCheckAt = Instant.now();
        private volatile WebSocketSession session;
        private volatile ScheduledFuture<?> heartbeatTask;
        private volatile ScheduledFuture<?> riskTask;
        private volatile CompletableFuture<WebSocketSession> connectionFuture;
        private volatile boolean received;
        private volatile DisconnectCause disconnectCause = DisconnectCause.NONE;

        private ConnectionContext(long generation, ConnectInfo connectInfo, String url,
                                  String host, int hostIndex, int hostCount) {
            this.generation = generation;
            this.connectInfo = connectInfo;
            this.url = url;
            this.host = host;
            this.hostIndex = hostIndex;
            this.hostCount = hostCount;
        }
    }

    private record ConnectTarget(ConnectInfo connectInfo, String url, String host, int hostIndex, int hostCount) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BilibiliLiveRoomConnector that = (BilibiliLiveRoomConnector) o;
        return Objects.equals(up, that.up);
    }

    @Override
    public int hashCode() {
        return Objects.hash(up);
    }

    /**
     * 获取直播间连接地址
     * @return 直播间连接地址
     */
    private ConnectTarget getConnectTarget() {
        ConnectInfo connectInfo = bilibili.getLiveRoomConnectInfo(up.getRoomId());
        List<ConnectAddress> addresses = connectInfo.getAddresses();
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalStateException("getDanmuInfo returned no websocket hosts for room " + up.getRoomId());
        }
        int index = Math.floorMod(addressCursor.getAndIncrement(), addresses.size());
        ConnectAddress address = addresses.get(index);
        String url = String.format("wss://%s:%d/sub", address.getHost(), address.getWssPort());
        return new ConnectTarget(connectInfo, url, address.getHost() + ':' + address.getWssPort(), index, addresses.size());
    }

    /**
     * 连接到直播间
     */
    public void connect() {
        FutureTask<Void> task;
        synchronized (lifecycleLock) {
            if (stopping || status == ConnectStatus.CONNECTING || status == ConnectStatus.CONNECTED) return;
            status = ConnectStatus.CONNECTING;
            task = new FutureTask<>(() -> { connectAttempt(); return null; });
            connectTask = task;
        }
        try { executor.execute(task); }
        catch (RejectedExecutionException failure) {
            synchronized (lifecycleLock) {
                task.cancel(false);
                if (connectTask == task) {
                    connectTask = null;
                    if (!stopping) status = ConnectStatus.ERROR;
                }
            }
            throw failure;
        }
    }

    private void connectAttempt() {
        if (stopping) return;
        int interval = properties.getLive().getLiveRoomReconnectInterval();
        ConnectionContext context = null;
        CompletableFuture<WebSocketSession> sessionFuture = null;
        BilibiliNetworkLogger.HttpTrace handshakeTrace = null;
        try {
            ConnectTarget target = getConnectTarget();
            context = new ConnectionContext(generationSequence.incrementAndGet(), target.connectInfo(),
                    target.url(), target.host(), target.hostIndex(), target.hostCount());
            ConnectionContext previous;
            synchronized (lifecycleLock) {
                if (stopping) return;
                previous = activeConnection;
                activeConnection = context;
            }
            if (previous != null) releaseConnection(previous);
            log.info("准备连接到 {} 的直播间 {}: host={}, generation={}, hostIndex={}/{}",
                    up.getUname(), up.getRoomId(), context.host, context.generation,
                    context.hostIndex + 1, context.hostCount);

            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("User-Agent", properties.getNetwork().getUserAgent());
            handshakeTrace = networkLog.httpRequest("bilibili-live-ws-handshake", "GET", context.url,
                    headers.toSingleValueMap(), null);

            BilibiliWebSocketHandler handler = new BilibiliWebSocketHandler(this, context);
            sessionFuture = webSocketClient.execute(handler, headers, URI.create(context.url));
            sessionFuture.whenComplete((ignored, error) -> { if (error != null) handler.latch.countDown(); });
            synchronized (lifecycleLock) {
                if (isUsable(context)) context.connectionFuture = sessionFuture;
                else sessionFuture.cancel(true);
            }

            if (handler.awaitConnection()) {
                sessionFuture.get(3, TimeUnit.SECONDS);
                try { networkLog.httpResponse(handshakeTrace, 101, Collections.emptyMap(), "<websocket-upgrade>"); }
                catch (RuntimeException diagnosticFailure) { log.debug("WebSocket 握手诊断记录失败", diagnosticFailure); }
            } else {
                throw new TimeoutException();
            }
        } catch (Exception e) {
            if (handshakeTrace != null) {
                try { networkLog.httpFailure(handshakeTrace, e); }
                catch (RuntimeException diagnosticFailure) { e.addSuppressed(diagnosticFailure); }
            }
            if (context == null) {
                ConnectInfo empty = new ConnectInfo();
                context = new ConnectionContext(generationSequence.incrementAndGet(), empty,
                        "", "unresolved", -1, 0);
                ConnectionContext previous;
                synchronized (lifecycleLock) {
                    if (stopping) return;
                    previous = activeConnection;
                    activeConnection = context;
                }
                if (previous != null) releaseConnection(previous);
            }
            try {
                synchronized (lifecycleLock) {
                    if (!isUsable(context)) return;
                    status = ConnectStatus.ERROR;
                    requestReconnect(context, interval, "handshake-failure");
                    reportHandshakeFailure(context, e, interval);
                }
            } finally { releaseConnection(context); }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (context != null) context.connectionFuture = null;
        }
    }

    /**
     * 断开连接直播间
     */
    public void disconnect() {
        log.info("准备断开 {} 的直播间 {}", up.getUname(), up.getRoomId());
        ConnectionContext context;
        synchronized (lifecycleLock) {
            if (stopping) return;
            stopping = true;
            status = ConnectStatus.CLOSED;
            context = activeConnection;
            activeConnection = null;
            if (connectTask != null) { connectTask.cancel(true); connectTask = null; }
            if (context != null) context.disconnectCause = DisconnectCause.LOCAL;
        }
        taskService.remove(this);
        if (context != null) releaseConnection(context);
        latestDanmus.clear(); latestAckMessages.clear();

        log.info("已断开连接 {} 的直播间 {}", up.getUname(), up.getRoomId());

        BilibiliDisconnectedEvent event = new BilibiliDisconnectedEvent(up);
        eventPublisher.publishEvent(event);
    }

    void cancelPendingConnection() {
        disconnect();
    }

    /** Detach transport resources before closing; callbacks can arrive synchronously or much later. */
    private void releaseConnection(ConnectionContext context) {
        WebSocketSession session;
        CompletableFuture<WebSocketSession> future;
        synchronized (lifecycleLock) {
            context.closeObserved.set(true);
            stopHeartBeat(context); stopDetectRisk(context);
            session = context.session; context.session = null;
            future = context.connectionFuture; context.connectionFuture = null;
        }
        if (future != null && !future.isDone()) future.cancel(true);
        if (session != null) closeQuietly(session);
    }

    private void finishConnection(ConnectionContext context, CloseStatus closeStatus, Throwable error) {
        try {
            synchronized (lifecycleLock) {
                if (!context.closeObserved.compareAndSet(false, true)) return;
                if (isCurrent(context)) reportConnectionClosed(context, closeStatus, error,
                        properties.getLive().getLiveRoomReconnectInterval());
            }
        } finally { releaseConnection(context); }
    }

    /**
     * 发送认证包
     */
    private void sendVerifyData(ConnectionContext context) {
        Map<String, Object> verifyData = Map.of(
                "uid", accountService.getAccountInfo().getUid(),
                "roomid", up.getRoomId(),
                "protover", 3,
                "buvid", bilibili.getCookies().getBuvid3(),
                "support_ack", true,
                "queue_uuid", randomQueueUuid(),
                "scene", "room",
                "platform", "web",
                "type", 2,
                "key", context.connectInfo.getToken()
        );
        String jsonString = JSON.toJSONString(verifyData);
        byte[] dataBytes = jsonString.getBytes(StandardCharsets.UTF_8);

        send(context, DataHeaderType.HEARTBEAT, DataPackType.VERIFY, dataBytes);
    }

    /**
     * 定时发送心跳包
     */
    private void startHeartBeat(ConnectionContext context) {
        if (!isUsable(context) || context.heartbeatTask != null) {
            return;
        }

        context.heartbeatTask = taskScheduler.scheduleAtFixedRate(() -> submitMaintenance(context, context.heartbeatQueued, () -> {
            if (!isUsable(context) || status != ConnectStatus.CONNECTED) {
                return;
            }

            if (Instant.now().minusSeconds(75).isAfter(context.lastHeartbeatAckAt)) {
                context.disconnectCause = DisconnectCause.HEARTBEAT_TIMEOUT;
                finishConnection(context, CloseStatus.SESSION_NOT_RELIABLE, null);
                return;
            }

            try {
                context.lastHeartbeatSentAt = Instant.now();
                send(context, DataHeaderType.HEARTBEAT, DataPackType.HEARTBEAT,
                        BilibiliHeartbeatPayload.bytes());
            } catch (Exception e) {
                log.error("发送 {} 的直播间 {} 的心跳包异常", up.getUname(), up.getRoomId(), e);
            }
        }), Instant.now().plusSeconds(10), Duration.ofSeconds(30));
    }

    /**
     * 停止定时发送心跳包
     */
    private void stopHeartBeat(ConnectionContext context) {
        if (context.heartbeatTask != null) {
            context.heartbeatTask.cancel(false);
            context.heartbeatTask = null;
        }
    }

    /**
     * 定时检测直播间数据风控
     */
    private void startDetectRisk(ConnectionContext context) {
        if (!properties.getLive().isAutoDetectLiveRoomRisk()) {
            return;
        }

        if (properties.getLive().getAutoDetectLiveRoomRiskRatio() < 1 || properties.getLive().getAutoDetectLiveRoomRiskRatio() > 100) {
            log.warn("直播间数据风控检测阈值配置不正确({}%), 请配置为 1 ~ 100 的数值后重试", properties.getLive().getAutoDetectLiveRoomRiskRatio());
            return;
        }

        if (!isUsable(context) || context.riskTask != null) {
            return;
        }

        int interval = properties.getLive().getAutoDetectLiveRoomRiskInterval();

        context.riskTask = taskScheduler.scheduleAtFixedRate(() -> submitMaintenance(context, context.riskQueued, () -> {
            if (!isUsable(context) || status != ConnectStatus.CONNECTED) {
                return;
            }

            Optional<Boolean> optionalLiveStatus = liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), up.getUid());
            if (optionalLiveStatus.isEmpty() || !optionalLiveStatus.get()) {
                return;
            }

            List<DanmuDTO> apiDanmus;
            try {
                apiDanmus = bilibili.getLiveRoomLatestDanmus(up.getRoomId())
                        .stream()
                        .filter(danmu -> danmu.getTimestamp().isAfter(context.lastRiskCheckAt))
                        .map(danmu -> new DanmuDTO(danmu.getSender().getUid(), danmu.getContent(), danmu.getTimestamp().getEpochSecond()))
                        .toList();
            } catch (Exception e) {
                log.error("直播间风控检测获取直播间 {} 最新弹幕失败, 偶然出现此异常可忽略", up.getRoomId(), e);
                return;
            }

            if (!isUsable(context) || apiDanmus.size() < 4) {
                return;
            }

            context.lastRiskCheckAt = Instant.now();

            long receivedCount = apiDanmus.stream().filter(latestDanmus::contains).count();
            double ratio = (double) receivedCount / apiDanmus.size() * 100;
            if (ratio < properties.getLive().getAutoDetectLiveRoomRiskRatio()) {
                log.debug("{} 的直播间 {} 数据抓取比例: {}%, 已达到风控阈值, 房间最新弹幕: {}", up.getUname(), up.getRoomId(), Math.round(ratio), apiDanmus);

                context.disconnectCause = DisconnectCause.RISK;
                finishConnection(context, CloseStatus.NORMAL, null);
            }
        }), Instant.now().plusSeconds(interval), Duration.ofSeconds(interval));
    }

    /**
     * 停止定时检测直播间数据风控
     */
    private void stopDetectRisk(ConnectionContext context) {
        if (context.riskTask != null) {
            context.riskTask.cancel(false);
            context.riskTask = null;
        }
    }

    /** Bound per-connection timer work without changing the shared worker pool. */
    private void submitMaintenance(ConnectionContext context, AtomicBoolean queued, Runnable action) {
        if (!isUsable(context) || !queued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { if (isUsable(context) && status == ConnectStatus.CONNECTED) action.run(); }
                finally { queued.set(false); }
            });
        } catch (RejectedExecutionException failure) {
            queued.set(false);
            finishConnection(context, CloseStatus.SERVER_ERROR, failure);
        }
    }

    /**
     * 发送 Websocket 数据
     * @param headerType 数据头类型
     * @param packType 数据包类型
     * @param data 数据
     */
    private void send(ConnectionContext context, DataHeaderType headerType, DataPackType packType, byte[] data) {
        if (!isUsable(context) || context.session == null) return;
        byte[] packedData = packetCodec.encode(packType.getCode(), data, headerType.getCode(), 1);
        networkLog.websocketOutLazy("bilibili-live", up.getRoomId(),
                packType.name() + "/protocol-" + headerType.getCode(), packedData.length,
                () -> Map.of("decoded", new String(data, StandardCharsets.UTF_8),
                        "frameBase64", Base64.getEncoder().encodeToString(packedData)),
                packType == DataPackType.HEARTBEAT);
        try {
            sendBinary(context, packedData);
        } catch (IOException | IllegalStateException e) {
            finishConnection(context, CloseStatus.SERVER_ERROR, e);
            log.error("发送 {} 的直播间 {} 的 Websocket 消息异常", up.getUname(), up.getRoomId(), e);
        }
    }

    private void sendOperation(ConnectionContext context, int operation, byte[] data) {
        if (!isUsable(context) || context.session == null) return;
        byte[] packedData = packetCodec.encode(operation, data, 1, 1);
        networkLog.websocketOutLazy("bilibili-live", up.getRoomId(), "OP-" + operation,
                packedData.length, () -> Map.of("decoded", new String(data, StandardCharsets.UTF_8),
                        "frameBase64", Base64.getEncoder().encodeToString(packedData)), false);
        try {
            sendBinary(context, packedData);
        } catch (IOException | IllegalStateException e) {
            finishConnection(context, CloseStatus.SERVER_ERROR, e);
            log.error("发送 {} 的直播间 {} WebSocket op={} 消息异常", up.getUname(), up.getRoomId(), operation, e);
        }
    }

    private String randomQueueUuid() {
        String value = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        return value.length() >= 8 ? value.substring(value.length() - 8) : "0".repeat(8 - value.length()) + value;
    }

    private void sendBinary(ConnectionContext context, byte[] payload) throws IOException {
        WebSocketSession session = context.session;
        if (!isUsable(context) || session == null || !session.isOpen()) return;
        synchronized (context.sendLock) {
            if (!isUsable(context) || context.session != session || !session.isOpen()) return;
            session.sendMessage(new BinaryMessage(payload));
        }
    }

    /** Returns false when a p_msg_type=1 duplicate must not be dispatched again. */
    private boolean acknowledgeMessage(JSONObject data, ConnectionContext context) {
        if (data == null || !data.getBooleanValue("p_is_ack")) return true;
        String messageId = data.getString("msg_id");
        String command = data.getString("cmd");
        if (messageId == null || messageId.isBlank() || command == null || command.isBlank()) return true;
        int messageType = data.getIntValue("p_msg_type", 0);
        if (messageType == 1 && latestAckMessages.contains(messageId)) return false;
        if (messageType == 1) latestAckMessages.add(messageId);
        JSONObject ack = new JSONObject();
        ack.put("msg_id", messageId);
        ack.put("cmd", command);
        ack.put("p_msg_type", messageType);
        sendOperation(context, 24, ack.toJSONString().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private boolean isCurrent(ConnectionContext context) {
        return context != null && activeConnection == context;
    }

    private boolean isUsable(ConnectionContext context) {
        return isCurrent(context) && !stopping && !context.closeObserved.get();
    }

    private long activeGeneration() {
        ConnectionContext context = activeConnection;
        return context == null ? 0 : context.generation;
    }

    private void logCloseFrame(ConnectionContext context, CloseStatus closeStatus) {
        Map<String, Object> details = closeLogDetails(closeStatus, context.host, context.generation,
                uptimeMillis(context), context.lastHeartbeatAckAt, heartbeatAckAgeMillis(context));
        try {
            networkLog.websocketIn("bilibili-live", up.getRoomId(), "CLOSE", 0, details, false);
        } catch (RuntimeException error) {
            log.debug("记录直播间 WebSocket 关闭诊断失败，不影响连接生命周期: room={}, generation={}",
                    up.getRoomId(), context.generation, error);
        }
    }

    static Map<String, Object> closeLogDetails(CloseStatus closeStatus, String host, long generation,
                                                long uptimeMs, Instant lastHeartbeatAckAt,
                                                long lastHeartbeatAckAgeMs) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", closeStatus.getCode());
        details.put("reason", closeStatus.getReason());
        details.put("host", host);
        details.put("generation", generation);
        details.put("uptimeMs", uptimeMs);
        details.put("lastHeartbeatAckAt", lastHeartbeatAckAt);
        details.put("lastHeartbeatAckAgeMs", lastHeartbeatAckAgeMs);
        return details;
    }

    private void requestReconnect(ConnectionContext context, long delayMillis, String reason) {
        if (!isCurrent(context) || stopping || !context.reconnectGate.trySchedule()) return;
        boolean scheduled = taskService.schedule(this, delayMillis);
        if (scheduled) {
            log.info("已安排直播间重连: room={}, host={}, generation={}, delayMs={}, reason={}",
                    up.getRoomId(), context.host, context.generation, delayMillis, reason);
        } else {
            log.debug("直播间重连任务已存在或调度器已关闭: room={}, generation={}, reason={}",
                    up.getRoomId(), context.generation, reason);
        }
    }

    private void reportHandshakeFailure(ConnectionContext context, Throwable error, int reconnectInterval) {
        BilibiliFailureIncidentReporter.Decision decision = incidentReporter.record(
                observation(BilibiliFailureIncidentReporter.Category.WS_HANDSHAKE_FAILURE, context, error));
        if (!decision.suppressWarning()) {
            log.warn("直播间 WebSocket 握手失败: room={}, host={}, generation={}, durationMs={}, retryInMs={}, reason={}",
                    up.getRoomId(), context.host, context.generation, uptimeMillis(context), reconnectInterval,
                    BilibiliFailureIncidentReporter.rootCause(error));
        }
        logFailureDetail("WebSocket 握手失败", context, error, decision.includeStack());
    }

    private void reportConnectionClosed(ConnectionContext context, CloseStatus closeStatus,
                                        Throwable error, int reconnectInterval) {
        if (!isCurrent(context)) return;
        long uptime = uptimeMillis(context);
        long ackAge = heartbeatAckAgeMillis(context);
        if (stopping || context.disconnectCause == DisconnectCause.LOCAL) {
            status = ConnectStatus.CLOSED;
            log.info("直播间 WebSocket 已正常关闭: room={}, host={}, generation={}, closeCode={}, uptimeMs={}, lastHeartbeatAckAgeMs={}",
                    up.getRoomId(), context.host, context.generation, closeStatus.getCode(), uptime, ackAge);
            return;
        }

        if (context.disconnectCause == DisconnectCause.AUTH) {
            status = ConnectStatus.ERROR;
            log.warn("直播间 WebSocket 因认证状态关闭: room={}, host={}, generation={}, closeCode={}, uptimeMs={}, retryInMs={}",
                    up.getRoomId(), context.host, context.generation, closeStatus.getCode(), uptime, reconnectInterval);
            requestReconnect(context, reconnectInterval, "authentication");
            return;
        }
        if (context.disconnectCause == DisconnectCause.RISK) {
            status = ConnectStatus.RISK;
            log.warn("直播间 WebSocket 因数据完整性检测关闭: room={}, host={}, generation={}, uptimeMs={}, retryInMs={}",
                    up.getRoomId(), context.host, context.generation, uptime, reconnectInterval);
            requestReconnect(context, reconnectInterval, "risk-detection");
            return;
        }

        boolean remoteNormal = closeStatus.getCode() == CloseStatus.NORMAL.getCode()
                || closeStatus.getCode() == CloseStatus.GOING_AWAY.getCode();
        if (remoteNormal && context.disconnectCause != DisconnectCause.HEARTBEAT_TIMEOUT) {
            status = ConnectStatus.CLOSED;
            log.info("直播间 WebSocket 被远端正常关闭，将重新连接: room={}, host={}, generation={}, closeCode={}, "
                            + "reason={}, uptimeMs={}, lastHeartbeatAckAgeMs={}, retryInMs={}",
                    up.getRoomId(), context.host, context.generation, closeStatus.getCode(), closeStatus.getReason(),
                    uptime, ackAge, reconnectInterval);
            requestReconnect(context, reconnectInterval, "remote-normal-close");
            return;
        }

        BilibiliFailureIncidentReporter.Category category;
        if (context.disconnectCause == DisconnectCause.HEARTBEAT_TIMEOUT) {
            category = BilibiliFailureIncidentReporter.Category.WS_HEARTBEAT_TIMEOUT;
            status = ConnectStatus.TIMEOUT;
        } else if (BilibiliFailureIncidentReporter.isTlsAbnormalClose(closeStatus.getCode(), error)) {
            category = BilibiliFailureIncidentReporter.Category.WS_TLS_ABNORMAL_CLOSE;
            status = ConnectStatus.ERROR;
        } else {
            category = BilibiliFailureIncidentReporter.Category.WS_ABNORMAL_CLOSE;
            status = ConnectStatus.ERROR;
        }
        requestReconnect(context, reconnectInterval, category.name());
        BilibiliFailureIncidentReporter.Decision decision = incidentReporter.record(observation(category, context, error));
        if (!decision.suppressWarning()) {
            log.warn("直播间 WebSocket 异常关闭: category={}, room={}, host={}, generation={}, closeCode={}, reason={}, "
                            + "uptimeMs={}, lastHeartbeatAckAt={}, lastHeartbeatAckAgeMs={}, received={}, retryInMs={}, rootCause={}",
                    category, up.getRoomId(), context.host, context.generation, closeStatus.getCode(), closeStatus.getReason(),
                    uptime, context.lastHeartbeatAckAt, ackAge, context.received, reconnectInterval,
                    BilibiliFailureIncidentReporter.rootCause(error));
        }
        logFailureDetail("WebSocket 异常关闭 " + category, context, error, decision.includeStack());
    }

    private BilibiliFailureIncidentReporter.Observation observation(
            BilibiliFailureIncidentReporter.Category category, ConnectionContext context, Throwable error) {
        return new BilibiliFailureIncidentReporter.Observation(category, up.getRoomId(), context.host,
                context.generation, uptimeMillis(context), heartbeatAckAgeMillis(context), error);
    }

    private void logFailureDetail(String label, ConnectionContext context, Throwable error, boolean includeStack) {
        if (includeStack && error != null) {
            log.debug("{}详情: room={}, host={}, generation={}", label, up.getRoomId(), context.host,
                    context.generation, error);
        } else {
            log.debug("{}: room={}, host={}, generation={}, uptimeMs={}, lastHeartbeatAckAt={}, "
                            + "lastHeartbeatAckAgeMs={}, error={}",
                    label, up.getRoomId(), context.host, context.generation, uptimeMillis(context),
                    context.lastHeartbeatAckAt, heartbeatAckAgeMillis(context), error == null ? "none" : error.toString());
        }
    }

    private long uptimeMillis(ConnectionContext context) {
        Instant started = context.connectedAt == null ? context.attemptStartedAt : context.connectedAt;
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }

    private long heartbeatAckAgeMillis(ConnectionContext context) {
        return Math.max(0, Duration.between(context.lastHeartbeatAckAt, Instant.now()).toMillis());
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) session.close(CloseStatus.NORMAL);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * 打包数据
     * @param headerType 数据头类型
     * @param packType 数据包类型
     * @param data 数据
     * @return 打包后的数据
     */
    private byte[] pack(DataHeaderType headerType, DataPackType packType, byte[] data) {
        if (headerType != DataHeaderType.RAW_JSON && headerType != DataHeaderType.HEARTBEAT) {
            throw new IllegalArgumentException("不支持的数据包协议版本: " + headerType);
        }
        if (packType != DataPackType.HEARTBEAT && packType != DataPackType.VERIFY) {
            throw new IllegalArgumentException("不支持的数据包类型: " + packType);
        }

        int totalLength = data.length + 16;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(totalLength);
        buffer.putShort((short) 16);
        buffer.putShort((short) headerType.getCode());
        buffer.putInt(packType.getCode());
        buffer.putInt(1);
        buffer.put(data);

        return buffer.array();
    }

    /**
     * 解包数据
     * @param data 原始数据
     * @return 解包后的数据
     */
    private List<JSONObject> unPack(byte[] data) {
        List<JSONObject> result = new ArrayList<>();
        for (DanmakuFrame frame : packetCodec.decode(data)) {
            JSONObject receiveData = new JSONObject();
            receiveData.put("protocol_version", frame.getVersion());
            receiveData.put("datapack_type", frame.getOperation());
            receiveData.put("sequence", frame.getSequence());
            receiveData.put("outer_sequence", frame.getOuterSequence());
            if (frame.getPopularity() != null) receiveData.put("data", new JSONObject().fluentPut("view", frame.getPopularity()));
            else if (frame.getJson() != null) receiveData.put("data", frame.getJson());
            else receiveData.put("data", new JSONObject());
            result.add(receiveData);
        }
        return result;
    }

    /**
     * WebSocket 处理器
     */
    private static class BilibiliWebSocketHandler implements WebSocketHandler {
        private final BilibiliLiveRoomConnector connector;

        private final ConnectionContext context;

        private final ThreadPoolTaskExecutor executor;

        private final Up up;

        private final CountDownLatch latch = new CountDownLatch(1);

        private volatile boolean connectTimeout;

        private BilibiliWebSocketHandler(BilibiliLiveRoomConnector connector, ConnectionContext context) {
            this.connector = connector;
            this.context = context;
            this.executor = connector.executor;
            this.up = connector.up;
        }

        /**
         * 等待 WebSocket 连接成功
         * @return 连接是否成功
         */
        public boolean awaitConnection() {
            try {
                if (latch.await(3, TimeUnit.SECONDS)) return !connectTimeout;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (connector.lifecycleLock) {
                if (context.session != null && connector.isUsable(context)) return true;
                connectTimeout = true;
                return false;
            }
        }

        /**
         * 连接建立
         * @param session WebSocket 会话
         */
        @Override
        public void afterConnectionEstablished(@NonNull WebSocketSession session) {
            boolean accepted;
            synchronized (connector.lifecycleLock) {
                accepted = !connectTimeout && connector.isUsable(context);
                if (accepted) {
                    context.session = session;
                    context.connectedAt = Instant.now();
                    context.lastHeartbeatAckAt = context.connectedAt;
                }
            }
            latch.countDown();
            if (!accepted) { closeQuietly(session); return; }
            // The queued task captures only context; closing immediately clears context.session.
            try { executor.execute(() -> {
                if (!connector.isUsable(context)) return;
                log.info("与 {} 的直播间 {} 的 WebSocket 连接成功, 开始发送认证数据: host={}, generation={}",
                        up.getUname(), up.getRoomId(), context.host, context.generation);
                try {
                    connector.sendVerifyData(context);
                } catch (Exception e) {
                    log.error("发送 {} 的直播间 {} 的认证数据异常", up.getUname(), up.getRoomId(), e);
                    connector.finishConnection(context, CloseStatus.SERVER_ERROR, e);
                }
            }); } catch (RejectedExecutionException failure) {
                connector.finishConnection(context, CloseStatus.SERVER_ERROR, failure);
            }
        }

        /**
         * 消息处理
         * @param session WebSocket 会话
         * @param rawMessage WebSocket 消息
         */
        @Override
        public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> rawMessage) {
            if (!connector.isUsable(context)) return;
            // Copy while the transport owns the callback, before its buffer can be reused or retained in a queue.
            final byte[] payload;
            final String text;
            if (rawMessage instanceof BinaryMessage message) {
                ByteBuffer buffer = message.getPayload().slice();
                payload = new byte[buffer.remaining()]; buffer.get(payload); text = null;
            } else if (rawMessage instanceof TextMessage message) {
                payload = null; text = message.getPayload();
            } else return;
            try { executor.execute(() -> {
                if (!connector.isUsable(context)) {
                    log.debug("忽略旧代直播间 WebSocket 消息: room={}, generation={}, activeGeneration={}",
                            up.getRoomId(), context.generation, connector.activeGeneration());
                    return;
                }
                try {
                    context.received = true;
                    if (payload != null) {
                        List<JSONObject> unpackedDatas = connector.unPack(payload);
                        boolean heartbeatFrame = !unpackedDatas.isEmpty() && unpackedDatas.stream()
                                .allMatch(item -> item.getIntValue("datapack_type") == DataPackType.HEARTBEAT_RESPONSE.getCode());
                        connector.networkLog.websocketInLazy("bilibili-live", up.getRoomId(), "BINARY-FRAME",
                                payload.length, () -> Map.of("frameBase64", Base64.getEncoder().encodeToString(payload)),
                                heartbeatFrame);
                        unpackedDatas.stream().map(item -> item.getLongValue("outer_sequence"))
                                .filter(sequence -> sequence > 1).distinct()
                                .forEach(connector.bilibili::acknowledgeLiveRoomSequence);
                        for (JSONObject unpackedData: unpackedDatas) {
                            if (!connector.isUsable(context)) return;
                            int dataPackType = unpackedData.getIntValue("datapack_type");
                            String kind = Arrays.stream(DataPackType.values())
                                    .filter(type -> type.getCode() == dataPackType)
                                    .map(Enum::name)
                                    .findFirst()
                                    .orElse("TYPE-" + dataPackType);
                            int protocolVersion = unpackedData.getIntValue("protocol_version");
                            connector.networkLog.websocketIn("bilibili-live", up.getRoomId(),
                                    kind + "/protocol-" + protocolVersion, payload.length, unpackedData,
                                    dataPackType == DataPackType.HEARTBEAT_RESPONSE.getCode());
                            JSONObject data = unpackedData.getJSONObject("data");

                            if (dataPackType == DataPackType.NOTICE.getCode()) {
                                if (!connector.acknowledgeMessage(data, context)) {
                                    continue;
                                }
                                for (StarBotBaseLiveEvent event : connector.eventParser.parseMany(data, up)) {

                                    if (connector.properties.getLive().isAutoDetectLiveRoomRisk()) {
                                        if (event instanceof BilibiliDanmuEvent danmuEvent) {
                                            connector.latestDanmus.add(new DanmuDTO(danmuEvent.getSender().getUid(), danmuEvent.getContent(), danmuEvent.getTimestamp() / 1000));
                                        } else if (event instanceof BilibiliEmojiEvent emojiEvent) {
                                            connector.latestDanmus.add(new DanmuDTO(emojiEvent.getSender().getUid(), emojiEvent.getEmoji().getName(), emojiEvent.getTimestamp() / 1000));
                                        }
                                    }

                                    if (event instanceof BilibiliLiveOnEvent liveOnEvent) {
                                        synchronized (BilibiliBackupLivePushService.class) {
                                            Optional<Boolean> optionalLastLiveStatus = connector.liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), up.getUid());
                                            if (optionalLastLiveStatus.isEmpty()) {
                                                log.error("直播推送未获取到历史直播状态信息, 请向开发者反馈该问题");
                                                connector.liveDataService.setLiveStatus(LivePlatform.BILIBILI.getName(), up.getUid(), true);
                                                connector.liveDataService.setLiveStartTime(LivePlatform.BILIBILI.getName(), up.getUid(), liveOnEvent.getTimestamp());
                                            } else {
                                                if (!optionalLastLiveStatus.get()) {
                                                    connector.eventPublisher.publishEvent(event);
                                                }
                                            }
                                        }
                                    } else if (event instanceof BilibiliLiveOffEvent liveOffEvent) {
                                        synchronized (BilibiliBackupLivePushService.class) {
                                            Optional<Boolean> optionalLastLiveStatus = connector.liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), up.getUid());
                                            if (optionalLastLiveStatus.isEmpty()) {
                                                log.error("直播推送未获取到历史直播状态信息, 请向开发者反馈该问题");
                                                connector.liveDataService.setLiveStatus(LivePlatform.BILIBILI.getName(), up.getUid(), false);
                                                connector.liveDataService.setLiveEndTime(LivePlatform.BILIBILI.getName(), up.getUid(), liveOffEvent.getTimestamp());
                                            } else {
                                                if (optionalLastLiveStatus.get()) {
                                                    connector.eventPublisher.publishEvent(event);
                                                }
                                            }
                                        }
                                    } else {
                                        connector.eventPublisher.publishEvent(event);
                                    }
                                }
                            } else if (dataPackType == DataPackType.HEARTBEAT_RESPONSE.getCode()) {
                                context.lastHeartbeatAckAt = Instant.now();
                            } else if (dataPackType == DataPackType.VERIFY_SUCCESS_RESPONSE.getCode()) {
                                int code = data == null || data.isEmpty() ? 0 : data.getIntValue("code", 0);
                                if (code == -101) {
                                    context.disconnectCause = DisconnectCause.AUTH;
                                    log.warn("{} 的直播间 {} danmu token 已失效，将重新获取 getDanmuInfo", up.getUname(), up.getRoomId());
                                    connector.finishConnection(context, CloseStatus.NORMAL, null);
                                    continue;
                                }
                                if (code != 0) {
                                    context.disconnectCause = DisconnectCause.AUTH;
                                    log.warn("{} 的直播间 {} WebSocket 认证失败: code={}, data={}", up.getUname(), up.getRoomId(), code, data);
                                    connector.finishConnection(context, CloseStatus.NORMAL, null);
                                    continue;
                                }
                                synchronized (connector.lifecycleLock) {
                                    if (!connector.isUsable(context)) return;
                                    connector.status = ConnectStatus.CONNECTED;
                                    context.lastHeartbeatAckAt = Instant.now();
                                    connector.startHeartBeat(context);
                                    context.lastRiskCheckAt = Instant.now();
                                    connector.latestDanmus.clear();
                                    connector.startDetectRisk(context);
                                }
                                log.info("已成功连接到 {} 的直播间 {}: host={}, generation={}",
                                        up.getUname(), up.getRoomId(), context.host, context.generation);

                                BilibiliConnectedEvent event = new BilibiliConnectedEvent(up);
                                connector.eventPublisher.publishEvent(event);
                            } else {
                                log.warn("收到 {} 的直播间 {} 的未知类型({})消息: {}", up.getUname(), up.getRoomId(), dataPackType, data.toJSONString());
                            }
                        }
                    } else {
                        connector.networkLog.websocketIn("bilibili-live", up.getRoomId(), "TEXT",
                                text.length(), text, false);
                    }
                } catch (Exception e) {
                    log.error("处理 {} 的直播间 {} 的 WebSocket 消息异常", up.getUname(), up.getRoomId(), e);
                }
            }); } catch (RejectedExecutionException failure) {
                connector.finishConnection(context, CloseStatus.SERVER_ERROR, failure);
            }
        }

        /**
         * 传输错误
         * @param session WebSocket 会话
         * @param exception 异常
         */
        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            if (!connector.isCurrent(context)) {
                log.debug("忽略旧代直播间传输异常: room={}, generation={}, activeGeneration={}",
                        up.getRoomId(), context.generation, connector.activeGeneration(), exception);
                return;
            }
            context.disconnectCause = connector.stopping ? DisconnectCause.LOCAL : DisconnectCause.TRANSPORT;
            boolean bound = context.session == session;
            connector.finishConnection(context, CloseStatus.NO_CLOSE_FRAME, exception);
            if (!bound) closeQuietly(session);
        }

        /**
         * 连接关闭
         * @param session WebSocket 会话
         * @param closeStatus 关闭状态
         */
        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
            connector.logCloseFrame(context, closeStatus);
            if (!connector.isCurrent(context)) {
                log.debug("忽略旧代直播间关闭回调: room={}, generation={}, activeGeneration={}, closeCode={}",
                        up.getRoomId(), context.generation, connector.activeGeneration(), closeStatus.getCode());
                return;
            }
            connector.finishConnection(context, closeStatus, null);
        }

        /**
         * 是否支持部分消息
         * @return 是否支持部分消息
         */
        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
