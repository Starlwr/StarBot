package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
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
        when(connector.getUp()).thenReturn(new com.starlwr.bot.bilibili.model.Up(1L,"test",2L));
        Semaphore ran = new Semaphore(0);
        doAnswer(ignored -> { ran.release(); return null; }).when(connector).connect();
        BilibiliLiveRoomConnectTaskService scheduler = new BilibiliLiveRoomConnectTaskService(properties);
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
        BilibiliLiveRoomConnectTaskService service = new BilibiliLiveRoomConnectTaskService(new StarBotBilibiliProperties());
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
