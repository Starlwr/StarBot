package com.starlwr.bot.bilibili.dynamic;

import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.painter.FontService;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Bilibili 动态绘图器工厂
 */
@StarBotComponent
public class BilibiliDynamicPainterFactory {
    private final StarBotCoreProperties properties;

    private final FontService fontService;

    private final BilibiliApiService bilibili;

    private final StarBotCommonPainterFactory factory;

    @Autowired
    public BilibiliDynamicPainterFactory(StarBotCoreProperties properties, FontService fontService, BilibiliApiService bilibili, StarBotCommonPainterFactory factory) {
        this.properties = properties;
        this.fontService = fontService;
        this.bilibili = bilibili;
        this.factory = factory;
    }

    /**
     * 创建动态绘图器
     * @param dynamic 动态信息
     * @return 动态绘图器
     */
    public BilibiliDynamicPainter create(Dynamic dynamic) {
        return new BilibiliDynamicPainter(properties, fontService, bilibili, factory, dynamic);
    }
}
