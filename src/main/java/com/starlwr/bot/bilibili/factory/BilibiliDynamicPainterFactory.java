package com.starlwr.bot.bilibili.factory;

import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Bilibili 动态绘图器工厂
 */
@Component
public class BilibiliDynamicPainterFactory {
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 创建动态绘图器
     * @param dynamic 动态信息
     * @return 动态绘图器
     */
    public BilibiliDynamicPainter create(Dynamic dynamic) {
        return applicationContext.getBean(BilibiliDynamicPainter.class, dynamic);
    }
}
