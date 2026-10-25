package com.starlwr.bot.bilibili.live.protocol;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnectTaskService;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnector;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliConnectSchedulerLifecycleTest {
    @Test void zeroDelayTasksLeaveNoCompletedPendingEntryAndCanBeRescheduled() throws Exception {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getLive().setLiveRoomConnectInterval(1);
        BilibiliLiveRoomConnector connector = mock(BilibiliLiveRoomConnector.class);
        when(connector.getUp()).thenReturn(new com.starlwr.bot.bilibili.api.model.Up("1","test","2"));
        Semaphore ran = new Semaphore(0);
        doAnswer(ignored -> { ran.release(); return null; }).when(connector).connect();
        com.starlwr.bot.bilibili.live.connection.Danmu352CircuitBreaker breaker =
                mock(com.starlwr.bot.bilibili.live.connection.Danmu352CircuitBreaker.class);
        when(breaker.tryAcquireExecutionSlot()).thenReturn(true);
        BilibiliLiveRoomConnectTaskService scheduler = new BilibiliLiveRoomConnectTaskService(properties, breaker);
        try {
            scheduler.onStarBotDataSourceLoadCompleteEvent();
            for(int i=0;i<100;i++) {
                assertTrue(scheduler.schedule(connector,0));
                assertTrue(ran.tryAcquire(5,TimeUnit.SECONDS));
                assertEquals(0,scheduler.pendingCount());
            }
        } finally { scheduler.close(); }
    }

    @Test void cancellingDelayedTasksRemovesThemFromTheTimerQueue() {
        BilibiliLiveRoomConnectTaskService service = new BilibiliLiveRoomConnectTaskService(new StarBotBilibiliProperties(), mock(com.starlwr.bot.bilibili.live.connection.Danmu352CircuitBreaker.class));
        try {
            service.onStarBotDataSourceLoadCompleteEvent();
            BilibiliLiveRoomConnector connector = mock(BilibiliLiveRoomConnector.class);
            assertTrue(service.schedule(connector, 3_600_000));
            assertTrue(service.remove(connector));
            assertEquals(0, service.pendingCount());
            ScheduledThreadPoolExecutor timer = (ScheduledThreadPoolExecutor)ReflectionTestUtils.getField(service,"scheduler");
            assertTrue(timer.getQueue().isEmpty());
            verify(connector, never()).connect();
        } finally { service.close(); }
    }
}
