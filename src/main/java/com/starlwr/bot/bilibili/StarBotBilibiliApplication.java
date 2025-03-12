package com.starlwr.bot.bilibili;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableRetry
@EnableScheduling
@ComponentScan("com.starlwr.bot")
@SpringBootApplication
public class StarBotBilibiliApplication {
    public static void main(String[] args) {
        SpringApplication.run(StarBotBilibiliApplication.class, args);
    }
}
