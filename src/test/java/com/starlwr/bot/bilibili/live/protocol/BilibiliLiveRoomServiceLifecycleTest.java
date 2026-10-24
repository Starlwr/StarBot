package com.starlwr.bot.bilibili.live.protocol;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnectTaskService;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnector;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomService;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnector;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomService;
import com.starlwr.bot.bilibili.live.connection.BilibiliLiveRoomConnectorFactory;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.core.live.LiveDataService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliLiveRoomServiceLifecycleTest {
    @Test void removingPendingReconnectStillDisconnectsExistingTransport() {
        BilibiliLiveRoomConnectTaskService tasks = mock(BilibiliLiveRoomConnectTaskService.class);
        BilibiliLiveRoomConnector connector = mock(BilibiliLiveRoomConnector.class);
        BilibiliLiveRoomConnectorFactory factory = mock(BilibiliLiveRoomConnectorFactory.class);
        LiveDataService data = mock(LiveDataService.class);
        Up up = new Up("1", "test", "2");
        when(factory.create(up)).thenReturn(connector); when(tasks.remove(connector)).thenReturn(true);
        BilibiliLiveRoomService service = new BilibiliLiveRoomService(new StarBotBilibiliProperties(), mock(BilibiliApiService.class),factory,tasks,data);
        service.addUp(up); service.removeUp(up);
        verify(connector).disconnect();
        verify(data).deactivate("bilibili","1","datasource_removed");
        assertFalse(service.hasUp("1"));
    }
    @Test void shutdownClosesEveryConnectorEvenWhenAnEventListenerFails() {
        BilibiliLiveRoomConnectorFactory factory = mock(BilibiliLiveRoomConnectorFactory.class);
        BilibiliLiveRoomConnector first = mock(BilibiliLiveRoomConnector.class), second = mock(BilibiliLiveRoomConnector.class);
        Up one = new Up("1", "one", "11"), two = new Up("2", "two", "22");
        when(factory.create(one)).thenReturn(first); when(factory.create(two)).thenReturn(second);
        doThrow(new IllegalStateException("simulated listener failure")).when(first).disconnect();
        BilibiliLiveRoomService service = new BilibiliLiveRoomService(new StarBotBilibiliProperties(),mock(BilibiliApiService.class),factory,
                mock(BilibiliLiveRoomConnectTaskService.class),mock(LiveDataService.class));
        service.addUp(one); service.addUp(two);
        assertThrows(IllegalStateException.class,service::close);
        verify(first).disconnect(); verify(second).disconnect();
        assertFalse(service.hasUp("1")); assertFalse(service.hasUp("2"));
        service.close(); service.addUp(one);
        verify(factory,times(1)).create(one);
    }
}
