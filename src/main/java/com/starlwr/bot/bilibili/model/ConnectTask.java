package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.bilibili.service.BilibiliLiveRoomConnector;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 直播间连接任务
 */
@Getter
@AllArgsConstructor
public class ConnectTask implements Callable<Void> {
    /**
     * 直播间连接器
     */
    private final BilibiliLiveRoomConnector connector;

    @Override
    public Void call() {
        connector.connect();
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConnectTask that = (ConnectTask) o;
        return Objects.equals(connector, that.connector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connector);
    }
}
