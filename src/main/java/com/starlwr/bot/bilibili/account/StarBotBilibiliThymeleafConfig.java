package com.starlwr.bot.bilibili.account;

import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * StarBotBilibili Thymeleaf 配置类
 */
@StarBotComponent
public class StarBotBilibiliThymeleafConfig {
    private final SpringTemplateEngine templateEngine;

    @Autowired
    public StarBotBilibiliThymeleafConfig(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

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
