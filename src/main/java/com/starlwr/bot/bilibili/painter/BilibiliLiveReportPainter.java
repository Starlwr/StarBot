package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSONObject;
import com.huaban.analysis.jieba.SegToken;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardType;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportConfig;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.BilibiliWordCloudUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.painter.ChartPainter;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.painter.WordCloudPainter;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.CollectionUtil;
import com.starlwr.bot.core.util.FontUtil;
import com.starlwr.bot.core.util.ImageUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.util.Pair;
import org.springframework.util.CollectionUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bilibili 直播报告绘图器
 */
@Slf4j
public class BilibiliLiveReportPainter {
    private final StarBotBilibiliProperties properties;

    private final BilibiliApiUtil bilibili;

    private final LiveDataService liveDataService;

    private final BilibiliWordCloudUtil wordCloudUtil;

    private final Up up;

    private final BilibiliLiveReportConfig config;

    private final ResourceLoader resourceLoader;

    private final CommonPainter painter;

    private final Font font;

    private final int WIDTH = 1000;

    private final int MARGIN = 50;

    private final int CHART_WIDTH = WIDTH - MARGIN * 2;

    private final Color COLOR_DEEP_PURPLE = new Color(121, 86, 237);

    private final Color COLOR_MIDDLE_PURPLE = new Color(176, 164, 227);

    private final Color COLOR_LIGHT_PURPLE = new Color(233, 231, 239);

    private final Color COLOR_GUARD_GOVERNOR = new Color(220, 20, 60);

    private final Color COLOR_GUARD_COMMANDER = new Color(255, 0, 255);

    private final Color COLOR_GUARD_CAPTAIN = new Color(0, 191, 255);

    private final Color COLOR_PINK = new Color(251, 114, 153);

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Map<String, Runnable> drawMethods = Map.of(
            "basicInfo", this::drawBasicInfo,
            "changeInfo", this::drawChangeInfo,
            "danmuAnalysis", this::drawDanmuAnalysis,
            "boxAnalysis", this::drawBoxAnalysis,
            "giftAnalysis", this::drawGiftAnalysis,
            "superChatAnalysis", this::drawSuperChatAnalysis,
            "guardAnalysis", this::drawGuardAnalysis
    );

    public BilibiliLiveReportPainter(StarBotBilibiliProperties properties, FontUtil fontUtil, BilibiliApiUtil bilibili, StarBotCommonPainterFactory factory, LiveDataService liveDataService, BilibiliWordCloudUtil wordCloudUtil, Up up, BilibiliLiveReportConfig config) {
        this.properties = properties;
        this.bilibili = bilibili;
        this.liveDataService = liveDataService;
        this.wordCloudUtil = wordCloudUtil;
        this.up = up;
        this.config = config;

        this.resourceLoader = new DefaultResourceLoader(getClass().getClassLoader());
        this.painter = factory.create(WIDTH, 5000, true);
        this.font = fontUtil.parseFont("内置").map(f -> f.deriveFont(Font.PLAIN, 25)).orElse(new Font(Font.SANS_SERIF, Font.PLAIN, 25));

        config.getSequence().removeIf(part -> {
            boolean isUnsupportedModule = !drawMethods.containsKey(part);
            if (isUnsupportedModule) {
                log.warn("已移除不支持的直播报告模块: {}", part);
            }
            return isUnsupportedModule;
        });
    }

    /**
     * 根据给定直播报告配置生成直播报告图片
     * @return 直播报告图片的 Base64 字符串
     */
    public Optional<String> paint() {
        return paint(null);
    }

    /**
     * 根据给定直播报告配置生成直播报告图片
     * @param path 图片保存路径
     * @return 直播报告图片的 Base64 字符串
     */
    public Optional<String> paint(String path) {
        try {
            drawLogo();

            List<String> sequence = new ArrayList<>();
            sequence.add("basicInfo");
            sequence.addAll(config.getSequence());
            for (String part : sequence) {
                drawMethods.get(part).run();
            }

            drawBottom();
            drawBackground();
        } catch (Exception e) {
            log.error("绘制 {}(UID: {}, 房间号: {}) 的直播报告图片失败", up.getUname(), up.getUid(), up.getRoomIdString(), e);
            return Optional.empty();
        }

        if (StringUtil.isBlank(path)) {
            return this.painter.base64();
        } else {
            return this.painter.saveAndGetBase64(path);
        }
    }

    /**
     * 绘制 StarBot Logo
     */
    private void drawLogo() {
        if (properties.getDynamic().isDrawLogo()) {
            try {
                BufferedImage logo = ImageIO.read(resourceLoader.getResource("classpath:logo.png").getInputStream());
                this.painter.drawImage(logo, new Point(200, 55)).setPos(MARGIN, 275);
            } catch (IOException e) {
                log.error("加载 StarBot Logo 失败", e);
            }
        } else {
            this.painter.setPos(MARGIN, 50);
        }
    }

