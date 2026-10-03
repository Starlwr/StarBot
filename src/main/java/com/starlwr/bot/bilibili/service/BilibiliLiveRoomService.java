package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.factory.BilibiliLiveRoomConnectorFactory;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import jakarta.annotation.PreDestroy;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Bilibili 直播间服务
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveRoomService {
    private final StarBotBilibiliProperties properties;

    private final BilibiliApiUtil bilibili;

    private final BilibiliLiveRoomConnectorFactory connectorFactory;

    private final BilibiliLiveRoomConnectTaskService taskService;

    private final LiveDataService liveDataService;

    private final Map<Long, Up> ups = new HashMap<>();

    private final Map<Long, Long> roomIdMap = new HashMap<>();

    private final Map<Long, BilibiliLiveRoomConnector> connectors = new HashMap<>();

    private boolean closed;

    @Autowired
    public BilibiliLiveRoomService(StarBotBilibiliProperties properties, BilibiliApiUtil bilibili, BilibiliLiveRoomConnectorFactory connectorFactory, BilibiliLiveRoomConnectTaskService taskService, LiveDataService liveDataService) {
        this.properties = properties;
        this.bilibili = bilibili;
        this.connectorFactory = connectorFactory;
        this.taskService = taskService;
        this.liveDataService = liveDataService;
    }

    /**
     * 更新主播昵称头像
     * @param event 事件
     */
    @Order(0)
    @EventListener
    public synchronized void onStarBotDataSourceUpdateEvent(StarBotDataSourceUpdateEvent event) {
        Up up = ups.get(event.getUser().getUid());
        if (up != null) {
            up.setUname(event.getUser().getUname());
            up.setFace(event.getUser().getFace());
        }
    }

    /**
     * 直播间风控检测检查
     */
    @Order(-10000)
    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public synchronized void onStarBotDataSourceLoadCompleteEvent() {
        if (ups.size() > 50 && properties.getLive().isAutoDetectLiveRoomRisk()) {
            log.warn("需要连接的直播间过多, 将不可避免的有部分直播间被数据风控, 建议关闭直播间数据风控检测功能, 避免持续尝试重新连接直播间导致更严重的风控");
        }
    }

    /**
     * 是否存在指定 UID 的 UP 主
     * @param uid UID
     * @return 是否存在指定 UID 的 UP 主
     */
    public synchronized boolean hasUp(Long uid) {
        return ups.containsKey(uid);
    }

    /**
     * 根据 UID 添加直播间监听
     * @param uid UID
     */
    public synchronized void addByUid(Long uid) {
        if (ups.containsKey(uid)) {
            log.warn("UID 为 {} 的 UP 主已存在于监听列表中, 无需重复添加", uid);
            return;
        }

        Up up = bilibili.getUpInfoByUid(uid);
        if (up.getRoomId() == null) {
            log.warn("{}(UID: {}) 还未开通直播间", up.getUname(), uid);
            return;
        }

        addTask(up);
    }

    /**
     * 根据房间号添加直播间监听
     * @param roomId 房间号
     */
    public synchronized void addByRoomId(Long roomId) {
        Up up = bilibili.getUpInfoByRoomId(roomId);
        if (ups.containsKey(up.getUid())) {
            log.warn("房间号为 {} 的 UP 主已存在于监听列表中, 无需重复添加", up.getRoomId());
            return;
        }

        addTask(up);
    }

    /**
     * 添加直播间监听
     * @param up UP 主
     */
    public synchronized void addUp(Up up) {
        if (up.getRoomId() == null) {
            log.warn("{}(UID: {}) 还未开通直播间", up.getUname(), up.getUid());
            return;
        }

        if (ups.containsKey(up.getUid())) {
            log.warn("{}(UID: {}, 房间号: {}) 已存在于监听列表中, 无需重复添加", up.getUname(), up.getUid(), up.getRoomIdString());
            return;
        }

        addTask(up);
    }

    /**
     * 添加直播间连接任务
     * @param up UP 主信息
     */
    private synchronized void addTask(Up up) {
        if (closed) return;
        ups.put(up.getUid(), up);
        roomIdMap.put(up.getRoomId(), up.getUid());

        BilibiliLiveRoomConnector connector = connectorFactory.create(up);
        connectors.put(up.getUid(), connector);

        taskService.add(connector);
    }

    /**
     * 根据 UID 移除直播间监听
     * @param uid UID
     */
    public synchronized void removeByUid(Long uid) {
        if (!ups.containsKey(uid)) {
            log.warn("UID 为 {} 的 UP 主不存在于监听列表中, 无需移除", uid);
            return;
        }

        removeTask(ups.get(uid));
    }

    /**
     * 根据房间号移除直播间监听
     * @param roomId 房间号
     */
    public synchronized void removeByRoomId(Long roomId) {
        Long uid = roomIdMap.get(roomId);
        if (uid == null) {
            log.warn("房间号为 {} 的 UP 主不存在于监听列表中, 无需移除", roomId);
            return;
        }

        removeTask(ups.get(uid));
    }

    /**
     * 移除直播间监听
     * @param up UP 主
     */
    public synchronized void removeUp(Up up) {
        if (!ups.containsKey(up.getUid())) {
            log.warn("{}(UID: {}, 房间号: {}) 不存在于监听列表中, 无需移除", up.getUname(), up.getUid(), up.getRoomIdString());
            return;
        }

        removeTask(up);
    }

    /**
     * 移除直播间连接任务
     * @param up UP 主信息
     */
    private synchronized void removeTask(Up up) {
        BilibiliLiveRoomConnector connector = connectors.remove(up.getUid());
        ups.remove(up.getUid());
        roomIdMap.remove(up.getRoomId());
        try {
            taskService.remove(connector);
            // A delayed reconnect can coexist with a live/closing session; cancellation alone is insufficient.
            connector.disconnect();
        } finally { liveDataService.deactivate("bilibili", up.getUid(), "datasource_removed"); }
    }

    @PreDestroy
    public void close() {
        List<BilibiliLiveRoomConnector> closing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = List.copyOf(connectors.values());
            connectors.clear(); ups.clear(); roomIdMap.clear();
        }
        RuntimeException failure = null;
        for (BilibiliLiveRoomConnector connector : closing) {
            try { connector.disconnect(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
