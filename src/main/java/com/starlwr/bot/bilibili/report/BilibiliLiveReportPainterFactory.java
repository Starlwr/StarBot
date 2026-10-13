package com.starlwr.bot.bilibili.report;

import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.live.LiveDataService;
import com.starlwr.bot.core.painter.FontService;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Bilibili 直播报告绘图器工厂
 */
@StarBotComponent
public class BilibiliLiveReportPainterFactory {
    private final StarBotCoreProperties coreProperties;

    private final StarBotBilibiliProperties bilibiliProperties;

    private final FontService fontService;

    private final BilibiliApiService bilibili;

    private final StarBotCommonPainterFactory factory;

    private final LiveDataService liveDataService;

    private final BilibiliWordCloudService wordCloudService;

    @Autowired
    public BilibiliLiveReportPainterFactory(StarBotCoreProperties coreProperties, StarBotBilibiliProperties bilibiliProperties, FontService fontService, BilibiliApiService bilibili, StarBotCommonPainterFactory factory, LiveDataService liveDataService, BilibiliWordCloudService wordCloudService) {
        this.coreProperties = coreProperties;
        this.bilibiliProperties = bilibiliProperties;
        this.fontService = fontService;
        this.bilibili = bilibili;
        this.factory = factory;
        this.liveDataService = liveDataService;
        this.wordCloudService = wordCloudService;
    }

    /**
     * 创建直播报告绘图器
     * @param up UP 主信息
     * @param config 直播报告配置
     * @return 直播报告绘图器
     */
    public BilibiliLiveReportPainter create(Up up, BilibiliLiveReportConfig config) {
        return new BilibiliLiveReportPainter(coreProperties, bilibiliProperties, fontService, bilibili, factory, liveDataService, wordCloudService, up, config);
    }
}
