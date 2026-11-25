package com.starlwr.bot.bilibili.cache;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.action.ConfigActionHandler;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 清空头像缓存动作处理器
 */
@StarBotComponent
public class BilibiliClearFaceCacheAction implements ConfigActionHandler {
    /**
     * Bilibili 用户资料缓存
     */
    private final BilibiliProfileCache profileCache;

    @Autowired
    public BilibiliClearFaceCacheAction(BilibiliProfileCache profileCache) {
        this.profileCache = profileCache;
    }

    @Override
    public String execute(JSONObject parameters) {
        profileCache.clearFaceCache();
        return "已清空头像缓存";
    }
}
