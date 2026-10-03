package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportConfig;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.BilibiliWordCloudUtil;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.service.LiveDataReadSession;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliLiveReportReadSessionTest {
    @Test
    void emptyPinnedSeriesDoesNotFallBackToTheNextLiveSession() {
        LiveDataService service = mock(LiveDataService.class);
        LiveDataReadSession reading = mock(LiveDataReadSession.class);
        when(service.openReadSession("bilibili", 1L)).thenReturn(Optional.of(reading));
        assertEquals(Optional.of("report"), painter(service).paint());
        verify(reading).forEachEvent(eq("Like"), any());
        verify(reading).close();
        verify(service, never()).getLike(anyString(), anyLong(), any());
        verify(service, never()).forEachEvent(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void failedReportAlwaysReleasesItsPinnedSession() {
        LiveDataService service = mock(LiveDataService.class);
        LiveDataReadSession reading = mock(LiveDataReadSession.class);
        when(service.openReadSession("bilibili", 1L)).thenReturn(Optional.of(reading));
        doThrow(new IllegalStateException("simulated report failure")).when(reading).forEachEvent(eq("Like"), any());
        assertTrue(painter(service).paint().isEmpty());
        verify(reading).close();
    }

    private BilibiliLiveReportPainter painter(LiveDataService service) {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getDynamic().setDrawLogo(false);
        CommonPainter canvas = mock(CommonPainter.class, RETURNS_SELF);
        when(canvas.base64()).thenReturn(Optional.of("report"));
        StarBotCommonPainterFactory factory = mock(StarBotCommonPainterFactory.class);
        when(factory.create(anyInt(), anyInt(), anyBoolean())).thenReturn(canvas);
        FontUtil fonts = mock(FontUtil.class);
        when(fonts.parseFont(anyString())).thenReturn(Optional.empty());
        BilibiliLiveReportConfig config = new BilibiliLiveReportConfig();
        config.setSequence(new ArrayList<>(List.of("likeAnalysis")));
        config.setEnableLikeAnalysis(true);
        return new BilibiliLiveReportPainter(properties, fonts, mock(BilibiliApiUtil.class), factory, service,
                mock(BilibiliWordCloudUtil.class), new Up(1L, "streamer", 3L), config);
    }
}
