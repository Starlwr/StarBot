package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.factory.BilibiliLiveRoomConnectorFactory;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Bilibili 直播间服务
 */
@Slf4j
@Service
public class BilibiliLiveRoomService {
    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private BilibiliLiveRoomConnectorFactory connectorFactory;

    private final Map<Long, Up> ups = new HashMap<>();

    private final Map<Long, Long> roomIdMap = new HashMap<>();

    private final Map<Long, BilibiliLiveRoomConnector> connectors = new HashMap<>();

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

        connectToLiveRoom(up);
    }

    /**
     * 根据房间号添加直播间监听
     * @param roomId 房间号
     */
    public synchronized void addByRoomId(Long roomId) {
        Up up = bilibili.getUpInfoByRoomId(roomId);
        if (ups.containsKey(up.getUid())) {
            log.warn("UID 为 {} 的 UP 主已存在于监听列表中, 无需重复添加", up.getUid());
            return;
        }

        connectToLiveRoom(up);
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
        if (!ups.containsKey(uid)) {
            log.warn("UID 为 {} 的 UP 主不存在于监听列表中, 无需移除", uid);
            return;
        }

        BilibiliLiveRoomConnector connector = connectors.get(uid);
        connector.disconnect();

        Up up = ups.remove(uid);
        roomIdMap.remove(up.getRoomId());
        connectors.remove(uid);
    }

    /**
     * 根据房间号移除直播间监听
     * @param roomId 房间号
     */
    public synchronized void removeByRoomId(Long roomId) {
        Long uid = roomIdMap.get(roomId);
        if (uid == null) {
            log.warn("房间号为 {} 的直播间不存在于监听列表中, 无需移除", roomId);
            return;
        }

        removeByUid(uid);
    }
}
