package com.starlwr.bot.bilibili.config;

import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Properties;

@TestConfiguration
public class TestBuildPropertiesConfig {
    @Bean
    public BuildProperties buildProperties() {
        Properties props = new Properties();
        props.put("version", "test");
        return new BuildProperties(props);
    }
}
