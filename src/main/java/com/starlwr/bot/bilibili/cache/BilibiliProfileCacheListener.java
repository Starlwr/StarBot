package com.starlwr.bot.bilibili.cache;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.event.live.base.StarBotLiveUserEvent;
import com.starlwr.bot.core.event.model.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * Bilibili 用户资料缓存监听器
 */
@Slf4j
@StarBotComponent
public class BilibiliProfileCacheListener {
    /**
     * Bilibili 用户资料缓存
     */
    private final BilibiliProfileCache profileCache;

    /**
     * StarBotBilibili 配置类
     */
    private final StarBotBilibiliProperties properties;

    @Autowired
    public BilibiliProfileCacheListener(BilibiliProfileCache profileCache, StarBotBilibiliProperties properties) {
        this.profileCache = profileCache;
        this.properties = properties;
    }

    /**
     * 从直播间事件中缓存用户资料
     * @param event 直播事件
     */
    @Order(-10000)
    @EventListener
    public void onExternalEvent(StarBotBaseLiveEvent event) {
        if (!properties.getCache().isCacheProfileFromEvents()) {
            return;
        }

        if (!LivePlatform.BILIBILI.getName().equals(event.getPlatform())) {
            return;
        }

        try {
            profileCache.cacheUserProfile(event.getSource());
            if (event instanceof StarBotLiveUserEvent userEvent) {
                profileCache.cacheUserProfile(userEvent.getSender());
            }
        } catch (Exception e) {
            log.warn("从直播间事件中缓存用户资料失败: {}", event, e);
        }
    }
}
