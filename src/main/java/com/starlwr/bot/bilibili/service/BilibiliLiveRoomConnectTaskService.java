package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.ConnectTask;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bilibili 直播间连接任务管理服务
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveRoomConnectTaskService {
    @Resource
    private StarBotBilibiliProperties properties;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BlockingQueue<ConnectTask> taskQueue = new LinkedBlockingQueue<>();

    @EventListener(StarBotDataSourceLoadCompleteEvent.class)
    public void handleLoadCompleteEvent() {
        executor.submit(() -> {
            Thread.currentThread().setName("bilibili-queue");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ConnectTask task = taskQueue.take();
                    synchronized (BilibiliLiveRoomConnectTaskService.this) {
                        Up up = task.getConnector().getUp();
                        log.info("从直播间连接队列中取出 {}(UID: {}, 房间号: {}), 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
                        task.call();
                    }
                    Thread.sleep(properties.getLive().getLiveRoomConnectInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("直播间连接队列异常", e);
                }
            }
        });
    }

    /**
     * 添加直播间连接任务
     * @param connector 直播间连接器
     * @return 是否添加成功
     */
    public synchronized boolean add(BilibiliLiveRoomConnector connector) {
        Up up = connector.getUp();

        ConnectTask task = new ConnectTask(connector);
        if (taskQueue.contains(task)) {
            log.warn("{}(UID: {}, 房间号: {}) 已存在于任务队列中, 无需重复添加", up.getUname(), up.getUid(), up.getRoomId());
            return false;
        }

        taskQueue.add(task);
        log.info("已将 {}(UID: {}, 房间号: {}) 添加至直播间连接队列中, 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());

        return true;
    }

    /**
     * 移除直播间连接任务
     * @param connector 直播间连接器
     * @return 是否移除成功
     */
    public synchronized boolean remove(BilibiliLiveRoomConnector connector) {
        Up up = connector.getUp();

        ConnectTask task = new ConnectTask(connector);
        if (taskQueue.contains(task)) {
            if (taskQueue.remove(task)) {
                log.info("已将 {}(UID: {}, 房间号: {}) 从直播间连接队列中移除, 当前直播间连接队列长度: {}", up.getUname(), up.getUid(), up.getRoomId(), taskQueue.size());
                return true;
            } else {
                log.error("从任务队列移除 {}(UID: {}, 房间号: {}) 的直播间连接任务失败", up.getUname(), up.getUid(), up.getRoomId());
            }
        }

        return false;
    }
}
