package com.starlwr.bot.bilibili.factory;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.service.BilibiliEventParser;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomConnectTaskService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomConnector;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bilibili 直播间连接器工厂
 */
@StarBotComponent
public class BilibiliLiveRoomConnectorFactory {
    private final ThreadPoolTaskExecutor executor;

    private final TaskScheduler taskScheduler;

    private final ApplicationEventPublisher eventPublisher;

    private final StarBotBilibiliProperties properties;

    private final LiveDataService liveDataService;

    private final BilibiliAccountService accountService;

    private final BilibiliLiveRoomConnectTaskService taskService;

    private final BilibiliEventParser eventParser;

    private final BilibiliApiUtil bilibili;

    private final BilibiliNetworkLogger networkLog;

    @Autowired
    public BilibiliLiveRoomConnectorFactory(@Qualifier("bilibiliThreadPool") ThreadPoolTaskExecutor executor, TaskScheduler taskScheduler, ApplicationEventPublisher eventPublisher, StarBotBilibiliProperties properties, LiveDataService liveDataService, BilibiliAccountService accountService, BilibiliLiveRoomConnectTaskService taskService, BilibiliEventParser eventParser, BilibiliApiUtil bilibili, BilibiliNetworkLogger networkLog) {
        this.executor = executor;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.liveDataService = liveDataService;
        this.accountService = accountService;
        this.taskService = taskService;
        this.eventParser = eventParser;
        this.bilibili = bilibili;
        this.networkLog = networkLog;
    }

    /**
     * 创建直播间连接器
     * @param up UP 主
     * @return 直播间连接器
     */
    public BilibiliLiveRoomConnector create(Up up) {
        return new BilibiliLiveRoomConnector(executor, taskScheduler, eventPublisher, properties, liveDataService, accountService, taskService, eventParser, bilibili, networkLog, up);
    }
}
