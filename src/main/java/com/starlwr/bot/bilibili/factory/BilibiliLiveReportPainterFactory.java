package com.starlwr.bot.bilibili.factory;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportConfig;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.BilibiliWordCloudUtil;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.FontUtil;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Bilibili 直播报告绘图器工厂
 */
@StarBotComponent
public class BilibiliLiveReportPainterFactory {
    private final StarBotBilibiliProperties properties;

    private final FontUtil fontUtil;

    private final BilibiliApiUtil bilibili;

    private final StarBotCommonPainterFactory factory;

    private final LiveDataService liveDataService;

    private final BilibiliWordCloudUtil wordCloudUtil;

    @Autowired
    public BilibiliLiveReportPainterFactory(StarBotBilibiliProperties properties, FontUtil fontUtil, BilibiliApiUtil bilibili, StarBotCommonPainterFactory factory, LiveDataService liveDataService, BilibiliWordCloudUtil wordCloudUtil) {
        this.properties = properties;
        this.fontUtil = fontUtil;
        this.bilibili = bilibili;
        this.factory = factory;
        this.liveDataService = liveDataService;
        this.wordCloudUtil = wordCloudUtil;
    }

    /**
     * 创建直播报告绘图器
     * @param up UP 主信息
     * @param config 直播报告配置
     * @return 直播报告绘图器
     */
    public BilibiliLiveReportPainter create(Up up, BilibiliLiveReportConfig config) {
        return new BilibiliLiveReportPainter(properties, fontUtil, bilibili, factory, liveDataService, wordCloudUtil, up, config);
    }
}
