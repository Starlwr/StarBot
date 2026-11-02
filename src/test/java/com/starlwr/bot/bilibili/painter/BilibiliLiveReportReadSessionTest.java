package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.report.BilibiliLiveReportConfig;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.report.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.report.BilibiliWordCloudService;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.live.LiveDataReadSession;
import com.starlwr.bot.core.live.LiveDataService;
import com.starlwr.bot.core.painter.FontService;
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
        when(service.openReadSession("bilibili", "1")).thenReturn(Optional.of(reading));
        assertEquals(Optional.of("report"), painter(service).paint());
        verify(reading).forEachEvent(eq("Like"), any());
        verify(reading).close();
        verify(service, never()).getLike(anyString(), anyString(), any());
        verify(service, never()).forEachEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void failedReportAlwaysReleasesItsPinnedSession() {
        LiveDataService service = mock(LiveDataService.class);
        LiveDataReadSession reading = mock(LiveDataReadSession.class);
        when(service.openReadSession("bilibili", "1")).thenReturn(Optional.of(reading));
        doThrow(new IllegalStateException("simulated report failure")).when(reading).forEachEvent(eq("Like"), any());
        assertTrue(painter(service).paint().isEmpty());
        verify(reading).close();
    }

    private BilibiliLiveReportPainter painter(LiveDataService service) {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        com.starlwr.bot.core.config.StarBotCoreProperties coreProperties = new com.starlwr.bot.core.config.StarBotCoreProperties();
        coreProperties.getPaint().setDrawLogo(false);
        CommonPainter canvas = mock(CommonPainter.class, RETURNS_SELF);
        when(canvas.base64()).thenReturn(Optional.of("report"));
        StarBotCommonPainterFactory factory = mock(StarBotCommonPainterFactory.class);
        when(factory.create(anyInt(), anyInt(), anyBoolean())).thenReturn(canvas);
        FontService fonts = mock(FontService.class);
        when(fonts.parseFont(anyString())).thenReturn(Optional.empty());
        BilibiliLiveReportConfig config = new BilibiliLiveReportConfig();
        config.setSequence(new ArrayList<>(List.of("likeAnalysis")));
        config.setEnableLikeAnalysis(true);
        return new BilibiliLiveReportPainter(coreProperties, properties, fonts, mock(BilibiliApiService.class), factory, service,
                mock(BilibiliWordCloudService.class), new Up("1", "streamer", "3"), config);
    }
}
