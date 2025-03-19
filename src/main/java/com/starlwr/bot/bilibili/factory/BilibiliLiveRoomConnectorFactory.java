package com.starlwr.bot.bilibili.factory;

import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomConnector;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Bilibili 直播间连接器工厂
 */
@Component
public class BilibiliLiveRoomConnectorFactory {
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 创建直播间连接器
     * @param up UP 主
     * @return 直播间连接器
     */
    public BilibiliLiveRoomConnector create(Up up) {
        return applicationContext.getBean(BilibiliLiveRoomConnector.class, up);
    }
}
