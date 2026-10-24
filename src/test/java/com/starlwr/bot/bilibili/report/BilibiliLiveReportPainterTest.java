package com.starlwr.bot.bilibili.report;

import com.alibaba.fastjson2.JSONObject;
import com.huaban.analysis.jieba.SegToken;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.report.BilibiliLiveReportConfig;
import com.starlwr.bot.bilibili.api.model.Room;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.report.BilibiliWordCloudService;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.event.model.UserInfo;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.live.LiveDataService;
import com.starlwr.bot.core.painter.FontService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bilibili 直播报告绘图器测试
 * <p>
 * 数据来源（BilibiliApiService / LiveDataService）全部使用 Mockito mock，避免真实网络请求与数据库依赖；
 * 绘图链路使用真实 {@link FontUtil}（配置系统字体）与真实 {@link CommonPainter}，仅 mock BuildProperties，
 * 保证生成的直播报告图片与生产环境绘制行为一致。
 * <p>
 * 覆盖：基础信息、数据变动（diff 三态）、弹幕分析（排行榜/曲线/分布/词云）、盲盒分析、礼物分析、
 * SC（醒目留言）分析、大航海分析、进房分析、点赞分析、分享分析全部模块；报告图片输出至 TestLiveReport 目录，
 * 并验证词云分词调试文件（WordCloudDebug 目录）的生成与清理
 */
public class BilibiliLiveReportPainterTest {
    /**
     * 直播平台标识
     */
    private static final String PLATFORM = "bilibili";

    /**
     * 测试主播 UID
     */
    private static final String UID = "180864557";

    /**
     * 测试直播间房间号
     */
    private static final String ROOM_ID = "7260744";

    /**
     * 是否将生成的直播报告图片保存至本地 TestLiveReport 目录
     * <p>
     * 设为 false（默认）时仅执行内存绘制并断言返回的 Base64 字符串有效，不产生任何图片文件；
     * 设为 true 时额外将报告图片保存为 TestLiveReport/live_report_test.png，
     * 并断言文件存在、非空、可读取且尺寸有效，用于人工审阅报告样式。
     */
    private static final boolean SAVE_IMAGE = false;

    private static StarBotCommonPainterFactory factory;

    private static StarBotCoreProperties coreProperties;

    private static FontService fontService;

    /**
     * 初始化测试绘图环境
     * <p>
     * 构建真实 {@link FontUtil}（指定系统字体列表，避免绘制文字时无可用字体异常）与真实 {@link CommonPainter}
     * （1000 x 5000 画布、透明背景），并将 StarBotCommonPainterFactory mock 为始终返回该 CommonPainter，
     * 使报告绘制链路与生产环境一致，仅数据来源依赖被替代
     */
    @BeforeAll
    public static void setUp() {
        // 真实绘图器：真实 FontService + CommonPainter，仅 mock BuildProperties
        coreProperties = new StarBotCoreProperties();
        coreProperties.getPaint().setFonts(List.of("微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial"));
        fontService = new FontService(new DefaultResourceLoader(), coreProperties);
        ReflectionTestUtils.invokeMethod(fontService, "init");

        CommonPainter commonPainter = new CommonPainter(mock(BuildProperties.class), coreProperties, fontService, 1000, 5000, true);

        factory = mock(StarBotCommonPainterFactory.class);
        when(factory.create(anyInt(), anyInt(), anyBoolean())).thenReturn(commonPainter);
    }

