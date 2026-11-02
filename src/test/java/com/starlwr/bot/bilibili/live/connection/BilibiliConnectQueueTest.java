package com.starlwr.bot.bilibili.live.connection;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliConnectQueueTest {
    @Test void blockedTasksKeepTheirEntriesAndNewTasksJoinWithoutReordering() throws Exception {
        var properties = new StarBotBilibiliProperties(); properties.getLive().setLiveRoomConnectInterval(1);
        var breaker = mock(Danmu352CircuitBreaker.class);
        var blocked = new AtomicBoolean(true);
        var checked = new CountDownLatch(2);
        when(breaker.isOpen()).thenAnswer(i -> blocked.get());
        when(breaker.nextRetryHintMillis()).thenReturn(20L);
        when(breaker.tryAcquireExecutionSlot()).thenAnswer(i -> { checked.countDown(); return !blocked.get(); });
        var service = new BilibiliLiveRoomConnectTaskService(properties, breaker);
        var order = new CopyOnWriteArrayList<Integer>(); var ran = new CountDownLatch(98);
        var connectors = new ArrayList<BilibiliLiveRoomConnector>();
        for (int i=0;i<98;i++) { final int n=i; var c=mock(BilibiliLiveRoomConnector.class);
            doAnswer(x->{order.add(n);ran.countDown();return null;}).when(c).connect();connectors.add(c); }
        try {
            service.add(connectors.get(0)); service.add(connectors.get(1));
            Map<?,?> pending = (Map<?,?>)ReflectionTestUtils.getField(service,"pending");
            Object first = pending.get(connectors.get(0)), second = pending.get(connectors.get(1));
            service.onStarBotDataSourceLoadCompleteEvent();
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertSame(first,pending.get(connectors.get(0))); assertSame(second,pending.get(connectors.get(1)));
            assertFalse(service.add(connectors.get(0)));
            for (int i=2;i<98;i++) assertTrue(service.add(connectors.get(i)));
            assertEquals(98,service.pendingCount());
            blocked.set(false); assertTrue(ran.await(3,TimeUnit.SECONDS));
            assertEquals(java.util.stream.IntStream.range(0,98).boxed().toList(),order);
            assertEquals(0,service.pendingCount());
        } finally { service.close(); }
    }
}
