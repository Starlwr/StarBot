package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.bilibili.protocol.DanmakuPacketCodec;
import com.starlwr.bot.bilibili.account.BilibiliAccountService;
import com.starlwr.bot.bilibili.live.protocol.BilibiliEventParser;
import com.starlwr.bot.bilibili.live.connection.BilibiliFailureIncidentReporter;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnectTaskService;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnector;
import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.live.LiveDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import jakarta.annotation.PreDestroy;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/**
 * Bilibili 直播间连接器工厂
 */
@StarBotComponent
public class BilibiliLiveRoomConnectorFactory {
    private final ThreadPoolTaskExecutor executor;

    private final TaskScheduler taskScheduler;

    private final ApplicationEventPublisher eventPublisher;

    private final StarBotBilibiliProperties properties;

    private final LiveDataService liveDataService;

    private final BilibiliAccountService accountService;

    private final BilibiliLiveRoomConnectTaskService taskService;

    private final BilibiliEventParser eventParser;

    private final BilibiliApiService bilibili;

    private final BilibiliNetworkLogger networkLog;

    private final DanmakuPacketCodec packetCodec;

    private final BilibiliLiveWsClient webSocketClient;

    private final BilibiliFailureIncidentReporter incidentReporter;
    private final Danmu352CircuitBreaker circuitBreaker;

    private final WebSocketContainer container;

    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public BilibiliLiveRoomConnectorFactory(@Qualifier("bilibiliThreadPool") ThreadPoolTaskExecutor executor, TaskScheduler taskScheduler, ApplicationEventPublisher eventPublisher, StarBotBilibiliProperties properties, LiveDataService liveDataService, BilibiliAccountService accountService, BilibiliLiveRoomConnectTaskService taskService, BilibiliEventParser eventParser, BilibiliApiService bilibili, BilibiliNetworkLogger networkLog, DanmakuPacketCodec packetCodec, BilibiliFailureIncidentReporter incidentReporter, Danmu352CircuitBreaker circuitBreaker, BrowserLiveWsClient browserLiveWsClient) {
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
        this.incidentReporter = incidentReporter;
        this.circuitBreaker = circuitBreaker;
        container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(properties.getLive().getWebSocketBufferSizeBytes());
        this.webSocketClient = new BilibiliLiveWsClient(new StandardWebSocketClient(container), browserLiveWsClient, properties);
        LoggerFactory.getLogger(getClass()).info("弹幕 WebSocket 传输配置: ws-transport={}, browserBridge={}",
                properties.getLive().getWsTransport(), browserLiveWsClient != null);
    }

    /**
     * 创建直播间连接器
     * @param up UP 主
     * @return 直播间连接器
     */
    public BilibiliLiveRoomConnector create(Up up) {
        if (closed.get()) throw new IllegalStateException("WebSocket connector factory is closed");
        return new BilibiliLiveRoomConnector(executor, taskScheduler, eventPublisher, properties, liveDataService, accountService, taskService, eventParser, bilibili, networkLog, packetCodec, webSocketClient, incidentReporter, circuitBreaker, up);
    }

    @PreDestroy
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        if (container instanceof WsWebSocketContainer tomcat) tomcat.destroy();
        else if (container instanceof AutoCloseable closeable) closeable.close();
    }
}