    /**
     * 绘制基础信息模块
     */
    private void drawBasicInfo() {
        if (!config.isEnableBasicInfo()) {
            return;
        }

        // 基础信息
        List<String> infoLines = new ArrayList<>();
        infoLines.add(up.getUname() + "(" + up.getRoomIdString() + ")");

        String liveArea = "";
        String liveTitle = "";
        String liveTime = "";

        // 房间信息
        if (config.isShowLiveArea() || config.isShowLiveTitle()) {
            try {
                Room room = bilibili.getLiveInfoByRoomId(up.getRoomId());
                liveArea = room.getAreaName();
                liveTitle = room.getTitle();
            } catch (Exception e) {
                log.error("获取 {}(UID: {}, 房间号: {}) 的直播信息失败", up.getUname(), up.getUid(), up.getRoomIdString(), e);
            }
        }

        // 直播时长
        if (config.isShowLiveTime()) {
            Optional<Long> optionalStartTime = liveDataService.getLiveStartTime(LivePlatform.BILIBILI.getName(), up.getUid());
            Optional<Long> optionalEndTime = liveDataService.getLiveEndTime(LivePlatform.BILIBILI.getName(), up.getUid());
            String startTime = "?";
            String endTime = "?";
            if (optionalStartTime.isPresent()) {
                startTime = formatter.format(Instant.ofEpochMilli(optionalStartTime.get()));
            }
            if (optionalEndTime.isPresent()) {
                endTime = formatter.format(Instant.ofEpochMilli(optionalEndTime.get()));
            }

            long hours;
            long minutes;
            long seconds;
            String time = "";
            if (optionalStartTime.isPresent() && optionalEndTime.isPresent()) {
                long duration = (optionalEndTime.get() - optionalStartTime.get()) / 1000;
                hours = duration / 3600;
                minutes = (duration % 3600) / 60;
                seconds = duration % 60;
                if (hours > 0) {
                    time += hours + " 时 ";
                }
                if (minutes > 0) {
                    time += minutes + " 分 ";
                }
                if (seconds > 0) {
                    time += seconds + " 秒";
                }
                time = time.trim();
            }

            if (StringUtil.isBlank(time)) {
                liveTime = startTime + " ~ " + endTime;
            } else {
                liveTime = startTime + " ~ " + endTime + " (" + time + ")";
            }
        }

        // 组织文字内容
        if (config.isShowLiveArea() && config.isShowLiveTime()) {
            if (StringUtil.isBlank(liveArea)) {
                infoLines.add(liveTime);
            } else {
                infoLines.add(liveArea + "  |  " + liveTime);
            }
        } else {
            if (config.isShowLiveArea() && StringUtil.isNotBlank(liveArea)) {
                infoLines.add(liveArea);
            }
            if (config.isShowLiveTime()) {
                infoLines.add(liveTime);
            }
        }

        if (config.isShowLiveTitle() && StringUtil.isNotBlank(liveTitle)) {
            infoLines.add(liveTitle);
        }

        // 计算最大宽度
        int maxWidth = 0;
        for (int i = 0; i < infoLines.size(); i++) {
            String line = infoLines.get(i);
            Pair<Integer, Integer> size;
            if (i == 0) {
                size = this.painter.getStringWidthAndHeight(line, CommonPainter.TEXT_FONT_SIZE);
            } else {
                size = this.painter.getStringWidthAndHeight(line, CommonPainter.TIP_FONT_SIZE);
            }
            maxWidth = Math.max(maxWidth, size.getFirst());
        }
        maxWidth = maxWidth + 150;
        int marginX = Math.max(MARGIN, (this.painter.getWidth() - maxWidth) / 2);

        // 绘制头像
        int faceSize = 125;
        Point facePoint = new Point(marginX, this.painter.getY());
        Optional<BufferedImage> optionalFace = bilibili.getBilibiliImage(up.getFace());
        optionalFace.ifPresent(face -> this.painter.drawImage(ImageUtil.maskToCircle(ImageUtil.resize(face, faceSize, faceSize)), facePoint));

        // 绘制文字
        int textHeight = 30 + 25 * (infoLines.size() - 1);
        int lineMargin = (faceSize - textHeight) / (infoLines.size() - 1);
        for (int i = 0; i < infoLines.size(); i++) {
            String text = infoLines.get(i);
            if (i == 0) {
                this.painter.drawText(text, new Point(marginX + faceSize + 25, this.painter.getY()));
            } else {
                this.painter.drawTip(text, new Point(marginX + faceSize + 25, this.painter.getY() + 5 + i * (25 + lineMargin)));
            }
        }

        this.painter.setPos(MARGIN, this.painter.getY() + faceSize + 50);
    }

    /**
     * 绘制模块标题
     * @param title 模块标题
     */
    private void drawTitle(String title) {
        Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(title, CommonPainter.CHAPTER_FONT_SIZE);

        List<Point> lightPoints = new ArrayList<>();
        lightPoints.add(new Point(this.painter.getX(), this.painter.getY() + 8));
        lightPoints.add(new Point(this.painter.getX() + size.getFirst() + 48, this.painter.getY() + 8));
        lightPoints.add(new Point(this.painter.getX() + size.getFirst() + 64, this.painter.getY() + size.getSecond() + 16));
        lightPoints.add(new Point(this.painter.getX(), this.painter.getY() + size.getSecond() + 16));
        this.painter.drawPolygon(lightPoints, COLOR_LIGHT_PURPLE);

        List<Point> deepPoints = new ArrayList<>();
        deepPoints.add(new Point(this.painter.getX(), this.painter.getY()));
        deepPoints.add(new Point(this.painter.getX() + size.getFirst() + 32, this.painter.getY()));
        deepPoints.add(new Point(this.painter.getX() + size.getFirst() + 48, this.painter.getY() + size.getSecond() + 16));
        deepPoints.add(new Point(this.painter.getX(), this.painter.getY() + size.getSecond() + 16));
        this.painter.drawPolygon(deepPoints, COLOR_MIDDLE_PURPLE);

        this.painter.drawRectangle(this.painter.getX(), this.painter.getY() + size.getSecond() + 12, this.painter.getWidth() - MARGIN * 2, 4, COLOR_LIGHT_PURPLE);

        this.painter.drawChapter(title, new Point(this.painter.getX() + 16, this.painter.getY() + 8));
        this.painter.setPos(MARGIN, this.painter.getY() + size.getSecond() + 50);
    }

