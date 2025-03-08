package com.starlwr.bot.bilibili;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan("com.starlwr.bot")
@SpringBootApplication
public class StarBotBilibiliApplication {
    public static void main(String[] args) {
        SpringApplication.run(StarBotBilibiliApplication.class, args);
    }
}
