package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.factory.BilibiliLiveRoomConnectorFactory;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliLiveRoomServiceLifecycleTest {
    @Test void removingPendingReconnectStillDisconnectsExistingTransport() {
        BilibiliLiveRoomConnectTaskService tasks = mock(BilibiliLiveRoomConnectTaskService.class);
        BilibiliLiveRoomConnector connector = mock(BilibiliLiveRoomConnector.class);
        BilibiliLiveRoomConnectorFactory factory = mock(BilibiliLiveRoomConnectorFactory.class);
        LiveDataService data = mock(LiveDataService.class);
        Up up = new Up(1L,"test",2L);
        when(factory.create(up)).thenReturn(connector); when(tasks.remove(connector)).thenReturn(true);
        BilibiliLiveRoomService service = new BilibiliLiveRoomService(new StarBotBilibiliProperties(), mock(BilibiliApiUtil.class),factory,tasks,data);
        service.addUp(up); service.removeUp(up);
        verify(connector).disconnect();
        verify(data).deactivate("bilibili",1L,"datasource_removed");
        assertFalse(service.hasUp(1L));
    }
    @Test void shutdownClosesEveryConnectorEvenWhenAnEventListenerFails() {
        BilibiliLiveRoomConnectorFactory factory = mock(BilibiliLiveRoomConnectorFactory.class);
        BilibiliLiveRoomConnector first = mock(BilibiliLiveRoomConnector.class), second = mock(BilibiliLiveRoomConnector.class);
        Up one = new Up(1L,"one",11L), two = new Up(2L,"two",22L);
        when(factory.create(one)).thenReturn(first); when(factory.create(two)).thenReturn(second);
        doThrow(new IllegalStateException("simulated listener failure")).when(first).disconnect();
        BilibiliLiveRoomService service = new BilibiliLiveRoomService(new StarBotBilibiliProperties(),mock(BilibiliApiUtil.class),factory,
                mock(BilibiliLiveRoomConnectTaskService.class),mock(LiveDataService.class));
        service.addUp(one); service.addUp(two);
        assertThrows(IllegalStateException.class,service::close);
        verify(first).disconnect(); verify(second).disconnect();
        assertFalse(service.hasUp(1L)); assertFalse(service.hasUp(2L));
        service.close(); service.addUp(one);
        verify(factory,times(1)).create(one);
    }
}