    /**
     * 测试生成直播报告图片，覆盖全部模块与关键数据形态
     * <p>
     * 数据变动：粉丝数增加（红 +N）、勋章数减少（绿 -N）、大航海数不变（灰 +0）覆盖 diff 三态；
     * 弹幕分析：排行榜、累计/互动曲线、类型分布、发送者分布，并开启弹幕词云；
     * 盲盒分析：盈亏含正、零、负（覆盖排行榜双向排名、盈亏曲线正负填充与盈亏分布三组）；
     * 礼物分析：含付费与免费；
     * SC（醒目留言）：金额为整数档位；
     * 大航海：含三种等级与续费记录
     * <p>
     * 验证：paint 返回 Base64 字符串；{@link #SAVE_IMAGE} 为 true 时额外保存图片并断言文件存在、非空、
     * 可读取且尺寸有效；同时开启词云分词调试（wordCloudDebug），断言 WordCloudDebug 目录下生成
     * word-cloud-debug-测试主播-时间戳.log，内容包含"切词"与"统计"行，并在测试结束后清理调试目录
     */
    @Test
    public void testPaintLiveReport() throws Exception {
        // ================ Mock 数据 ================

        // BilibiliApiService：头像、房间信息、批量用户信息、批量头像
        BilibiliApiService bilibili = mock(BilibiliApiService.class);
        BufferedImage face = ImageIO.read(BilibiliLiveReportPainterTest.class.getResourceAsStream("/images/common/face.png"));
        assertNotNull(face, "测试头像图片加载失败");
        when(bilibili.getBilibiliImage(anyString())).thenReturn(Optional.of(face));
        when(bilibili.asyncGetBilibiliImages(anyList())).thenAnswer(invocation -> {
            List<String> urls = invocation.getArgument(0);
            return CompletableFuture.completedFuture(urls.stream().map(url -> Optional.of(face)).toList());
        });
        Map<String, UserInfo> userInfos = new HashMap<>();
        userInfos.put("10001", new UserInfo("10001", "弹幕狂魔", "https://example.com/face1.jpg"));
        userInfos.put("10002", new UserInfo("10002", "话痨小王", "https://example.com/face2.jpg"));
        userInfos.put("10003", new UserInfo("10003", "互动达人", "https://example.com/face3.jpg"));
        userInfos.put("10004", new UserInfo("10004", "潜水员甲", "https://example.com/face4.jpg"));
        userInfos.put("10005", new UserInfo("10005", "潜水员乙", "https://example.com/face5.jpg"));
        userInfos.put("10006", new UserInfo("10006", "潜水员丙", "https://example.com/face6.jpg"));
        userInfos.put("10007", new UserInfo("10007", "潜水员丁", "https://example.com/face7.jpg"));
        userInfos.put("10008", new UserInfo("10008", "潜水员戊", "https://example.com/face8.jpg"));
        userInfos.put("20001", new UserInfo("20001", "表情选手", "https://example.com/face9.jpg"));
        userInfos.put("20002", new UserInfo("20002", "表情玩家", "https://example.com/face10.jpg"));
        userInfos.put("30001", new UserInfo("30001", "盲盒欧皇", "https://example.com/face11.jpg"));
        userInfos.put("30002", new UserInfo("30002", "盲盒非酋", "https://example.com/face12.jpg"));
        userInfos.put("30003", new UserInfo("30003", "尝鲜观众", "https://example.com/face13.jpg"));
        userInfos.put("30004", new UserInfo("30004", "盒子老手", "https://example.com/face14.jpg"));
        userInfos.put("40001", new UserInfo("40001", "礼物战神", "https://example.com/face15.jpg"));
        userInfos.put("40002", new UserInfo("40002", "高能粉丝", "https://example.com/face16.jpg"));
        userInfos.put("40003", new UserInfo("40003", "新来的", "https://example.com/face17.jpg"));
        userInfos.put("40004", new UserInfo("40004", "白嫖怪", "https://example.com/face18.jpg"));
        userInfos.put("50001", new UserInfo("50001", "SC 土豪", "https://example.com/face19.jpg"));
        userInfos.put("50002", new UserInfo("50002", "一笔千金", "https://example.com/face20.jpg"));
        userInfos.put("50003", new UserInfo("50003", "轻量助力", "https://example.com/face21.jpg"));
        userInfos.put("60001", new UserInfo("60001", "总督老爷", "https://example.com/face22.jpg"));
        userInfos.put("60002", new UserInfo("60002", "提督大人", "https://example.com/face23.jpg"));
        userInfos.put("60003", new UserInfo("60003", "舰长哥哥", "https://example.com/face24.jpg"));
        userInfos.put("60004", new UserInfo("60004", "续费舰长", "https://example.com/face25.jpg"));
        userInfos.put("60005", new UserInfo("60005", "舰长小白", "https://example.com/face26.jpg"));
        userInfos.put("70001", new UserInfo("70001", "进房常客", "https://example.com/face27.jpg"));
        userInfos.put("70002", new UserInfo("70002", "路过观众", "https://example.com/face28.jpg"));
        userInfos.put("70003", new UserInfo("70003", "串门者", "https://example.com/face29.jpg"));
        userInfos.put("80001", new UserInfo("80001", "点赞狂魔", "https://example.com/face30.jpg"));
        userInfos.put("80002", new UserInfo("80002", "点赞粉丝", "https://example.com/face31.jpg"));
        userInfos.put("80003", new UserInfo("80003", "路过点赞", "https://example.com/face32.jpg"));
        userInfos.put("90001", new UserInfo("90001", "分享达人", "https://example.com/face33.jpg"));
        userInfos.put("90002", new UserInfo("90002", "热心观众", "https://example.com/face34.jpg"));
        when(bilibili.getUserInfoByUids(anySet())).thenReturn(userInfos);
        Room room = new Room(UID, "测试主播", ROOM_ID, "https://example.com/face.jpg", 1,
                1784617225000L, "今晚测试直播标题", "https://example.com/cover.jpg", "虚拟主播", "综合");
        when(bilibili.getLiveInfoByRoomId(ROOM_ID)).thenReturn(room);

        // LiveDataService：直播时间、数据变动（diff 三态）、弹幕/表情数据
        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(1784617225000L));
        when(liveDataService.getLiveEndTime(PLATFORM, UID)).thenReturn(Optional.of(1784620000000L));
        // getCustomObject(Integer.class, platform, 键名, uid)：varargs 展开后索引 0=type, 1=platform, 2=键名, 3=uid
        when(liveDataService.getCustomObject(eq(Integer.class), any(String[].class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(2, String.class);
            return switch (key) {
                case "BeforeFansCount" -> Optional.of(100);
                case "AfterFansCount" -> Optional.of(150);        // 粉丝 100 → 150（+50，红色）
                case "BeforeFansMedalCount" -> Optional.of(50);
                case "AfterFansMedalCount" -> Optional.of(30);    // 勋章 50 → 30（-20，绿色）
                case "BeforeGuardCount" -> Optional.of(20);
                case "AfterGuardCount" -> Optional.of(20);        // 大航海 20 → 20（+0，灰色）
                default -> Optional.empty();
            };
        });
        when(liveDataService.getDanmu(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createDanmus());
        when(liveDataService.getEmoji(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createEmojis());
        when(liveDataService.getRandomGift(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createBoxes());
        when(liveDataService.getPaidGift(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createPaidGifts());
        when(liveDataService.getFreeGift(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createFreeGifts());
        when(liveDataService.getSuperChat(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createSuperChats());
        when(liveDataService.getMemberShip(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createGuards());
        when(liveDataService.getEnterRoom(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createEnterRooms());
        when(liveDataService.getLike(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createLikes());
        when(liveDataService.getShare(eq(PLATFORM), eq(UID), eq(JSONObject.class))).thenReturn(createShares());

        // ================ 生成图片 ================

        Up up = new Up(UID, "测试主播", ROOM_ID, "https://example.com/face.jpg");
        BilibiliLiveReportConfig config = createTestConfig();

        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getLive().setWordCloudLimit(100);
        properties.getDebug().setWordCloudDebug(true);

        BilibiliWordCloudService wordCloudUtil = new BilibiliWordCloudService();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(wordCloudUtil, "init");
        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                new com.starlwr.bot.core.config.StarBotCoreProperties(),
                properties, fontService, bilibili, factory, liveDataService, wordCloudUtil, up, config);

        Optional<String> result;
        if (SAVE_IMAGE) {
            String outputDir = "TestLiveReport";
            Files.createDirectories(Paths.get(outputDir));
            String outputPath = outputDir + "/live_report_test.png";

            result = painter.paint(outputPath);
            assertTrue(result.isPresent(), "paint 应返回 Base64 字符串");

            File file = new File(outputPath);
            assertTrue(file.exists(), "生成图片文件应存在");
            assertTrue(file.length() > 0, "生成图片文件不应为空");

            BufferedImage image = ImageIO.read(file);
            assertNotNull(image, "图片应可读取");
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0, "图片尺寸应有效");

            System.out.println("生成图片: " + file.getAbsolutePath() + " 尺寸: " + image.getWidth() + "x" + image.getHeight());
        } else {
            // 默认仅内存绘制，不保存图片文件
            result = painter.paint();
            assertTrue(result.isPresent(), "paint 应返回 Base64 字符串");
        }

        // 词云分词调试文件断言与清理
        Path debugDirectory = Paths.get("WordCloudDebug");
        try (var stream = Files.list(debugDirectory)) {
            Path debugFile = stream.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("word-cloud-debug-测试主播-") && name.endsWith(".log");
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("词云分词调试文件不存在"));
            String debugContent = Files.readString(debugFile);
            assertTrue(debugContent.contains("切词"), "调试文件应包含切词结果");
            assertTrue(debugContent.contains("统计"), "调试文件应包含统计行");
            // 每条非空白弹幕都应输出一行原文，验证弹幕数量充足且 contentText 提取生效
            long originalLines = debugContent.lines().filter(line -> line.startsWith("原文: ")).count();
            assertTrue(originalLines >= 25, "调试文件应包含全部弹幕原文, 实际: " + originalLines);
            // 词云数据中高频词应多次出现，验证重复弹幕被正确计数
            assertTrue(debugContent.contains("晚安"), "调试文件应包含高频词「晚安」的切词结果");
            assertTrue(debugContent.contains("666"), "调试文件应包含数字弹幕「666」的切词结果");
            Files.deleteIfExists(debugFile);
        }
        Files.deleteIfExists(debugDirectory);
    }

    /**
     * 测试分词过滤不完整表情字符
     * <p>
     * Emoji 等补充平面字符会被分词工具拆成孤立代理项，编码为 UTF-8 时变成问号，
     * 只应剔除这些不完整的字符；完整的符号（如 ✓）与正常汉字、数字、字母应保留
     */
    @Test
    public void testSegmentFiltersEmoji() {
        BilibiliWordCloudService wordCloudUtil = new BilibiliWordCloudService();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(wordCloudUtil, "init");

        List<SegToken> tokens = wordCloudUtil.segment("测试😮测试✓🚀 666");

        boolean hasIncompleteUtf8 = tokens.stream().anyMatch(token -> {
            String word = token.word;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= word.length() || !Character.isLowSurrogate(word.charAt(i + 1))) {
                        return true;
                    }
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    return true;
                }
            }
            return false;
        });
        assertFalse(hasIncompleteUtf8, "分词结果不应包含不完整字符（孤立代理项）");
        assertTrue(tokens.stream().anyMatch(token -> "测试".equals(token.word)), "正常汉字应保留");
        assertTrue(tokens.stream().anyMatch(token -> "666".equals(token.word)), "数字词条应保留");
        assertTrue(tokens.stream().anyMatch(token -> "✓".equals(token.word)), "完整符号（✓）应保留");
    }

    /**
     * 创建测试直播报告配置，启用全部已实现模块与统计图
     *
     * @return 测试直播报告配置
     */
    private BilibiliLiveReportConfig createTestConfig() {
        BilibiliLiveReportConfig config = new BilibiliLiveReportConfig();

        // 模块顺序
        config.setSequence(new ArrayList<>(List.of("changeInfo", "danmuAnalysis", "boxAnalysis", "giftAnalysis", "superChatAnalysis", "guardAnalysis", "enterRoomAnalysis", "likeAnalysis", "shareAnalysis")));

        // 基础信息模块
        config.setEnableBasicInfo(true);
        config.setShowLiveArea(true);
        config.setShowLiveTitle(true);
        config.setShowLiveTime(true);

        // 数据变动模块
        config.setEnableChangeInfo(true);
        config.setShowFansChange(true);
        config.setShowFansMedalChange(true);
        config.setShowGuardChange(true);

        // 弹幕分析模块
        config.setEnableDanmuAnalysis(true);
        config.setShowDanmuDetails(true);
        config.setDanmuRankingLimit(5);
        config.setShowDanmuGrowthChart(true);
        config.setShowDanmuInteractionChart(true);
        config.setShowDanmuTypeDistributionChart(true);
        config.setShowDanmuSenderDistributionChart(true);
        config.setShowDanmuWordCloud(true);

        // 盲盒分析模块
        config.setEnableBoxAnalysis(true);
        config.setShowBoxDetails(true);
        config.setShowBoxProfitDetails(true);
        config.setBoxRankingLimit(5);
        config.setBoxProfitRankingLimit(5);
        config.setShowBoxGrowthChart(true);
        config.setShowBoxInteractionChart(true);
        config.setShowBoxProfitGrowthChart(true);
        config.setShowBoxProfitInteractionChart(true);
        config.setShowBoxProfitDistributionChart(true);
        config.setShowBoxGiftDistributionChart(true);

        // 礼物分析模块
        config.setEnableGiftAnalysis(true);
        config.setShowGiftDetails(true);
        config.setGiftRankingLimit(5);
        config.setShowGiftGrowthChart(true);
        config.setShowGiftInteractionChart(true);
        config.setShowGiftTypeDistributionChart(true);

        // SC（醒目留言）分析模块
        config.setEnableSuperChatAnalysis(true);
        config.setShowSuperChatDetails(true);
        config.setSuperChatRankingLimit(5);
        config.setShowSuperChatGrowthChart(true);
        config.setShowSuperChatInteractionChart(true);

        // 大航海分析模块
        config.setEnableGuardAnalysis(true);
        config.setShowGuardDetails(true);
        config.setShowGuardList(true);

        // 进房分析模块
        config.setEnableEnterRoomAnalysis(true);
        config.setShowEnterRoomDetails(true);
        config.setShowEnterRoomGrowthChart(true);
        config.setShowEnterRoomInteractionChart(true);

        // 点赞分析模块
        config.setEnableLikeAnalysis(true);
        config.setShowLikeDetails(true);
        config.setLikeRankingLimit(5);
        config.setShowLikeGrowthChart(true);
        config.setShowLikeInteractionChart(true);

        // 分享分析模块
        config.setEnableShareAnalysis(true);
        config.setShowShareDetails(true);
        config.setShowShareGrowthChart(true);
        config.setShowShareInteractionChart(true);

        return config;
    }

    /**
     * 构造普通弹幕数据
     * <p>
     * 共 28 条贴近真实直播的弹幕，时间跨度 1~28 分钟满足曲线图至少一分钟的校验；
     * 高频词多次重复（晚安、好听、再来一首、666、哈哈哈哈哈、破防了等）确保弹幕词云能产出足够词条与明显频次差异；
     * 弹幕狂魔 8 条、话痨小王 6 条、互动达人 5 条、潜水员甲 3 条、潜水员乙/丙各 2 条、潜水员丁/戊各 1 条，
     * 发送者占比各不相同，使发送者分布图出现"其他"汇总；
     * 词云仅统计本列表的 contentText
     *
     * @return 普通弹幕记录列表
     */
    private List<JSONObject> createDanmus() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        // 弹幕狂魔：8 条，高频词大量重复
        for (String text : List.of("晚安 晚安", "主播好帅", "再来一首 再来一首", "666666",
                "awsl 好好听", "好听好听", "破防了", "晚安 晚安")) {
            list.add(createDanmu(start + index++ * 60_000L, "10001", text));
        }
        // 话痨小王：6 条，语气词与梗词重复
        for (String text : List.of("哈哈哈哈哈", "笑死我了", "破防了 破防了", "网络好卡",
                "蹲一个 蹲一个", "哈哈哈哈哈")) {
            list.add(createDanmu(start + index++ * 60_000L, "10002", text));
        }
        // 互动达人：5 条，互动类弹幕
        for (String text : List.of("打卡 打卡", "关注了 关注了", "已三连", "冲鸭 冲鸭", "谢谢主播")) {
            list.add(createDanmu(start + index++ * 60_000L, "10003", text));
        }
        // 潜水员甲：3 条
        for (String text : List.of("晚安", "好听", "666")) {
            list.add(createDanmu(start + index++ * 60_000L, "10004", text));
        }
        // 潜水员乙：2 条
        for (String text : List.of("哈哈哈哈", "下次一定")) {
            list.add(createDanmu(start + index++ * 60_000L, "10005", text));
        }
        // 潜水员丙：2 条
        for (String text : List.of("加油 加油", "生日快乐")) {
            list.add(createDanmu(start + index++ * 60_000L, "10006", text));
        }
        // 潜水员丁：1 条
        list.add(createDanmu(start + index++ * 60_000L, "10007", "潜水"));
        // 潜水员戊：1 条
        list.add(createDanmu(start + index++ * 60_000L, "10008", "路过"));
        return list;
    }

    /**
     * 构造表情弹幕数据
     * 用于验证弹幕类型分布与人数统计，词云绘制不会读取该列表
     *
     * @return 表情弹幕记录列表
     */
    private List<JSONObject> createEmojis() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L + 8 * 60_000L;
        list.add(createEmoji(start, "20001"));
        list.add(createEmoji(start + 60_000L, "20002"));
        return list;
    }

    /**
     * 构造一条普通弹幕记录
     *
     * @param timestamp 发送时间戳，单位：毫秒
     * @param sender    发送者 UID
     * @param text      弹幕文本
     * @return 弹幕记录
     */
    private JSONObject senderObject(String uid) {
        return new JSONObject().fluentPut("uid", uid).fluentPut("uname", "sender-" + uid);
    }

    private JSONObject createDanmu(long timestamp, String sender, String text) {
        JSONObject danmu = new JSONObject();
        danmu.put("timestamp", timestamp);
        danmu.put("content", text);
        danmu.put("contentText", text);
        danmu.put("sender", senderObject(sender));
        return danmu;
    }

    /**
     * 构造一条表情弹幕记录
     *
     * @param timestamp 发送时间戳，单位：毫秒
     * @param sender    发送者 UID
     * @return 表情弹幕记录
     */
    private JSONObject createEmoji(long timestamp, String sender) {
        JSONObject emoji = new JSONObject();
        emoji.put("timestamp", timestamp);
        emoji.put("content", "[表情]弹幕" + sender);
        emoji.put("sender", senderObject(sender));
        return emoji;
    }

    /**
     * 构造盲盒数据
     * 数量口径按 randomGiftInfo.count 累加：合计 9 个、4 人；盈亏含正、零、负（+250 / -250 / 0 / -80），
     * 覆盖盲盒盈亏排行榜双向排名、盈亏曲线正负填充与盈亏分布三组；
     * 时间跨度 1~7 分钟满足曲线图至少一分钟的校验
     */
    private List<JSONObject> createBoxes() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        // 盲盒欧皇：3 个，盈亏 +100 / +200 / -50
        list.add(createBox(start + index++ * 60_000L, "30001", 1, "整蛊盲盒", 100.0, "荣耀皇冠", 200.0));
        list.add(createBox(start + index++ * 60_000L, "30001", 1, "整蛊盲盒", 100.0, "小飞机", 300.0));
        list.add(createBox(start + index++ * 60_000L, "30001", 1, "心动盲盒", 100.0, "辣条", 50.0));
        // 盲盒非酋：2 次共 4 个（一次 count=2），盈亏 -300 / +50
        list.add(createBox(start + index++ * 60_000L, "30002", 2, "心动盲盒", 500.0, "小丑气球", 200.0));
        list.add(createBox(start + index++ * 60_000L, "30002", 2, "心动盲盒", 500.0, "抱枕", 550.0));
        // 尝鲜观众：1 个，盈亏 0
        list.add(createBox(start + index++ * 60_000L, "30003", 1, "整蛊盲盒", 100.0, "表情包", 100.0));
        // 盒子老手：1 个，盈亏 -80
        list.add(createBox(start + index++ * 60_000L, "30004", 1, "整蛊盲盒", 100.0, "辣条", 20.0));
        return list;
    }

