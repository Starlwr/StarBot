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
        private volatile Instant connectedAt;
        private volatile Instant lastHeartbeatSentAt;
        private volatile Instant lastHeartbeatAckAt = Instant.now();
        private volatile Instant lastRiskCheckAt = Instant.now();
        private volatile WebSocketSession session;
        private volatile ScheduledFuture<?> heartbeatTask;
        private volatile ScheduledFuture<?> riskTask;
        private volatile Throwable transportError;
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
        executor.submit(() -> {
            synchronized (lifecycleLock) {
                if (stopping || status == ConnectStatus.CLOSING) {
                    status = ConnectStatus.CLOSED;
                    return;
                }
                if (status == ConnectStatus.CONNECTING || status == ConnectStatus.CONNECTED) {
                    log.debug("忽略重复直播间连接请求: room={}, status={}, generation={}", up.getRoomId(), status,
                            activeConnection == null ? 0 : activeConnection.generation);
                    return;
                }
                status = ConnectStatus.CONNECTING;
            }

            int interval = properties.getLive().getLiveRoomReconnectInterval();
            ConnectionContext context = null;
            CompletableFuture<WebSocketSession> sessionFuture = null;
            BilibiliNetworkLogger.HttpTrace handshakeTrace = null;
            try {
                ConnectTarget target = getConnectTarget();
                context = new ConnectionContext(generationSequence.incrementAndGet(), target.connectInfo(),
                        target.url(), target.host(), target.hostIndex(), target.hostCount());
                synchronized (lifecycleLock) {
                    if (stopping) return;
                    activeConnection = context;
                }
                log.info("准备连接到 {} 的直播间 {}: host={}, generation={}, hostIndex={}/{}",
                        up.getUname(), up.getRoomId(), context.host, context.generation,
                        context.hostIndex + 1, context.hostCount);

                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                headers.add("User-Agent", properties.getNetwork().getUserAgent());
                handshakeTrace = networkLog.httpRequest("bilibili-live-ws-handshake", "GET", context.url,
                        headers.toSingleValueMap(), null);

                BilibiliWebSocketHandler handler = new BilibiliWebSocketHandler(this, context);
                sessionFuture = webSocketClient.execute(handler, headers, URI.create(context.url));

                if (handler.awaitConnection()) {
                    sessionFuture.get();
                    networkLog.httpResponse(handshakeTrace, 101, Collections.emptyMap(), "<websocket-upgrade>");
                } else {
                    throw new TimeoutException();
                }
            } catch (Exception e) {
                if (handshakeTrace != null) {
                    networkLog.httpFailure(handshakeTrace, e);
                }
                if (sessionFuture != null && e instanceof TimeoutException) sessionFuture.cancel(true);
                if (context == null) {
                    ConnectInfo empty = new ConnectInfo();
                    context = new ConnectionContext(generationSequence.incrementAndGet(), empty,
                            "", "unresolved", -1, 0);
                    activeConnection = context;
                }
                if (!isCurrent(context) || stopping) return;
                status = ConnectStatus.ERROR;
                reportHandshakeFailure(context, e, interval);
                requestReconnect(context, interval, "handshake-failure");
            }
        });
    }

    /**
     * 断开连接直播间
     */
    public void disconnect() {
        log.info("准备断开 {} 的直播间 {}", up.getUname(), up.getRoomId());
        stopping = true;
        status = ConnectStatus.CLOSING;
        ConnectionContext context = activeConnection;
        if (context != null) {
            context.disconnectCause = DisconnectCause.LOCAL;
            stopHeartBeat(context);
            stopDetectRisk(context);
        }

        if (context != null && context.session != null) {
            try {
                context.session.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.error("断开 {} 的直播间 {} 的 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
            }
        }

        log.info("已断开连接 {} 的直播间 {}", up.getUname(), up.getRoomId());

        BilibiliDisconnectedEvent event = new BilibiliDisconnectedEvent(up);
        eventPublisher.publishEvent(event);
    }

    void cancelPendingConnection() {
        stopping = true;
        status = ConnectStatus.CLOSED;
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
        if (!isCurrent(context) || context.heartbeatTask != null) {
            return;
        }

        context.heartbeatTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (!isCurrent(context) || status != ConnectStatus.CONNECTED) {
                return;
            }

            if (Instant.now().minusSeconds(75).isAfter(context.lastHeartbeatAckAt)) {
                status = ConnectStatus.TIMEOUT;
                context.disconnectCause = DisconnectCause.HEARTBEAT_TIMEOUT;

                try {
                    if (context.session != null) context.session.close();
                } catch (Exception e) {
                    log.error("断开 {} 的直播间 {} 的 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
                }

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

        if (!isCurrent(context) || context.riskTask != null) {
            return;
        }

        int interval = properties.getLive().getAutoDetectLiveRoomRiskInterval();

        context.riskTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (!isCurrent(context) || status != ConnectStatus.CONNECTED) {
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

            if (apiDanmus.size() < 4) {
                return;
            }

            context.lastRiskCheckAt = Instant.now();

            long receivedCount = apiDanmus.stream().filter(latestDanmus::contains).count();
            double ratio = (double) receivedCount / apiDanmus.size() * 100;
            if (ratio < properties.getLive().getAutoDetectLiveRoomRiskRatio()) {
                log.debug("{} 的直播间 {} 数据抓取比例: {}%, 已达到风控阈值, 房间最新弹幕: {}", up.getUname(), up.getRoomId(), Math.round(ratio), apiDanmus);

                status = ConnectStatus.RISK;
                context.disconnectCause = DisconnectCause.RISK;

                try {
                    if (context.session != null) context.session.close();
                } catch (Exception e) {
                    log.error("断开 {} 的直播间 {} 的 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
                }
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

    /**
     * 发送 Websocket 数据
     * @param headerType 数据头类型
     * @param packType 数据包类型
     * @param data 数据
     */
    private void send(ConnectionContext context, DataHeaderType headerType, DataPackType packType, byte[] data) {
        if (!isCurrent(context) || context.session == null) return;
        byte[] packedData = packetCodec.encode(packType.getCode(), data, headerType.getCode(), 1);
        networkLog.websocketOut("bilibili-live", up.getRoomId(),
                packType.name() + "/protocol-" + headerType.getCode(), packedData.length,
                Map.of("decoded", new String(data, StandardCharsets.UTF_8),
                        "frameBase64", Base64.getEncoder().encodeToString(packedData)),
                packType == DataPackType.HEARTBEAT);
        try {
            context.session.sendMessage(new BinaryMessage(packedData));
        } catch (IOException e) {
            log.error("发送 {} 的直播间 {} 的 Websocket 消息异常", up.getUname(), up.getRoomId(), e);
        }
    }

    private void sendOperation(ConnectionContext context, int operation, byte[] data) {
        if (!isCurrent(context) || context.session == null) return;
        byte[] packedData = packetCodec.encode(operation, data, 1, 1);
        networkLog.websocketOut("bilibili-live", up.getRoomId(), "OP-" + operation,
                packedData.length, Map.of("decoded", new String(data, StandardCharsets.UTF_8),
                        "frameBase64", Base64.getEncoder().encodeToString(packedData)), false);
        try {
            context.session.sendMessage(new BinaryMessage(packedData));
        } catch (IOException e) {
            log.error("发送 {} 的直播间 {} WebSocket op={} 消息异常", up.getUname(), up.getRoomId(), operation, e);
        }
    }

    private String randomQueueUuid() {
        String value = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        return value.length() >= 8 ? value.substring(value.length() - 8) : "0".repeat(8 - value.length()) + value;
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

    private long activeGeneration() {
        ConnectionContext context = activeConnection;
        return context == null ? 0 : context.generation;
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
        BilibiliFailureIncidentReporter.Decision decision = incidentReporter.record(observation(category, context, error));
        if (!decision.suppressWarning()) {
            log.warn("直播间 WebSocket 异常关闭: category={}, room={}, host={}, generation={}, closeCode={}, reason={}, "
                            + "uptimeMs={}, lastHeartbeatAckAt={}, lastHeartbeatAckAgeMs={}, received={}, retryInMs={}, rootCause={}",
                    category, up.getRoomId(), context.host, context.generation, closeStatus.getCode(), closeStatus.getReason(),
                    uptime, context.lastHeartbeatAckAt, ackAge, context.received, reconnectInterval,
                    BilibiliFailureIncidentReporter.rootCause(error));
        }
        logFailureDetail("WebSocket 异常关闭 " + category, context, error, decision.includeStack());
        requestReconnect(context, reconnectInterval, category.name().toLowerCase(Locale.ROOT));
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
            session.close(CloseStatus.NORMAL);
        } catch (IOException ignored) {
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

        private final int interval;

        private final CountDownLatch latch = new CountDownLatch(1);

        private boolean connectTimeout = false;

        private BilibiliWebSocketHandler(BilibiliLiveRoomConnector connector, ConnectionContext context) {
            this.connector = connector;
            this.context = context;
            this.executor = connector.executor;
            this.up = connector.up;
            this.interval = connector.properties.getLive().getLiveRoomReconnectInterval();
        }

        /**
         * 等待 WebSocket 连接成功
         * @return 连接是否成功
         */
        public boolean awaitConnection() {
            synchronized (this) {
                try {
                    if (latch.await(3, TimeUnit.SECONDS)) {
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

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
            latch.countDown();

            synchronized (this) {
                if (connectTimeout) {
                    try {
                        session.close();
                    } catch (Exception e) {
                        log.error("断开 {} 的直播间 {} 的超时 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
                    }
                    return;
                }
            }

            executor.submit(() -> {
                if (!connector.isCurrent(context)) {
                    closeQuietly(session);
                    return;
                }
                context.session = session;
                context.connectedAt = Instant.now();
                context.lastHeartbeatAckAt = context.connectedAt;
                log.info("与 {} 的直播间 {} 的 WebSocket 连接成功, 开始发送认证数据: host={}, generation={}",
                        up.getUname(), up.getRoomId(), context.host, context.generation);
                try {
                    connector.sendVerifyData(context);
                } catch (Exception e) {
                    log.error("发送 {} 的直播间 {} 的认证数据异常", up.getUname(), up.getRoomId(), e);
                }
            });
        }

        /**
         * 消息处理
         * @param session WebSocket 会话
         * @param rawMessage WebSocket 消息
         */
        @Override
        public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> rawMessage) {
            executor.submit(() -> {
                if (!connector.isCurrent(context)) {
                    log.debug("忽略旧代直播间 WebSocket 消息: room={}, generation={}, activeGeneration={}",
                            up.getRoomId(), context.generation, connector.activeGeneration());
                    return;
                }
                try {
                    context.received = true;
                    if (rawMessage instanceof BinaryMessage message) {
                        ByteBuffer payloadBuffer = message.getPayload().slice();
                        byte[] payload = new byte[payloadBuffer.remaining()];
                        payloadBuffer.get(payload);

                        List<JSONObject> unpackedDatas = connector.unPack(payload);
                        boolean heartbeatFrame = !unpackedDatas.isEmpty() && unpackedDatas.stream()
                                .allMatch(item -> item.getIntValue("datapack_type") == DataPackType.HEARTBEAT_RESPONSE.getCode());
                        connector.networkLog.websocketIn("bilibili-live", up.getRoomId(), "BINARY-FRAME",
                                payload.length, Map.of("frameBase64", Base64.getEncoder().encodeToString(payload)),
                                heartbeatFrame);
                        unpackedDatas.stream().map(item -> item.getLongValue("outer_sequence"))
                                .filter(sequence -> sequence > 1).distinct()
                                .forEach(connector.bilibili::acknowledgeLiveRoomSequence);
                        for (JSONObject unpackedData: unpackedDatas) {
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
                                Optional<StarBotBaseLiveEvent> optionalEvent = connector.eventParser.parse(data, up);
                                if (optionalEvent.isPresent()) {
                                    StarBotBaseLiveEvent event = optionalEvent.get();

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
                                    connector.status = ConnectStatus.ERROR;
                                    context.disconnectCause = DisconnectCause.AUTH;
                                    log.warn("{} 的直播间 {} danmu token 已失效，将重新获取 getDanmuInfo", up.getUname(), up.getRoomId());
                                    connector.stopHeartBeat(context);
                                    connector.stopDetectRisk(context);
                                    session.close();
                                    continue;
                                }
                                if (code != 0) {
                                    connector.status = ConnectStatus.ERROR;
                                    context.disconnectCause = DisconnectCause.AUTH;
                                    log.warn("{} 的直播间 {} WebSocket 认证失败: code={}, data={}", up.getUname(), up.getRoomId(), code, data);
                                    session.close();
                                    continue;
                                }
                                connector.status = ConnectStatus.CONNECTED;
                                context.lastHeartbeatAckAt = Instant.now();
                                connector.startHeartBeat(context);
                                context.lastRiskCheckAt = Instant.now();
                                connector.latestDanmus.clear();
                                connector.startDetectRisk(context);
                                log.info("已成功连接到 {} 的直播间 {}: host={}, generation={}",
                                        up.getUname(), up.getRoomId(), context.host, context.generation);

                                BilibiliConnectedEvent event = new BilibiliConnectedEvent(up);
                                connector.eventPublisher.publishEvent(event);
                            } else {
                                log.warn("收到 {} 的直播间 {} 的未知类型({})消息: {}", up.getUname(), up.getRoomId(), dataPackType, data.toJSONString());
                            }
                        }
                    } else if (rawMessage instanceof TextMessage message) {
                        connector.networkLog.websocketIn("bilibili-live", up.getRoomId(), "TEXT",
                                message.getPayloadLength(), message.getPayload(), false);
                    }
                } catch (Exception e) {
                    log.error("处理 {} 的直播间 {} 的 WebSocket 消息异常", up.getUname(), up.getRoomId(), e);
                }
            });
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
            context.transportError = exception;
            context.disconnectCause = connector.stopping ? DisconnectCause.LOCAL : DisconnectCause.TRANSPORT;
            connector.stopHeartBeat(context);
            connector.stopDetectRisk(context);
            if (!connector.stopping) {
                connector.status = ConnectStatus.ERROR;
                if (context.closeObserved.compareAndSet(false, true)) {
                    connector.reportConnectionClosed(context, CloseStatus.NO_CLOSE_FRAME, exception, interval);
                }
                closeQuietly(session);
            } else {
                connector.status = ConnectStatus.CLOSED;
            }
        }

        /**
         * 连接关闭
         * @param session WebSocket 会话
         * @param closeStatus 关闭状态
         */
        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
            if (connectTimeout) {
                return;
            }

            connector.networkLog.websocketIn("bilibili-live", up.getRoomId(), "CLOSE", 0,
                    Map.of("code", closeStatus.getCode(), "reason", closeStatus.getReason(),
                            "host", context.host, "generation", context.generation,
                            "uptimeMs", connector.uptimeMillis(context),
                            "lastHeartbeatAckAt", context.lastHeartbeatAckAt.toString(),
                            "lastHeartbeatAckAgeMs", connector.heartbeatAckAgeMillis(context)), false);
            if (!connector.isCurrent(context)) {
                log.debug("忽略旧代直播间关闭回调: room={}, generation={}, activeGeneration={}, closeCode={}",
                        up.getRoomId(), context.generation, connector.activeGeneration(), closeStatus.getCode());
                return;
            }
            connector.stopHeartBeat(context);
            connector.stopDetectRisk(context);
            if (context.closeObserved.compareAndSet(false, true)) {
                connector.reportConnectionClosed(context, closeStatus, context.transportError, interval);
            }
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
