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

    @Getter
    private final Up up;

    @Getter
    private ConnectStatus status;

    private WebSocketSession session;

    private ConnectInfo connectInfo;

    private boolean received;

    private ScheduledFuture<?> heartBeatTask;

    private Instant lastHeartBeatResponseTime = Instant.now();

    private ScheduledFuture<?> detectRiskTask;

    private Instant lastDetectRiskTime = Instant.now();

    private final FixedSizeSetQueue<DanmuDTO> latestDanmus = new FixedSizeSetQueue<>(30);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class DanmuDTO {
        private long uid;
        private String content;
        private long timestamp;
    }

    public BilibiliLiveRoomConnector(ThreadPoolTaskExecutor executor, TaskScheduler taskScheduler, ApplicationEventPublisher eventPublisher, StarBotBilibiliProperties properties, LiveDataService liveDataService, BilibiliAccountService accountService, BilibiliLiveRoomConnectTaskService taskService, BilibiliEventParser eventParser, BilibiliApiUtil bilibili, BilibiliNetworkLogger networkLog, Up up) {
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

        this.up = up;

        this.status = ConnectStatus.INIT;
    }

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
    private String getConnectUrl() {
        connectInfo = bilibili.getLiveRoomConnectInfo(up.getRoomId());
        ConnectAddress address = connectInfo.getAddresses().get(0);
        return String.format("wss://%s:%d/sub", address.getHost(), address.getWssPort());
    }

    /**
     * 连接到直播间
     */
    public void connect() {
        executor.submit(() -> {
            if (status == ConnectStatus.CLOSING) {
                status = ConnectStatus.CLOSED;
                return;
            }

            int interval = properties.getLive().getLiveRoomReconnectInterval();

            log.info("准备连接到 {} 的直播间 {}", up.getUname(), up.getRoomId());

            status = ConnectStatus.CONNECTING;
            received = false;

            CompletableFuture<WebSocketSession> sessionFuture = null;
            BilibiliNetworkLogger.HttpTrace handshakeTrace = null;
            try {
                String url = getConnectUrl();

                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                headers.add("User-Agent", properties.getNetwork().getUserAgent());
                handshakeTrace = networkLog.httpRequest("bilibili-live-ws-handshake", "GET", url,
                        headers.toSingleValueMap(), null);

                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                container.setDefaultMaxBinaryMessageBufferSize(2 * 1024 * 1024);
                StandardWebSocketClient webSocketClient = new StandardWebSocketClient(container);
                BilibiliWebSocketHandler handler = new BilibiliWebSocketHandler(this);
                sessionFuture = webSocketClient.execute(handler, headers, URI.create(url));

                if (handler.awaitConnection()) {
                    WebSocketSession connectedSession = sessionFuture.get();
                    networkLog.httpResponse(handshakeTrace, 101, Collections.emptyMap(), "<websocket-upgrade>");

                    lastHeartBeatResponseTime = Instant.now();
                    startHeartBeat();

                    lastDetectRiskTime = Instant.now();
                    latestDanmus.clear();
                    startDetectRisk();
                } else {
                    throw new TimeoutException();
                }
            } catch (Exception e) {
                if (handshakeTrace != null) {
                    networkLog.httpFailure(handshakeTrace, e);
                }
                status = ConnectStatus.ERROR;
                if (e instanceof TimeoutException) {
                    log.warn("与 {} 的直播间 {} 连接超时, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000);
                    sessionFuture.cancel(true);
                } else {
                    log.warn("与 {} 的直播间 {} 连接异常, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000, e);
                }

                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                taskService.add(this);
            }
        });
    }

    /**
     * 断开连接直播间
     */
    public void disconnect() {
        log.info("准备断开 {} 的直播间 {}", up.getUname(), up.getRoomId());
        status = ConnectStatus.CLOSING;
        stopHeartBeat();
        stopDetectRisk();

        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.error("断开 {} 的直播间 {} 的 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
            }
        }

        log.info("已断开连接 {} 的直播间 {}", up.getUname(), up.getRoomId());

        BilibiliDisconnectedEvent event = new BilibiliDisconnectedEvent(up);
        eventPublisher.publishEvent(event);
    }

    /**
     * 发送认证包
     */
    private void sendVerifyData() {
        Map<String, Object> verifyData = Map.of(
                "uid", accountService.getAccountInfo().getUid(),
                "roomid", up.getRoomId(),
                "protover", 3,
                "buvid", bilibili.getCookies().getBuvid3(),
                "platform", "web",
                "type", 2,
                "key", connectInfo.getToken()
        );
        String jsonString = JSON.toJSONString(verifyData);
        byte[] dataBytes = jsonString.getBytes(StandardCharsets.UTF_8);

        send(DataHeaderType.HEARTBEAT, DataPackType.VERIFY, dataBytes);
    }

    /**
     * 定时发送心跳包
     */
    private void startHeartBeat() {
        if (heartBeatTask != null) {
            return;
        }

        heartBeatTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (status != ConnectStatus.CONNECTED) {
                return;
            }

            if (Instant.now().minusSeconds(75).isAfter(lastHeartBeatResponseTime)) {
                status = ConnectStatus.TIMEOUT;

                try {
                    session.close();
                } catch (Exception e) {
                    log.error("断开 {} 的直播间 {} 的 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
                }

                return;
            }

            try {
                send(DataHeaderType.HEARTBEAT, DataPackType.HEARTBEAT, "[object Object]".getBytes(StandardCharsets.UTF_8));
                bilibili.liveRoomHeartbeat(up.getRoomId());
            } catch (Exception e) {
                log.error("发送 {} 的直播间 {} 的心跳包异常", up.getUname(), up.getRoomId(), e);
            }
        }), Instant.now().plusSeconds(10), Duration.ofSeconds(30));
    }

    /**
     * 停止定时发送心跳包
     */
    private void stopHeartBeat() {
        if (heartBeatTask != null) {
            heartBeatTask.cancel(false);
            heartBeatTask = null;
        }
    }

    /**
     * 定时检测直播间数据风控
     */
    private void startDetectRisk() {
        if (!properties.getLive().isAutoDetectLiveRoomRisk()) {
            return;
        }

        if (properties.getLive().getAutoDetectLiveRoomRiskRatio() < 1 || properties.getLive().getAutoDetectLiveRoomRiskRatio() > 100) {
            log.warn("直播间数据风控检测阈值配置不正确({}%), 请配置为 1 ~ 100 的数值后重试", properties.getLive().getAutoDetectLiveRoomRiskRatio());
            return;
        }

        if (detectRiskTask != null) {
            return;
        }

        int interval = properties.getLive().getAutoDetectLiveRoomRiskInterval();

        detectRiskTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (status != ConnectStatus.CONNECTED) {
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
                        .filter(danmu -> danmu.getTimestamp().isAfter(lastDetectRiskTime))
                        .map(danmu -> new DanmuDTO(danmu.getSender().getUid(), danmu.getContent(), danmu.getTimestamp().getEpochSecond()))
                        .toList();
            } catch (Exception e) {
                log.error("直播间风控检测获取直播间 {} 最新弹幕失败, 偶然出现此异常可忽略", up.getRoomId(), e);
                return;
            }

            if (apiDanmus.size() < 4) {
                return;
            }

            lastDetectRiskTime = Instant.now();

            long receivedCount = apiDanmus.stream().filter(latestDanmus::contains).count();
            double ratio = (double) receivedCount / apiDanmus.size() * 100;
            if (ratio < properties.getLive().getAutoDetectLiveRoomRiskRatio()) {
                log.debug("{} 的直播间 {} 数据抓取比例: {}%, 已达到风控阈值, 房间最新弹幕: {}", up.getUname(), up.getRoomId(), Math.round(ratio), apiDanmus);

                status = ConnectStatus.RISK;

                try {
                    session.close();
                } catch (Exception e) {
                    log.error("断开 {} 的直播间 {} 的 Websocket 连接异常", up.getUname(), up.getRoomId(), e);
                }
            }
        }), Instant.now().plusSeconds(interval), Duration.ofSeconds(interval));
    }

    /**
     * 停止定时检测直播间数据风控
     */
    private void stopDetectRisk() {
        if (detectRiskTask != null) {
            detectRiskTask.cancel(false);
            detectRiskTask = null;
        }
    }

    /**
     * 发送 Websocket 数据
     * @param headerType 数据头类型
     * @param packType 数据包类型
     * @param data 数据
     */
    private void send(DataHeaderType headerType, DataPackType packType, byte[] data) {
        byte[] packedData = pack(headerType, packType, data);
        networkLog.websocketOut("bilibili-live", up.getRoomId(),
                packType.name() + "/protocol-" + headerType.getCode(), packedData.length,
                Map.of("decoded", new String(data, StandardCharsets.UTF_8),
                        "frameBase64", Base64.getEncoder().encodeToString(packedData)),
                packType == DataPackType.HEARTBEAT);
        try {
            session.sendMessage(new BinaryMessage(packedData));
        } catch (IOException e) {
            log.error("发送 {} 的直播间 {} 的 Websocket 消息异常", up.getUname(), up.getRoomId(), e);
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

        ByteBuffer header = ByteBuffer.wrap(data, 0, 12).order(ByteOrder.BIG_ENDIAN);
        header.getInt();
        header.getShort();
        short protocolVersion = header.getShort();
        int dataPackType = header.getInt();

        byte[] realData;
        if (protocolVersion == DataHeaderType.BROTLI_JSON.getCode()) {
            byte[] compressedData = new byte[data.length - 16];
            System.arraycopy(data, 16, compressedData, 0, compressedData.length);

            try (ByteArrayInputStream compressedInputStream = new ByteArrayInputStream(compressedData);
                 BrotliInputStream brotliInputStream = new BrotliInputStream(compressedInputStream);
                 ByteArrayOutputStream decompressedOutputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int bytesRead;
                while ((bytesRead = brotliInputStream.read(buffer)) != -1) {
                    decompressedOutputStream.write(buffer, 0, bytesRead);
                }
                realData = decompressedOutputStream.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("解析 brotli 数据异常", e);
            }
        } else {
            realData = data;
        }

        if (protocolVersion == DataHeaderType.HEARTBEAT.getCode() && dataPackType == DataPackType.HEARTBEAT_RESPONSE.getCode()) {
            realData = new byte[data.length - 16];
            System.arraycopy(data, 16, realData, 0, data.length - 16);

            ByteBuffer heartBeatBuffer = ByteBuffer.wrap(realData);
            int view = heartBeatBuffer.getInt();

            JSONObject heartBeatData = new JSONObject();
            heartBeatData.put("protocol_version", protocolVersion);
            heartBeatData.put("datapack_type", dataPackType);
            heartBeatData.put("data", new JSONObject().fluentPut("view", view));

            result.add(heartBeatData);
            return result;
        }

        int offset = 0;
        while (offset < realData.length) {
            ByteBuffer chunkBuffer = ByteBuffer.wrap(realData, offset, 12);
            int chunkLength = chunkBuffer.getInt();
            chunkBuffer.getShort();
            short chunkProtocolVersion = chunkBuffer.getShort();
            int chunkDataPackType = chunkBuffer.getInt();

            int dataLength = chunkLength - 16;
            ByteBuffer dataBuffer = ByteBuffer.wrap(realData, offset + 16, dataLength);
            byte[] chunkData = new byte[dataLength];
            dataBuffer.get(chunkData);

            JSONObject receiveData = new JSONObject();
            receiveData.put("protocol_version", chunkProtocolVersion);
            receiveData.put("datapack_type", chunkDataPackType);

            if (chunkProtocolVersion == 0 || chunkProtocolVersion == 2) {
                receiveData.put("data", JSON.parseObject(new String(chunkData, StandardCharsets.UTF_8)));
            } else if (chunkProtocolVersion == 1) {
                if (chunkDataPackType == DataPackType.HEARTBEAT_RESPONSE.getCode()) {
                    receiveData.put("data", new JSONObject().fluentPut("view", ByteBuffer.wrap(chunkData).getInt()));
                } else if (chunkDataPackType == DataPackType.VERIFY_SUCCESS_RESPONSE.getCode()) {
                    receiveData.put("data", JSON.parseObject(new String(chunkData, StandardCharsets.UTF_8)));
                }
            }
            result.add(receiveData);
            offset += chunkLength;
        }

        return result;
    }

    /**
     * WebSocket 处理器
     */
    private static class BilibiliWebSocketHandler implements WebSocketHandler {
        private final BilibiliLiveRoomConnector connector;

        private final ThreadPoolTaskExecutor executor;

        private final Up up;

        private final int interval;

        private final CountDownLatch latch = new CountDownLatch(1);

        private boolean connectTimeout = false;

        private BilibiliWebSocketHandler(BilibiliLiveRoomConnector connector) {
            this.connector = connector;
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
                log.info("与 {} 的直播间 {} 的 Websocket 连接成功, 开始发送认证数据", up.getUname(), up.getRoomId());
                connector.session = session;
                try {
                    connector.sendVerifyData();
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
                try {
                    connector.received = true;
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
                                connector.lastHeartBeatResponseTime = Instant.now();
                            } else if (dataPackType == DataPackType.VERIFY_SUCCESS_RESPONSE.getCode()) {
                                connector.status = ConnectStatus.CONNECTED;
                                log.info("已成功连接到 {} 的直播间 {}", up.getUname(), up.getRoomId());

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
            executor.execute(() -> {
                if (connector.status != ConnectStatus.CLOSING) {
                    connector.status = ConnectStatus.ERROR;
                    log.warn("与 {} 的直播间 {} 连接异常, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000, exception);
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    connector.taskService.add(connector);
                } else {
                    connector.status = ConnectStatus.CLOSED;
                }
            });
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
                    Map.of("code", closeStatus.getCode(), "reason", closeStatus.getReason()), false);

            executor.execute(() -> {
                if (connector.status == ConnectStatus.CLOSING) {
                    connector.status = ConnectStatus.CLOSED;
                    return;
                }

                if (connector.status == ConnectStatus.TIMEOUT) {
                    log.warn("{} 的直播间 {} 心跳响应超时, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000);
                } else if (connector.status == ConnectStatus.RISK) {
                    log.warn("检测到 {} 的直播间 {} 被数据风控, 抓取到的数据不完整, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000);
                } else {
                    connector.status = ConnectStatus.ERROR;
                    if (connector.received) {
                        log.warn("与 {} 的直播间 {} 连接断开 ({}: {}), 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), closeStatus.getCode(), closeStatus.getReason(), interval / 1000);
                    } else {
                        log.error("与 {} 的直播间 {} 连接异常, 自连接建立后未收到响应数据, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000);
                    }
                }

                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                connector.taskService.add(connector);
            });
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