    /**
     * 构造一条盲盒记录
     */
    private JSONObject createBox(long timestamp, String sender, int count, String boxName, double price, String giftName, double value) {
        JSONObject box = new JSONObject();
        box.put("timestamp", timestamp);
        box.put("sender", senderObject(sender));

        JSONObject randomGiftInfo = new JSONObject();
        randomGiftInfo.put("id", 34914L);
        randomGiftInfo.put("name", boxName);
        randomGiftInfo.put("price", price / count);
        randomGiftInfo.put("count", count);
        randomGiftInfo.put("url", "https://example.com/box.png");
        box.put("randomGiftInfo", randomGiftInfo);

        JSONObject giftInfo = new JSONObject();
        giftInfo.put("id", 34919L);
        giftInfo.put("name", giftName);
        giftInfo.put("price", value / count);
        giftInfo.put("count", count);
        giftInfo.put("url", "https://example.com/gift.png");
        box.put("giftInfo", giftInfo);

        box.put("price", price);
        box.put("value", value);
        box.put("profit", value - price);
        return box;
    }

    /**
     * 构造付费礼物数据
     * 合计收益 1889.7 元（保留一位小数：1889.7），4 人送礼（含仅送免费礼物的白嫖怪）；
     * 礼物类型分布按件数：小花花 6、辣条 3、嘉年华 1（仅统计付费礼物）；
     * 时间跨度 1~6 分钟满足曲线图至少一分钟的校验
     */
    private List<JSONObject> createPaidGifts() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        // 礼物战神：金额 0.3 + 1888.8 + 0.2 = 1889.3，件数 3 + 1 + 2 = 6
        list.add(createGift(start + index++ * 60_000L, "40001", 3, "小花花", 0.1, 0.3));
        list.add(createGift(start + index++ * 60_000L, "40001", 1, "嘉年华", 1888.8, 1888.8));
        list.add(createGift(start + index++ * 60_000L, "40001", 2, "小花花", 0.1, 0.2));
        // 高能粉丝：金额 0.1 + 0.2 = 0.3，件数 1 + 2 = 3
        list.add(createGift(start + index++ * 60_000L, "40002", 1, "辣条", 0.1, 0.1));
        list.add(createGift(start + index++ * 60_000L, "40002", 2, "辣条", 0.1, 0.2));
        // 新来的：金额 0.1，件数 1
        list.add(createGift(start + index++ * 60_000L, "40003", 1, "小花花", 0.1, 0.1));
        return list;
    }

    /**
     * 构造免费礼物数据
     */
    private List<JSONObject> createFreeGifts() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L + 7 * 60_000L;
        // 白嫖怪：只送免费礼物，不应出现在礼物排行榜与其他统计中
        list.add(createGift(start, "40004", 1, "粉丝团灯牌", 0.0, 0.0));
        list.add(createGift(start + 60_000L, "40004", 1, "人气票", 0.0, 0.0));
        return list;
    }

    /**
     * 构造一条礼物记录
     */
    private JSONObject createGift(long timestamp, String sender, int count, String giftName, double price, double value) {
        JSONObject gift = new JSONObject();
        gift.put("timestamp", timestamp);
        gift.put("sender", senderObject(sender));

        JSONObject giftInfo = new JSONObject();
        giftInfo.put("id", 31036L);
        giftInfo.put("name", giftName);
        giftInfo.put("price", price);
        giftInfo.put("count", count);
        giftInfo.put("url", "https://example.com/gift.png");
        gift.put("giftInfo", giftInfo);

        gift.put("value", value);
        return gift;
    }

    /**
     * 构造 SC（醒目留言）数据
     * 合计 660 元 / 3 人，金额为整数档位（30 / 100 / 500 / 30），排行榜 500 > 130 > 30；
     * 时间跨度 1~4 分钟满足曲线图至少一分钟的校验
     */
    private List<JSONObject> createSuperChats() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        // SC 土豪：30 + 100 = 130 元
        list.add(createSuperChat(start + index++ * 60_000L, "50001", 30.0, "主播加油"));
        list.add(createSuperChat(start + index++ * 60_000L, "50001", 100.0, "生日快乐"));
        // 一笔千金：500 元
        list.add(createSuperChat(start + index++ * 60_000L, "50002", 500.0, "再来一曲"));
        // 轻量助力：30 元
        list.add(createSuperChat(start + index++ * 60_000L, "50003", 30.0, "冲冲冲"));
        return list;
    }

    /**
     * 构造一条 SC（醒目留言）记录
     */
    private JSONObject createSuperChat(long timestamp, String sender, double value, String content) {
        JSONObject superChat = new JSONObject();
        superChat.put("timestamp", timestamp);
        superChat.put("sender", senderObject(sender));
        superChat.put("content", content);
        superChat.put("value", value);
        return superChat;
    }

    /**
     * 构造大航海数据
     * 舰长 5 月（3+1+1）、提督 6 月、总督 12 月；
     * 含一条续费记录（开通+续费合并统计），观众列表按总督/提督/舰长分组展示
     */
    private List<JSONObject> createGuards() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        // 总督老爷：总督 12 月
        list.add(createGuard(start + index++ * 60_000L, "60001", "Governor", "ACTIVATION", 12, 19998.0));
        // 提督大人：提督 6 月
        list.add(createGuard(start + index++ * 60_000L, "60002", "Commander", "ACTIVATION", 6, 1998.0));
        // 舰长哥哥：舰长 3 月
        list.add(createGuard(start + index++ * 60_000L, "60003", "Captain", "ACTIVATION", 3, 138.0));
        // 续费舰长：续费 1 月（开通+续费合并统计）
        list.add(createGuard(start + index++ * 60_000L, "60004", "Captain", "RENEWAL", 1, 138.0));
        // 舰长小白：舰长 1 月
        list.add(createGuard(start + index++ * 60_000L, "60005", "Captain", "ACTIVATION", 1, 138.0));
        return list;
    }

    /**
     * 构造一条大航海记录
     */
    private JSONObject createGuard(long timestamp, String sender, String type, String operateType, int count, double price) {
        JSONObject guard = new JSONObject();
        guard.put("timestamp", timestamp);
        guard.put("sender", senderObject(sender));
        guard.put("type", type);
        guard.put("operateType", operateType);
        guard.put("count", count);
        guard.put("price", price);
        guard.put("unit", "月");
        guard.put("value", price * count);
        return guard;
    }

    /**
     * 构造进房数据
     * 进房常客进房 2 次、路过观众 1 次、串门者 1 次，合计 4 次 / 3 人；
     * 时间跨度 1~3 分钟满足曲线图至少一分钟的校验
     */
    private List<JSONObject> createEnterRooms() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        list.add(createEnterRoom(start + index++ * 60_000L, "70001"));
        list.add(createEnterRoom(start + index++ * 60_000L, "70002"));
        list.add(createEnterRoom(start + index++ * 60_000L, "70003"));
        list.add(createEnterRoom(start + index++ * 60_000L, "70001"));
        return list;
    }

    /**
     * 构造一条进房记录
     */
    private JSONObject createEnterRoom(long timestamp, String sender) {
        JSONObject enter = new JSONObject();
        enter.put("timestamp", timestamp);
        enter.put("sender", senderObject(sender));
        return enter;
    }

    /**
     * 构造点赞数据
     * 点赞狂魔 3 次、点赞粉丝 1 次、路过点赞 1 次，合计 5 次 / 3 人，排行榜 3 > 1 > 1；
     * 时间跨度 1~5 分钟满足曲线图至少一分钟的校验
     */
    private List<JSONObject> createLikes() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        list.add(createLike(start + index++ * 60_000L, "80001"));
        list.add(createLike(start + index++ * 60_000L, "80002"));
        list.add(createLike(start + index++ * 60_000L, "80001"));
        list.add(createLike(start + index++ * 60_000L, "80003"));
        list.add(createLike(start + index++ * 60_000L, "80001"));
        return list;
    }

    /**
     * 构造一条点赞记录
     */
    private JSONObject createLike(long timestamp, String sender) {
        JSONObject like = new JSONObject();
        like.put("timestamp", timestamp);
        like.put("sender", senderObject(sender));
        return like;
    }

    /**
     * 构造分享数据
     * 分享达人 2 次、热心观众 1 次，合计 3 次 / 2 人；
     * 时间跨度 1~3 分钟满足曲线图至少一分钟的校验
     */
    private List<JSONObject> createShares() {
        List<JSONObject> list = new ArrayList<>();
        long start = 1784617225000L;
        int index = 1;
        list.add(createShare(start + index++ * 60_000L, "90001"));
        list.add(createShare(start + index++ * 60_000L, "90002"));
        list.add(createShare(start + index++ * 60_000L, "90001"));
        return list;
    }

    /**
     * 构造一条分享记录
     */
    private JSONObject createShare(long timestamp, String sender) {
        JSONObject share = new JSONObject();
        share.put("timestamp", timestamp);
        share.put("sender", senderObject(sender));
        return share;
    }
}
