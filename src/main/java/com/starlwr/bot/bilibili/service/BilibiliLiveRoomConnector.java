package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.event.live.BilibiliConnectedEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliDisconnectedEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEmojiEvent;
import com.starlwr.bot.bilibili.model.ConnectAddress;
import com.starlwr.bot.bilibili.model.ConnectInfo;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.util.FixedSizeSetQueue;
import jakarta.annotation.Resource;
import jakarta.websocket.ClientEndpoint;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.brotli.dec.BrotliInputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Scope;
import org.springframework.data.util.Pair;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bilibili 直播间连接器
 */
@Slf4j
@Service
@ClientEndpoint
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class BilibiliLiveRoomConnector {
    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    @Qualifier("bilibiliThreadPool")
    private ThreadPoolTaskExecutor executor;

    @Resource
    private BilibiliAccountService accountService;

    @Resource
    private BilibiliLiveRoomConnectTaskService taskService;

    @Resource
    private BilibiliEventParser eventParser;

    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private TaskScheduler taskScheduler;

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

    private final FixedSizeSetQueue<Pair<Long, String>> latestDanmus = new FixedSizeSetQueue<>(30);

    public BilibiliLiveRoomConnector(Up up) {
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
     * 获取当前直播间主播信息
     * @return 当前直播间主播信息
     */
    private LiveStreamerInfo getLiveStreamerInfo() {
        return new LiveStreamerInfo(up.getUid(), up.getUname(), up.getRoomId(), up.getFace());
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

            Throwable exception = null;
            try {
                String url = getConnectUrl();

                CompletableFuture<WebSocketSession> sessionFuture = new CompletableFuture<>();

                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                headers.add("User-Agent", properties.getNetwork().getUserAgent());

                StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
                BilibiliWebSocketHandler handler = new BilibiliWebSocketHandler(this, interval, sessionFuture);
                webSocketClient.execute(handler, headers, URI.create(url));

                this.session = sessionFuture.get(3, TimeUnit.SECONDS);

                lastHeartBeatResponseTime = Instant.now();
                startHeartBeat();

                startDetectRisk();
            } catch (Exception e) {
                exception = e;
            }

            if (exception != null) {
                status = ConnectStatus.ERROR;
                if (exception instanceof TimeoutException) {
                    log.warn("与 {} 的直播间 {} 连接超时, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000);
                } else {
                    log.warn("与 {} 的直播间 {} 连接异常, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000, exception);
                }

                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
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
                log.error("断开 Websocket 连接异常", e);
            }
        }

        log.info("已断开连接 {} 的直播间 {}", up.getUname(), up.getRoomId());

        BilibiliDisconnectedEvent event = new BilibiliDisconnectedEvent(getLiveStreamerInfo());
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
                    log.error("断开 Websocket 连接异常", e);
                }

                return;
            }

            try {
                send(DataHeaderType.HEARTBEAT, DataPackType.HEARTBEAT, "[object Object]".getBytes(StandardCharsets.UTF_8));
                bilibili.liveRoomHeartbeat(up.getRoomId());
            } catch (Exception e) {
                log.error("发送心跳包异常", e);
            }
        }), Instant.now().plusSeconds(10), Duration.ofSeconds(30));
    }

    /**
     * 停止定时发送心跳包
     */
    private void stopHeartBeat() {
        if (heartBeatTask != null) {
            heartBeatTask.cancel(false);
        }
    }

    /**
     * 定时检测直播间数据风控
     */
    private void startDetectRisk() {
        if (!properties.getLive().isAutoDetectLiveRoomRisk()) {
            return;
        }

        if (detectRiskTask != null) {
            return;
        }

        latestDanmus.clear();
        bilibili.getLiveRoomLatestDanmus(up.getRoomId()).forEach(latestDanmus::add);

        int interval = properties.getLive().getAutoDetectLiveRoomRiskInterval();

        detectRiskTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (status != ConnectStatus.CONNECTED) {
                return;
            }

            List<Pair<Long, String>> apiDanmus = bilibili.getLiveRoomLatestDanmus(up.getRoomId());
            if (apiDanmus.isEmpty()) {
                return;
            }

            long receivedCount = apiDanmus.stream().filter(latestDanmus::contains).count();
            double ratio = (double) receivedCount / apiDanmus.size() * 100;
            if (ratio <= properties.getLive().getAutoDetectLiveRoomRiskRatio()) {
                log.debug("{} 的直播间 {} 数据抓取比例: {}%, 已达到风控阈值, 房间最新弹幕: {}, ", up.getUname(), up.getRoomId(), Math.round(ratio), apiDanmus);

                status = ConnectStatus.RISK;

                try {
                    session.close();
                } catch (Exception e) {
                    log.error("断开 Websocket 连接异常", e);
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
        try {
            session.sendMessage(new BinaryMessage(packedData));
        } catch (IOException e) {
            log.error("发送 Websocket 消息异常", e);
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

        private final CompletableFuture<WebSocketSession> sessionFuture;

        private BilibiliWebSocketHandler(BilibiliLiveRoomConnector connector, int interval, CompletableFuture<WebSocketSession> sessionFuture) {
            this.connector = connector;
            this.executor = connector.executor;
            this.up = connector.up;
            this.interval = interval;
            this.sessionFuture = sessionFuture;
        }

        /**
         * 连接建立
         * @param session WebSocket 会话
         */
        @Override
        public void afterConnectionEstablished(@NonNull WebSocketSession session) {
            executor.submit(() -> {
                log.info("与 {} 的直播间 {} Websocket 连接成功, 开始发送认证数据", up.getUname(), up.getRoomId());
                sessionFuture.complete(session);
                connector.sendVerifyData();
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
                        byte[] payload = message.getPayload().array();

                        List<JSONObject> unpackedDatas = connector.unPack(payload);
                        for (JSONObject unpackedData: unpackedDatas) {
                            int dataPackType = unpackedData.getIntValue("datapack_type");
                            JSONObject data = unpackedData.getJSONObject("data");

                            if (dataPackType == DataPackType.NOTICE.getCode()) {
                                Optional<StarBotBaseLiveEvent> optionalEvent = connector.eventParser.parse(data, connector.getLiveStreamerInfo());
                                if (optionalEvent.isPresent()) {
                                    StarBotBaseLiveEvent event = optionalEvent.get();

                                    if (connector.properties.getLive().isAutoDetectLiveRoomRisk()) {
                                        if (event instanceof BilibiliDanmuEvent danmuEvent) {
                                            connector.latestDanmus.add(Pair.of(danmuEvent.getSender().getUid(), danmuEvent.getContent()));
                                        } else if (event instanceof BilibiliEmojiEvent emojiEvent) {
                                            connector.latestDanmus.add(Pair.of(emojiEvent.getSender().getUid(), emojiEvent.getEmoji().getName()));
                                        }
                                    }

                                    connector.eventPublisher.publishEvent(event);
                                }
                            } else if (dataPackType == DataPackType.HEARTBEAT_RESPONSE.getCode()) {
                                connector.lastHeartBeatResponseTime = Instant.now();
                            } else if (dataPackType == DataPackType.VERIFY_SUCCESS_RESPONSE.getCode()) {
                                connector.status = ConnectStatus.CONNECTED;
                                log.info("已成功连接到 {} 的直播间 {}", up.getUname(), up.getRoomId());

                                BilibiliConnectedEvent event = new BilibiliConnectedEvent(connector.getLiveStreamerInfo());
                                connector.eventPublisher.publishEvent(event);
                            } else {
                                log.warn("收到直播间 {} 的未知类型({})消息: {}", up.getRoomId(), dataPackType, data.toJSONString());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("处理 WebSocket 消息异常", e);
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
                        log.warn("与 {} 的直播间 {} 连接断开, 将在 {} 秒后重新连接", up.getUname(), up.getRoomId(), interval / 1000);
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