    /**
     * 绘制模块标题及提示文字
     *
     * @param title 模块标题
     * @param text  提示文字
     */
    private void drawTitle(String title, String text) {
        int lineY = this.painter.getY() + this.painter.getStringWidthAndHeight(title, CommonPainter.SECTION_FONT_SIZE).getSecond() + 12;
        drawTitle(title);

        if (StringUtil.isNotBlank(text)) {
            Pair<Integer, Integer> textSize = this.painter.getStringWidthAndHeight(text, CommonPainter.TEXT_FONT_SIZE);
            int textX = this.painter.getWidth() - MARGIN * 2 - textSize.getFirst();
            int textY = lineY - textSize.getSecond() - 5;
            this.painter.drawText(text, COLOR_DEEP_PURPLE, new Point(textX, textY));
        }
    }

    /**
     * 绘制模块标题及含格式提示文字
     *
     * @param title 模块标题
     * @param texts  提示文字列表
     */
    private void drawTitle(String title, List<TextWithStyle> texts) {
        int lineY = this.painter.getY() + this.painter.getStringWidthAndHeight(title, CommonPainter.SECTION_FONT_SIZE).getSecond() + 12;
        drawTitle(title);

        String allText = texts.stream().map(TextWithStyle::getText).collect(Collectors.joining());
        if (StringUtil.isNotBlank(allText)) {
            Pair<Integer, Integer> textSize = this.painter.getStringWidthAndHeight(allText, CommonPainter.TEXT_FONT_SIZE);
            int textX = this.painter.getWidth() - MARGIN * 2 - textSize.getFirst();
            int textY = lineY - textSize.getSecond() - 5;
            this.painter.drawTextWithStyle(texts, new Point(textX, textY));
        }
    }

    /**
     * 绘制小节标题
     * @param title 小节标题
     */
    private void drawSection(String title) {
        this.painter.drawTextWithStyle(Arrays.asList(new TextWithStyle("➤  ", CommonPainter.SECTION_FONT_SIZE, COLOR_DEEP_PURPLE), new TextWithStyle(title, CommonPainter.SECTION_FONT_SIZE, Color.BLACK)));
        this.painter.setPos(this.painter.getX(), this.painter.getY() + this.painter.getRowSpace());
    }

    /**
     * 绘制数据变动行
     *
     * @param name   数据名称
     * @param before 变动前数据
     * @param after  变动后数据
     */
    private void drawChangeLine(String name, Integer before, Integer after) {
        int diff = 0;
        if (before != null && after != null) {
            diff = after - before;
        }

        String beforeText = before != null ? String.valueOf(before) : "?";
        String afterText = after != null ? String.valueOf(after) : "?";

        String diffText;
        Color diffColor;
        if (diff > 0) {
            diffText = "(+" + diff + ")";
            diffColor = Color.RED;
        } else if (diff < 0) {
            diffText = "(" + diff + ")";
            diffColor = Color.GREEN;
        } else {
            diffText = "(+0)";
            diffColor = Color.GRAY;
        }

        List<TextWithStyle> texts = new ArrayList<>();
        texts.add(new TextWithStyle(name + ": " + beforeText + " → " + afterText + " ", CommonPainter.TEXT_FONT_SIZE, Color.BLACK));
        texts.add(new TextWithStyle(diffText, CommonPainter.TEXT_FONT_SIZE, diffColor));
        this.painter.drawTextWithStyle(texts);
    }

    /**
     * 绘制数据变动模块（粉丝、粉丝团、大航海）
     */
    private void drawChangeInfo() {
        if (!config.isEnableChangeInfo()) {
            return;
        }

        drawTitle("数据变动");

        String platform = LivePlatform.BILIBILI.getName();
        String uid = String.valueOf(up.getUid());

        Integer beforeFansCount = liveDataService.getCustomObject(Integer.class, platform, "BeforeFansCount", uid).orElse(null);
        Integer afterFansCount = liveDataService.getCustomObject(Integer.class, platform, "AfterFansCount", uid).orElse(null);
        Integer beforeFansMedalCount = liveDataService.getCustomObject(Integer.class, platform, "BeforeFansMedalCount", uid).orElse(null);
        Integer afterFansMedalCount = liveDataService.getCustomObject(Integer.class, platform, "AfterFansMedalCount", uid).orElse(null);
        Integer beforeGuardCount = liveDataService.getCustomObject(Integer.class, platform, "BeforeGuardCount", uid).orElse(null);
        Integer afterGuardCount = liveDataService.getCustomObject(Integer.class, platform, "AfterGuardCount", uid).orElse(null);

        if (config.isShowFansChange()) {
            drawChangeLine("粉丝", beforeFansCount, afterFansCount);
        }
        if (config.isShowFansMedalChange()) {
            drawChangeLine("粉丝团", beforeFansMedalCount, afterFansMedalCount);
        }
        if (config.isShowGuardChange()) {
            drawChangeLine("大航海", beforeGuardCount, afterGuardCount);
        }

        this.painter.setPos(MARGIN, this.painter.getY() + 25);
    }

    /**
     * 加载默认 B 站头像
     */
    private BufferedImage loadDefaultFace() {
        try {
            return ImageIO.read(resourceLoader.getResource("classpath:images/common/face.png").getInputStream());
        } catch (Exception e) {
            log.error("加载默认 B 站头像失败", e);
            return null;
        }
    }

