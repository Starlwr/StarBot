package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.factory.BilibiliLiveRoomConnectorFactory;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Bilibili 直播间服务
 */
@Slf4j
@Service
public class BilibiliLiveRoomService {
    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private BilibiliLiveRoomConnectorFactory connectorFactory;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BlockingQueue<ConnectTask> taskQueue = new LinkedBlockingQueue<>();

    private final Map<Long, Up> ups = new HashMap<>();

    private final Map<Long, Long> roomIdMap = new HashMap<>();

    private final Map<Long, BilibiliLiveRoomConnector> connectors = new HashMap<>();

    /**
     * 直播间连接任务
     */
    @Getter
    @AllArgsConstructor
    private class ConnectTask implements Callable<Void> {
        private final Up up;

        @Override
        public Void call() {
            connectToLiveRoom(up);
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConnectTask that = (ConnectTask) o;
            return Objects.equals(up, that.up);
        }

        @Override
        public int hashCode() {
            return Objects.hash(up);
        }
    }

    /**
     * 启动直播间连接服务
     */
    public void start() {
        executor.submit(() -> {
            Thread.currentThread().setName("bilibili-queue");
            while (true) {
                try {
                    ConnectTask task = taskQueue.take();
                    synchronized (this) {
                        Up up = task.getUp();
                        log.info("从直播间连接队列中取出 {}(UID: {}, 房间号: {}), 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
                        task.call();
                    }
                    Thread.sleep(properties.getLive().getLiveRoomConnectInterval());
                } catch (Exception e) {
                    log.error("直播间连接队列异常", e);
                }
            }
        });
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
            log.warn("UID: {}, 昵称: {} 还未开通直播间", uid, up.getUname());
            return;
        }

        ConnectTask task = new ConnectTask(up);
        if (taskQueue.contains(task)) {
            log.warn("UID 为 {} 的 UP 主已存在于任务队列中, 无需重复添加", uid);
            return;
        }

        taskQueue.add(task);

        log.info("已将 {}(UID: {}, 房间号: {}) 添加至直播间连接队列中, 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
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

        ConnectTask task = new ConnectTask(up);
        if (taskQueue.contains(task)) {
            log.warn("房间号为 {} 的 UP 主已存在于任务队列中, 无需重复添加", up.getRoomId());
            return;
        }

        taskQueue.add(task);

        log.info("已将 {}(UID: {}, 房间号: {}) 添加至直播间连接队列中, 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
    }

    /**
     * 连接到直播间
     * @param up UP 主信息
     */
    private synchronized void connectToLiveRoom(Up up) {
        ups.put(up.getUid(), up);
        roomIdMap.put(up.getRoomId(), up.getUid());

        BilibiliLiveRoomConnector connector = connectorFactory.create(up);
        connectors.put(up.getUid(), connector);

        connector.connect();
    }

    /**
     * 根据 UID 移除直播间监听
     * @param uid UID
     */
    public synchronized void removeByUid(Long uid) {
        if (ups.containsKey(uid)) {
            Up up = ups.get(uid);
            disconnectToLiveRoom(up);
            return;
        }

        Up up = bilibili.getUpInfoByUid(uid);
        ConnectTask task = new ConnectTask(up);
        if (taskQueue.contains(task)) {
            if (taskQueue.remove(task)) {
                log.info("已将 {}(UID: {}, 房间号: {}) 从直播间连接队列中移除, 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
            } else {
                log.error("从任务队列移除 UID 为 {} 的 直播间连接任务失败", uid);
            }
        } else {
            log.warn("UID 为 {} 的 UP 主不存在于监听列表和连接队列中, 无需移除", uid);
        }
    }

    /**
     * 根据房间号移除直播间监听
     * @param roomId 房间号
     */
    public synchronized void removeByRoomId(Long roomId) {
        Long uid = roomIdMap.get(roomId);
        if (uid != null) {
            Up up = ups.get(uid);
            disconnectToLiveRoom(up);
            return;
        }

        Up up = bilibili.getUpInfoByRoomId(roomId);
        ConnectTask task = new ConnectTask(up);
        if (taskQueue.contains(task)) {
            if (taskQueue.remove(task)) {
                log.info("已将 {}(UID: {}, 房间号: {}) 从直播间连接队列中移除, 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
            } else {
                log.error("从任务队列移除房间号为 {} 的 直播间连接任务失败", roomId);
            }
        } else {
            log.warn("房间号为 {} 的 UP 主不存在于监听列表和连接队列中, 无需移除", roomId);
        }
    }

    /**
     * 断开连接直播间
     * @param up UP 主信息
     */
    private synchronized void disconnectToLiveRoom(Up up) {
        BilibiliLiveRoomConnector connector = connectors.get(up.getUid());
        connector.disconnect();

        ups.remove(up.getUid());
        roomIdMap.remove(up.getRoomId());
        connectors.remove(up.getUid());
    }
}
