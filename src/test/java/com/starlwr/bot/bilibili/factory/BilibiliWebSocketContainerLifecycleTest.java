package com.starlwr.bot.bilibili.factory;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Up;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliWebSocketContainerLifecycleTest {
    @Test void factoryReleasesOwnedTomcatContainerOnceAndRejectsFurtherConnectors() throws Exception {
        BilibiliLiveRoomConnectorFactory factory = new BilibiliLiveRoomConnectorFactory(null,null,null,
                new StarBotBilibiliProperties(),null,null,null,null,null,null,null,null);
        WsWebSocketContainer actual = (WsWebSocketContainer)ReflectionTestUtils.getField(factory,"container");
        actual.destroy();
        WsWebSocketContainer owned = mock(WsWebSocketContainer.class);
        ReflectionTestUtils.setField(factory,"container",owned);
        factory.close(); factory.close();
        verify(owned,times(1)).destroy();
        assertThrows(IllegalStateException.class,() -> factory.create(new Up(1L,"test",2L)));
    }
}
