package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * StarBotBilibili Thymeleaf 配置类
 */
@Configuration
@StarBotComponent
public class StarBotBilibiliThymeleafConfig {
    @Resource
    private SpringTemplateEngine templateEngine;

    @PostConstruct
    public void registerPluginTemplateResolver() {
        ClassLoaderTemplateResolver pluginResolver = new ClassLoaderTemplateResolver(getClass().getClassLoader());

        pluginResolver.setPrefix("templates/");
        pluginResolver.setSuffix(".html");
        pluginResolver.setTemplateMode(TemplateMode.HTML);
        pluginResolver.setCharacterEncoding("UTF-8");
        pluginResolver.setCheckExistence(true);

        templateEngine.addTemplateResolver(pluginResolver);
    }
}