    /**
     * 绘制发送者排行榜
     *
     * @param sectionTitle 小节标题
     * @param sorted       按数值降序排序的发送者统计
     * @param limit        展示前多少名
     * @param userInfos    用户信息
     */
    private void drawSenderRankingChart(String sectionTitle, List<Map.Entry<String, Double>> sorted, int limit, Map<Long, UserInfo> userInfos) {
        List<Map.Entry<String, Double>> top = sorted.stream().limit(limit).toList();
        drawSection(sectionTitle + " (Top " + top.size() + ")");

        List<String> faceUrls = new ArrayList<>();
        List<Long> topUids = new ArrayList<>();
        for (Map.Entry<String, Double> entry : top) {
            Long senderUid = Long.valueOf(entry.getKey());
            topUids.add(senderUid);
            faceUrls.add(userInfos.get(senderUid).getFace());
        }
        List<Optional<BufferedImage>> faces = bilibili.asyncGetBilibiliImages(faceUrls).join();
        BufferedImage defaultFace = loadDefaultFace();

        List<ChartPainter.RankingItem> items = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            items.add(new ChartPainter.RankingItem(faces.get(i).orElse(defaultFace), userInfos.get(topUids.get(i)).getUname(), top.get(i).getValue()));
        }
        ChartPainter.renderRankingChart(items, CHART_WIDTH, font).ifPresent(this.painter::drawImage);
    }

    /**
     * 获取最近一场直播的时间范围
     *
     * @return 时间范围，不可用时返回 null，单位：毫秒
     */
    private long[] getLiveRange() {
        Optional<Long> optionalStartTime = liveDataService.getLiveStartTime(LivePlatform.BILIBILI.getName(), up.getUid());
        Optional<Long> optionalEndTime = liveDataService.getLiveEndTime(LivePlatform.BILIBILI.getName(), up.getUid());
        if (optionalStartTime.isPresent() && optionalEndTime.isPresent() && optionalEndTime.get() > optionalStartTime.get()) {
            return new long[]{optionalStartTime.get(), optionalEndTime.get()};
        }
        return null;
    }

    /**
     * 绘制直播报告曲线图
     *
     * @param samples    曲线图数据点列表
     * @param cumulative 是否为累计曲线
     */
    private void drawLineChart(List<ChartPainter.LinePoint> samples, boolean cumulative) {
        long[] range = getLiveRange();
        ChartPainter.renderLineChart(samples, cumulative, 20, CHART_WIDTH, font, range != null ? range[0] : null, range != null ? range[1] : null).ifPresent(this.painter::drawImage);
    }

    /**
     * 绘制弹幕分析模块
     */
    private void drawDanmuAnalysis() {
        if (!config.isEnableDanmuAnalysis()) {
            return;
        }

        String platform = LivePlatform.BILIBILI.getName();
        Long uid = up.getUid();

        List<JSONObject> danmus = liveDataService.getDanmu(platform, uid, JSONObject.class);
        List<JSONObject> emojis = liveDataService.getEmoji(platform, uid, JSONObject.class);
        List<JSONObject> all = Stream.concat(danmus.stream(), emojis.stream()).toList();
        int count = all.size();

        if (count == 0) {
            return;
        }

        if (config.isShowDanmuDetails()) {
            long senderCount = all.stream().map(json -> json.getLong("sender")).distinct().count();
            String tip = count + " 条 / " + senderCount + " 人";
            drawTitle("弹幕分析", tip);
        } else {
            drawTitle("弹幕分析");
        }

        // 按发送者分组统计弹幕数
        Map<String, Long> countBySender = all.stream().collect(Collectors.groupingBy(json -> json.getString("sender"), Collectors.counting()));
        List<Map.Entry<String, Long>> sorted = countBySender.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();

        // 按需批量获取发送者用户信息
        Map<Long, UserInfo> userInfos = config.getDanmuRankingLimit() > 0 || config.isShowDanmuSenderDistributionChart()
                ? bilibili.getUserInfoByUids(countBySender.keySet().stream().map(Long::valueOf).collect(Collectors.toSet()))
                : Map.of();

        // 弹幕排行榜
        if (config.getDanmuRankingLimit() > 0) {
            drawSenderRankingChart("弹幕排行榜", sorted.stream().map(entry -> Map.entry(entry.getKey(), (double) entry.getValue())).toList(), config.getDanmuRankingLimit(), userInfos);
        }

        if (config.isShowDanmuGrowthChart() || config.isShowDanmuInteractionChart()) {
            List<ChartPainter.LinePoint> samples = all.stream()
                    .map(json -> new ChartPainter.LinePoint(json.getLongValue("timestamp"), 1.0))
                    .toList();

            // 弹幕累计曲线图
            if (config.isShowDanmuGrowthChart()) {
                drawSection("弹幕累计曲线图");
                drawLineChart(samples, true);
            }

            // 弹幕互动曲线图
            if (config.isShowDanmuInteractionChart()) {
                drawSection("弹幕互动曲线图");
                drawLineChart(samples, false);
            }
        }

        // 弹幕类型分布图
        if (config.isShowDanmuTypeDistributionChart()) {
            drawSection("弹幕类型分布图");

            List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
            slices.add(new ChartPainter.DistributionSlice("普通弹幕", danmus.size()));
            slices.add(new ChartPainter.DistributionSlice("表情弹幕", emojis.size()));
            ChartPainter.renderDistributionChart(slices, CHART_WIDTH, font).ifPresent(this.painter::drawImage);
        }

        // 发送弹幕观众分布图
        if (config.isShowDanmuSenderDistributionChart()) {
            List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
            long otherCount = 0;
            for (Map.Entry<String, Long> entry : sorted) {
                if ((double) entry.getValue() / all.size() > 0.05) {
                    slices.add(new ChartPainter.DistributionSlice(userInfos.get(Long.valueOf(entry.getKey())).getUname(), entry.getValue()));
                } else {
                    otherCount += entry.getValue();
                }
            }
            if (otherCount > 0) {
                slices.add(new ChartPainter.DistributionSlice("其他观众", otherCount));
            }

            drawSection("发送弹幕观众分布图");
            ChartPainter.renderDistributionChart(slices, CHART_WIDTH, font).ifPresent(this.painter::drawImage);
        }

        // 弹幕词云
        if (config.isShowDanmuWordCloud()) {
            drawSection("弹幕词云");
            int wordCloudLimit = properties.getLive().getWordCloudLimit();
            if (wordCloudLimit > 0) {
                Map<String, Long> frequency = new HashMap<>();
                boolean debugEnabled = properties.getDebug().isWordCloudDebug();
                StringBuilder debug = new StringBuilder();
                long danmuCount = 0;
                long totalTokens = 0;

                for (JSONObject danmu : danmus) {
                    String contentText = danmu.getString("contentText");
                    if (StringUtil.isBlank(contentText)) {
                        continue;
                    }

                    danmuCount++;
                    List<SegToken> tokens = wordCloudUtil.segment(contentText);
                    totalTokens += tokens.size();

                    if (debugEnabled) {
                        debug.append("原文: ").append(contentText).append("\n");
                        debug.append("切词: ").append(tokens.stream().map(token -> token.word).collect(Collectors.joining(" | "))).append("\n");
                    }

                    for (SegToken token : tokens) {
                        if (StringUtil.isNotBlank(token.word)) {
                            frequency.merge(token.word, 1L, Long::sum);
                        }
                    }
                }

                List<WordCloudPainter.WordItem> words = frequency.entrySet().stream()
                        .map(entry -> new WordCloudPainter.WordItem(entry.getKey(), entry.getValue()))
                        .toList();
                WordCloudPainter.renderWordCloud(words, CHART_WIDTH, 500, font, wordCloudLimit).ifPresent(this.painter::drawImage);

                if (debugEnabled) {
                    try {
                        debug.append("统计: 弹幕数 ").append(danmuCount)
                                .append(", 词条数 ").append(totalTokens)
                                .append(", 去重词数 ").append(frequency.size()).append("\n");

                        debug.append("词频排序(降序):\n");
                        frequency.entrySet().stream()
                                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                                .forEach(entry -> debug.append(entry.getKey())
                                        .append("(").append(entry.getValue()).append(")").append("\n"));

                        Path directory = Paths.get("WordCloudDebug");
                        Files.createDirectories(directory);
                        DateTimeFormatter fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
                        Path file = directory.resolve("word-cloud-debug-" + up.getUname() + "-" + fileTimestamp.format(LocalDateTime.now()) + ".log");
                        Files.write(file, debug.toString().getBytes(StandardCharsets.UTF_8));
                        log.info("弹幕词云分词调试结果已输出至: {}", file.toAbsolutePath());
                    } catch (IOException e) {
                        log.error("输出弹幕词云分词调试结果失败", e);
                    }
                }
            }
        }

        this.painter.setPos(MARGIN, this.painter.getY() + 25);
    }

    /**
     * 获取一条盲盒记录包含的盲盒数量
     *
     * @param box 盲盒记录
     * @return 盲盒数量
     */
    private long getBoxCount(JSONObject box) {
        JSONObject randomGiftInfo = box.getJSONObject("randomGiftInfo");
        Integer count = randomGiftInfo != null ? randomGiftInfo.getInteger("count") : null;
        return count != null ? count : 1;
    }

    /**
     * 绘制盲盒分析模块
     */
    private void drawBoxAnalysis() {
        if (!config.isEnableBoxAnalysis()) {
            return;
        }

        String platform = LivePlatform.BILIBILI.getName();
        Long uid = up.getUid();

        List<JSONObject> boxes = liveDataService.getRandomGift(platform, uid, JSONObject.class);
        if (CollectionUtils.isEmpty(boxes)) {
            return;
        }

        List<TextWithStyle> tips = new ArrayList<>();
        if (config.isShowBoxDetails()) {
            long totalCount = boxes.stream().mapToLong(this::getBoxCount).sum();
            tips.add(new TextWithStyle(totalCount + " 个", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
            tips.add(new TextWithStyle(" / ", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
            long senderCount = boxes.stream().map(json -> json.getLong("sender")).distinct().count();
            tips.add(new TextWithStyle(senderCount + " 人", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
        }
        if (config.isShowBoxProfitDetails()) {
            if (!CollectionUtils.isEmpty(tips)) {
                tips.add(new TextWithStyle(" / ", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
            }

            double totalProfit = boxes.stream().mapToDouble(json -> json.getDoubleValue("profit")).sum();
            if (totalProfit > 0) {
                tips.add(new TextWithStyle("赚了 ", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
                tips.add(new TextWithStyle(String.valueOf(Math.abs(totalProfit)), CommonPainter.TEXT_FONT_SIZE, Color.RED));
                tips.add(new TextWithStyle(" 元", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
            } else if (totalProfit < 0) {
                tips.add(new TextWithStyle("亏了 ", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
                tips.add(new TextWithStyle(String.valueOf(Math.abs(totalProfit)), CommonPainter.TEXT_FONT_SIZE, Color.GREEN));
                tips.add(new TextWithStyle(" 元", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
            } else {
                tips.add(new TextWithStyle("不赚不亏", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
            }
        }
        if (CollectionUtils.isEmpty(tips)) {
            drawTitle("盲盒分析");
        } else {
            drawTitle("盲盒分析", tips);
        }

        // 按发送者分组统计盲盒数量与盈亏
        Map<String, Long> countBySender = boxes.stream().collect(Collectors.groupingBy(json -> json.getString("sender"), Collectors.summingLong(this::getBoxCount)));
        Map<String, Double> profitBySender = boxes.stream().collect(Collectors.groupingBy(json -> json.getString("sender"), Collectors.summingDouble(json -> json.getDoubleValue("profit"))));
        List<Map.Entry<String, Double>> sortedCount = countBySender.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> Map.entry(entry.getKey(), (double) entry.getValue()))
                .toList();
        List<Map.Entry<String, Double>> sortedProfit = profitBySender.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        // 按需批量获取发送者用户信息
        Map<Long, UserInfo> userInfos = config.getBoxRankingLimit() > 0 || config.getBoxProfitRankingLimit() > 0
                ? bilibili.getUserInfoByUids(countBySender.keySet().stream().map(Long::valueOf).collect(Collectors.toSet()))
                : Map.of();

        // 盲盒数量排行榜
        if (config.getBoxRankingLimit() > 0) {
            drawSenderRankingChart("盲盒数量排行榜", sortedCount, config.getBoxRankingLimit(), userInfos);
        }

        // 盲盒盈亏排行榜
        if (config.getBoxProfitRankingLimit() > 0) {
            drawSenderRankingChart("盲盒盈亏排行榜", sortedProfit, config.getBoxProfitRankingLimit(), userInfos);
        }

        if (config.isShowBoxGrowthChart() || config.isShowBoxInteractionChart()) {
            List<ChartPainter.LinePoint> countSamples = boxes.stream()
                    .map(json -> new ChartPainter.LinePoint(json.getLongValue("timestamp"), getBoxCount(json)))
                    .toList();

            // 盲盒数量累计曲线图
            if (config.isShowBoxGrowthChart()) {
                drawSection("盲盒数量累计曲线图");
                drawLineChart(countSamples, true);
            }

            // 盲盒数量互动曲线图
            if (config.isShowBoxInteractionChart()) {
                drawSection("盲盒数量互动曲线图");
                drawLineChart(countSamples, false);
            }
        }

        if (config.isShowBoxProfitGrowthChart() || config.isShowBoxProfitInteractionChart()) {
            List<ChartPainter.LinePoint> profitSamples = boxes.stream()
                    .map(json -> new ChartPainter.LinePoint(json.getLongValue("timestamp"), json.getDoubleValue("profit")))
                    .toList();

            // 盲盒盈亏累计曲线图
            if (config.isShowBoxProfitGrowthChart()) {
                drawSection("盲盒盈亏累计曲线图");
                drawLineChart(profitSamples, true);
            }

            // 盲盒盈亏互动曲线图
            if (config.isShowBoxProfitInteractionChart()) {
                drawSection("盲盒盈亏互动曲线图");
                drawLineChart(profitSamples, false);
            }
        }

        // 盲盒盈亏分布图
        if (config.isShowBoxProfitDistributionChart()) {
            long profitCount = 0;
            long evenCount = 0;
            long lossCount = 0;
            for (JSONObject box : boxes) {
                double profit = box.getDoubleValue("profit");
                long count = getBoxCount(box);
                if (profit > 0) {
                    profitCount += count;
                } else if (profit < 0) {
                    lossCount += count;
                } else {
                    evenCount += count;
                }
            }

            List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
            if (profitCount > 0) {
                slices.add(new ChartPainter.DistributionSlice("赚了", profitCount));
            }
            if (evenCount > 0) {
                slices.add(new ChartPainter.DistributionSlice("不赚不亏", evenCount));
            }
            if (lossCount > 0) {
                slices.add(new ChartPainter.DistributionSlice("亏了", lossCount));
            }

            drawSection("盲盒盈亏分布图");
            ChartPainter.renderDistributionChart(slices, CHART_WIDTH, font).ifPresent(this.painter::drawImage);
        }

        // 盲盒爆出礼物分布图
        if (config.isShowBoxGiftDistributionChart()) {
            Map<String, Long> countByGiftName = boxes.stream().collect(Collectors.groupingBy(json -> json.getJSONObject("giftInfo").getString("name"), Collectors.summingLong(this::getBoxCount)));
            List<Map.Entry<String, Long>> sortedGifts = countByGiftName.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .toList();

            List<ChartPainter.DistributionSlice> slices = sortedGifts.stream()
                    .map(entry -> new ChartPainter.DistributionSlice(entry.getKey(), entry.getValue()))
                    .toList();

            drawSection("盲盒爆出礼物分布图");
            ChartPainter.renderDistributionChart(slices, CHART_WIDTH, font).ifPresent(this.painter::drawImage);
        }

        this.painter.setPos(MARGIN, this.painter.getY() + 25);
    }

    /**
     * 获取一条礼物记录包含的礼物数量
     *
     * @param gift 礼物记录
     * @return 礼物数量
     */
    private long getGiftCount(JSONObject gift) {
        JSONObject giftInfo = gift.getJSONObject("giftInfo");
        Integer count = giftInfo != null ? giftInfo.getInteger("count") : null;
        return count != null ? count : 1;
    }

    /**
     * 绘制礼物分析模块
     */
    private void drawGiftAnalysis() {
        if (!config.isEnableGiftAnalysis()) {
            return;
        }

        String platform = LivePlatform.BILIBILI.getName();
        Long uid = up.getUid();

        List<JSONObject> freeGifts = liveDataService.getFreeGift(platform, uid, JSONObject.class);
        List<JSONObject> paidGifts = liveDataService.getPaidGift(platform, uid, JSONObject.class);

        if (CollectionUtils.isEmpty(freeGifts) && CollectionUtils.isEmpty(paidGifts)) {
            return;
        }

        if (config.isShowGiftDetails()) {
            double totalValue = paidGifts.stream().mapToDouble(json -> json.getDoubleValue("value")).sum();
            long senderCount = Stream.concat(paidGifts.stream(), freeGifts.stream())
                    .map(json -> json.getLong("sender"))
                    .distinct()
                    .count();
            String tip = String.format("%.1f 元 / %d 人", totalValue, senderCount);
            drawTitle("礼物分析", tip);
        } else {
            drawTitle("礼物分析");
        }

        if (CollectionUtils.isEmpty(paidGifts)) {
            this.painter.setPos(MARGIN, this.painter.getY() + 25);
            return;
        }

        // 按发送者分组统计礼物价值
        Map<String, Double> valueBySender = paidGifts.stream().collect(Collectors.groupingBy(json -> json.getString("sender"), Collectors.summingDouble(json -> json.getDoubleValue("value"))));
        List<Map.Entry<String, Double>> sorted = valueBySender.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        // 按需批量获取发送者用户信息
        Map<Long, UserInfo> userInfos = config.getGiftRankingLimit() > 0
                ? bilibili.getUserInfoByUids(valueBySender.keySet().stream().map(Long::valueOf).collect(Collectors.toSet()))
                : Map.of();

        // 礼物排行榜
        if (config.getGiftRankingLimit() > 0) {
            drawSenderRankingChart("礼物排行榜", sorted, config.getGiftRankingLimit(), userInfos);
        }

        if (config.isShowGiftGrowthChart() || config.isShowGiftInteractionChart()) {
            List<ChartPainter.LinePoint> samples = paidGifts.stream()
                    .map(json -> new ChartPainter.LinePoint(json.getLongValue("timestamp"), json.getDoubleValue("value")))
                    .toList();

            // 礼物累计曲线图
            if (config.isShowGiftGrowthChart()) {
                drawSection("礼物累计曲线图");
                drawLineChart(samples, true);
            }

            // 礼物互动曲线图
            if (config.isShowGiftInteractionChart()) {
                drawSection("礼物互动曲线图");
                drawLineChart(samples, false);
            }
        }

        // 礼物类型分布图
        if (config.isShowGiftTypeDistributionChart()) {
            Map<String, Long> countByGiftName = paidGifts.stream().collect(Collectors.groupingBy(
                    json -> json.getJSONObject("giftInfo").getString("name"), Collectors.summingLong(this::getGiftCount)));
            List<Map.Entry<String, Long>> sortedGifts = countByGiftName.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .toList();

            List<ChartPainter.DistributionSlice> slices = sortedGifts.stream()
                    .map(entry -> new ChartPainter.DistributionSlice(entry.getKey(), entry.getValue()))
                    .toList();

            drawSection("礼物类型分布图");
            ChartPainter.renderDistributionChart(slices, CHART_WIDTH, font).ifPresent(this.painter::drawImage);
        }

        this.painter.setPos(MARGIN, this.painter.getY() + 25);
    }

    /**
     * 绘制 SC（醒目留言）分析模块
     */
    private void drawSuperChatAnalysis() {
        if (!config.isEnableSuperChatAnalysis()) {
            return;
        }

        String platform = LivePlatform.BILIBILI.getName();
        Long uid = up.getUid();

        List<JSONObject> superChats = liveDataService.getSuperChat(platform, uid, JSONObject.class);
        if (CollectionUtils.isEmpty(superChats)) {
            return;
        }

        if (config.isShowSuperChatDetails()) {
            long totalValue = superChats.stream().mapToLong(json -> json.getLongValue("value")).sum();
            long senderCount = superChats.stream().map(json -> json.getLong("sender")).distinct().count();
            String tip = totalValue + " 元 / " + senderCount + " 人";
            drawTitle("SC（醒目留言）分析", tip);
        } else {
            drawTitle("SC（醒目留言）分析");
        }

        // 按发送者分组统计 SC（醒目留言）金额
        Map<String, Double> valueBySender = superChats.stream().collect(Collectors.groupingBy(json -> json.getString("sender"), Collectors.summingDouble(json -> json.getDoubleValue("value"))));
        List<Map.Entry<String, Double>> sorted = valueBySender.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        // 按需批量获取发送者用户信息
        Map<Long, UserInfo> userInfos = config.getSuperChatRankingLimit() > 0
                ? bilibili.getUserInfoByUids(valueBySender.keySet().stream().map(Long::valueOf).collect(Collectors.toSet()))
                : Map.of();

        // SC（醒目留言）排行榜
        if (config.getSuperChatRankingLimit() > 0) {
            drawSenderRankingChart("SC（醒目留言）排行榜", sorted, config.getSuperChatRankingLimit(), userInfos);
        }

        if (config.isShowSuperChatGrowthChart() || config.isShowSuperChatInteractionChart()) {
            List<ChartPainter.LinePoint> samples = superChats.stream()
                    .map(json -> new ChartPainter.LinePoint(json.getLongValue("timestamp"), json.getDoubleValue("value")))
                    .toList();

            // SC（醒目留言）累计曲线图
            if (config.isShowSuperChatGrowthChart()) {
                drawSection("SC（醒目留言）累计曲线图");
                drawLineChart(samples, true);
            }

            // SC（醒目留言）互动曲线图
            if (config.isShowSuperChatInteractionChart()) {
                drawSection("SC（醒目留言）互动曲线图");
                drawLineChart(samples, false);
            }
        }

        this.painter.setPos(MARGIN, this.painter.getY() + 25);
    }

    /**
     * 绘制大航海分析模块
     */
    private void drawGuardAnalysis() {
        if (!config.isEnableGuardAnalysis()) {
            return;
        }

        String platform = LivePlatform.BILIBILI.getName();
        Long uid = up.getUid();

        List<JSONObject> guards = liveDataService.getMemberShip(platform, uid, JSONObject.class);
        if (CollectionUtils.isEmpty(guards)) {
            return;
        }

        // 按大航海类型分组
        Map<GuardType, List<JSONObject>> grouped = guards.stream()
                .filter(json -> StringUtil.isNotBlank(json.getString("type")))
                .collect(Collectors.groupingBy(json -> GuardType.valueOf(json.getString("type"))));

        // 各类型开通月数合计
        long captainMonths = grouped.getOrDefault(GuardType.Captain, List.of()).stream().mapToLong(json -> json.getLongValue("count")).sum();
        long commanderMonths = grouped.getOrDefault(GuardType.Commander, List.of()).stream().mapToLong(json -> json.getLongValue("count")).sum();
        long governorMonths = grouped.getOrDefault(GuardType.Governor, List.of()).stream().mapToLong(json -> json.getLongValue("count")).sum();

        if (config.isShowGuardDetails()) {
            List<TextWithStyle> tips = new ArrayList<>();
            if (captainMonths > 0) {
                tips.add(new TextWithStyle("舰长 × " + captainMonths, CommonPainter.TEXT_FONT_SIZE, COLOR_GUARD_CAPTAIN));
            }
            if (commanderMonths > 0) {
                if (!CollectionUtils.isEmpty(tips)) {
                    tips.add(new TextWithStyle(" / ", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
                }
                tips.add(new TextWithStyle("提督 × " + commanderMonths, CommonPainter.TEXT_FONT_SIZE, COLOR_GUARD_COMMANDER));
            }
            if (governorMonths > 0) {
                if (!CollectionUtils.isEmpty(tips)) {
                    tips.add(new TextWithStyle(" / ", CommonPainter.TEXT_FONT_SIZE, COLOR_DEEP_PURPLE));
                }
                tips.add(new TextWithStyle("总督 × " + governorMonths, CommonPainter.TEXT_FONT_SIZE, COLOR_GUARD_GOVERNOR));
            }
            drawTitle("大航海分析", tips);
        } else {
            drawTitle("大航海分析");
        }

        // 大航海观众列表
        if (config.isShowGuardList()) {
            drawSection("本场开通大航海观众");

            int faceSize = 100;
            int iconSize = 150;
            int textSize = 20;
            int lineCount = 3;

            // 各类型图标
            Map<GuardType, BufferedImage> icons = new HashMap<>();
            for (GuardType type : List.of(GuardType.Governor, GuardType.Commander, GuardType.Captain)) {
                String resource = switch (type) {
                    case Governor -> "classpath:images/live/governor.png";
                    case Commander -> "classpath:images/live/commander.png";
                    case Captain -> "classpath:images/live/captain.png";
                    default -> null;
                };
                if (StringUtil.isNotBlank(resource)) {
                    try {
                        icons.put(type, ImageIO.read(resourceLoader.getResource(resource).getInputStream()));
                    } catch (IOException e) {
                        log.error("加载 {} 大航海图标失败", type.getName(), e);
                    }
                }
            }

            for (GuardType type : List.of(GuardType.Governor, GuardType.Commander, GuardType.Captain)) {
                List<JSONObject> list = grouped.get(type);
                if (CollectionUtils.isEmpty(list)) {
                    continue;
                }

                Color color = switch (type) {
                    case Governor -> COLOR_GUARD_GOVERNOR;
                    case Commander -> COLOR_GUARD_COMMANDER;
                    case Captain -> COLOR_GUARD_CAPTAIN;
                    default -> Color.BLACK;
                };

                Map<Long, UserInfo> userInfos = bilibili.getUserInfoByUids(list.stream().map(json -> json.getLong("sender")).collect(Collectors.toSet()));
                List<Optional<BufferedImage>> faces = bilibili.asyncGetBilibiliImages(list.stream().map(json -> userInfos.get(json.getLong("sender")).getFace()).toList()).join();
                Map<Long, Optional<BufferedImage>> facesByUid = new HashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    facesByUid.put(list.get(i).getLong("sender"), faces.get(i));
                }
                BufferedImage icon = icons.get(type);

                for (List<JSONObject> line : CollectionUtil.splitCollection(list, lineCount)) {
                    int margin = (CHART_WIDTH - iconSize * line.size()) / (line.size() + 1);
                    int y = this.painter.getY();
                    for (int j = 0; j < line.size(); j++) {
                        JSONObject guard = line.get(j);
                        int x = MARGIN + margin + j * (iconSize + margin);
                        Optional<BufferedImage> face = facesByUid.get(guard.getLong("sender"));
                        if (face != null && face.isPresent()) {
                            int padding = (iconSize - faceSize) / 2;
                            this.painter.drawImage(ImageUtil.maskToCircle(ImageUtil.resize(face.get(), faceSize, faceSize)), new Point(x + padding, y + padding));
                        }
                        if (icon != null) {
                            this.painter.drawImage(ImageUtil.resize(icon, iconSize, iconSize), new Point(x, y));
                        }
                        UserInfo userInfo = userInfos.get(guard.getLong("sender"));
                        if (userInfo != null) {
                            String uname = StringUtil.getOmitString(userInfo.getUname(), 8);
                            String countText = guard.getIntValue("count") + " 月";
                            Pair<Integer, Integer> unameSize = this.painter.getStringWidthAndHeight(uname, textSize);
                            Pair<Integer, Integer> countSize = this.painter.getStringWidthAndHeight(countText, textSize);
                            int textY = y + iconSize + 10;
                            this.painter.drawTextWithStyle(List.of(new TextWithStyle(uname, textSize, color)), new Point(x + (iconSize - unameSize.getFirst()) / 2, textY));
                            this.painter.drawTextWithStyle(List.of(new TextWithStyle(countText, textSize, Color.BLACK)), new Point(x + (iconSize - countSize.getFirst()) / 2, textY + textSize + 6));
                        }
                    }
                    this.painter.setPos(MARGIN, y + iconSize + textSize * 2 + 20 + this.painter.getRowSpace());
                }
            }
        }

        this.painter.setPos(MARGIN, this.painter.getY() + 25);
    }

    /**
     * 绘制底部版权信息
     */
    private void drawBottom() {
        this.painter.movePos(0, this.painter.getRowSpace());

        Package currentPackage = getClass().getPackage();
        TextWithStyle text = new TextWithStyle("Designed by starbot-bilibili-plugin v" + currentPackage.getImplementationVersion(), CommonPainter.TEXT_FONT_SIZE, COLOR_PINK);

        this.painter.drawCopyright(List.of(List.of(text)), List.of(), MARGIN);
    }

    /**
     * 绘制背景
     */
    private void drawBackground() {
        this.painter.createSolidRoundedRectangleBackground(Color.WHITE, 35);
    }
}
