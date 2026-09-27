package com.starlwr.bot.bilibili.factory;

import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.FontUtil;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Bilibili 动态绘图器工厂
 */
@StarBotComponent
public class BilibiliDynamicPainterFactory {
    private final StarBotCoreProperties properties;

    private final FontUtil fontUtil;

    private final BilibiliApiUtil bilibili;

    private final StarBotCommonPainterFactory factory;

    @Autowired
    public BilibiliDynamicPainterFactory(StarBotCoreProperties properties, FontUtil fontUtil, BilibiliApiUtil bilibili, StarBotCommonPainterFactory factory) {
        this.properties = properties;
        this.fontUtil = fontUtil;
        this.bilibili = bilibili;
        this.factory = factory;
    }

    /**
     * 创建动态绘图器
     * @param dynamic 动态信息
     * @return 动态绘图器
     */
    public BilibiliDynamicPainter create(Dynamic dynamic) {
        return new BilibiliDynamicPainter(properties, fontUtil, bilibili, factory, dynamic);
    }
}
