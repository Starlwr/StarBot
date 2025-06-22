package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.util.CollectionUtil;
import com.starlwr.bot.core.util.FontUtil;
import com.starlwr.bot.core.util.ImageUtil;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bilibili 动态绘图器
 */
@Slf4j
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class BilibiliDynamicPainter {
    @Resource
    private ResourceLoader resourceLoader;

    @Resource
    private BuildProperties properties;

    @Resource
    private FontUtil fontUtil;

    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private StarBotCommonPainterFactory factory;

    private CommonPainter painter;

    private final Dynamic dynamic;

    private final Map<String, String> iconUrlMap = new HashMap<>();

    private final int WIDTH = 1000;

    private final int TEXT_MARGIN = 25;

    private final int IMAGE_MARGIN = 10;

    private final int EMOJI_SIZE = 60;

    private final int ICON_SIZE = 40;

    private final Color COLOR_PINK = new Color(251, 114, 153);

    private final Color COLOR_FORWARD = new Color(244, 244, 244);

    private final Color COLOR_LIGHT_BLUE = new Color(0, 174, 236);

    public BilibiliDynamicPainter(Dynamic dynamic) {
        this.dynamic = dynamic;
    }

    @PostConstruct
    public void init() {
        this.painter = factory.create(WIDTH, 5000, true);
        iconUrlMap.put("RICH_TEXT_NODE_TYPE_WEB", "classpath:images/dynamic/link.png");
        iconUrlMap.put("RICH_TEXT_NODE_TYPE_BV", "classpath:images/dynamic/video.png");
        iconUrlMap.put("RICH_TEXT_NODE_TYPE_OGV_SEASON", "classpath:images/dynamic/video.png");
        iconUrlMap.put("RICH_TEXT_NODE_TYPE_LOTTERY", "classpath:images/dynamic/box.png");
        iconUrlMap.put("RICH_TEXT_NODE_TYPE_VOTE", "classpath:images/dynamic/tick.png");
    }

    /**
     * 根据给定动态信息生成动态图片
     * @return 动态图片的 Base64 字符串
     */
    @Cacheable(value = "bilibiliDynamicImageCache", keyGenerator = "cacheKeyGenerator")
    public Optional<String> paint() {
        return paint(null);
    }

    /**
     * 根据给定动态信息生成动态图片
     * @param path 图片保存路径
     * @return 动态图片的 Base64 字符串
     */
    @Cacheable(value = "bilibiliDynamicImageCache", keyGenerator = "cacheKeyGenerator")
    public Optional<String> paint(String path) {
        try {
            drawLogo();
            drawHeader();
            drawByType(false);
            drawBottom();
            drawBackground();
        } catch (Exception e) {
            log.error("绘制动态图片失败: {}", JSON.toJSONString(this.dynamic), e);
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
        try {
            BufferedImage logo = ImageIO.read(resourceLoader.getResource("classpath:logo.png").getInputStream());
            this.painter.drawImage(logo, new Point(200, 55)).setPos(175, 300);
        } catch (IOException e) {
            log.error("加载 StarBot Logo 失败", e);
        }
    }

    /**
     * 绘制动态头部
     */
    private void drawHeader() {
        JSONObject author = this.dynamic.getModules().getJSONObject("module_author");

        Optional<BufferedImage> optionalFace = bilibili.getBilibiliImage(author.getString("face"));
        Optional<BufferedImage> optionalPendant = bilibili.getBilibiliImage(author.getJSONObject("pendant").getString("image"));
        // -1: 无认证, 0: 个人认证, 1: 机构认证
        Integer officialType = author.getJSONObject("official_verify").getInteger("type");
        boolean isVip = "".equals(author.getJSONObject("vip").getString("nickname_color"));
        String uname = author.getString("name");
        Instant timestamp = Instant.ofEpochSecond(author.getInteger("pub_ts"));

        Point facePoint = new Point(TEXT_MARGIN * 2, this.painter.getY());
        optionalFace.ifPresent(face -> this.painter.drawImage(ImageUtil.maskToCircle(ImageUtil.resize(face, 100, 100)), facePoint));
        optionalPendant.ifPresent(pendant -> this.painter.drawImage(ImageUtil.resize(pendant, 170, 170), new Point(facePoint.x - 35, facePoint.y - 35)).movePos(15, 0));

        BufferedImage icon = null;
        try {
            if (officialType == 0) {
                icon = ImageIO.read(resourceLoader.getResource("classpath:images/dynamic/personal.png").getInputStream());
            } else if (officialType == 1) {
                icon = ImageIO.read(resourceLoader.getResource("classpath:images/dynamic/business.png").getInputStream());
            } else if (isVip) {
                icon = ImageIO.read(resourceLoader.getResource("classpath:images/dynamic/vip.png").getInputStream());
            }
        } catch (IOException e) {
            log.error("加载图标失败", e);
        }
        if (icon != null) {
            this.painter.drawImage(icon, new Point(facePoint.x + 68, facePoint.y + 68));
        }

        Color unameColor = isVip ? COLOR_PINK : Color.BLACK;
        this.painter.drawText(uname, unameColor);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm").withZone(ZoneId.systemDefault());
        this.painter.drawTip(formatter.format(timestamp));

        this.painter.setPos(TEXT_MARGIN, facePoint.y + 150);
    }

    /**
     * 根据类型绘制动态主体
     * @param isForward 是否为转发源动态
     */
    private void drawByType(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);

        switch (currentDynamic.getType()) {
            case "DYNAMIC_TYPE_ARTICLE" -> {
                drawTitle(isForward);
                drawArticleContent(isForward);
                drawImages(isForward);
            }
            case "DYNAMIC_TYPE_AV" -> {
                drawVideoContent(isForward);
                drawVideoCover(isForward);
                drawVideoTitle(isForward);
            }
            case "DYNAMIC_TYPE_COMMON_SQUARE" -> {
                drawVideoContent(isForward);
                drawCommonArea(isForward);
            }
            case "DYNAMIC_TYPE_DRAW" -> {
                if ("MAJOR_TYPE_BLOCKED".equals(currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getString("type"))) {
                    drawBlockArea();
                } else {
                    drawTitle(isForward);
                    drawContent(isForward);
                    drawImages(isForward);
                }
            }
            case "DYNAMIC_TYPE_FORWARD" -> {
                drawContent(isForward);
                drawOriginDynamicUname();
                drawByType(true);
            }
            case "DYNAMIC_TYPE_LIVE_RCMD" -> {
                // 系统自动发送，暂不处理
            }
            case "DYNAMIC_TYPE_PGC_UNION" -> {
                // 纪录片等更新，暂不处理
            }
            case "DYNAMIC_TYPE_UGC_SEASON" -> {
                // 合集更新，暂不处理
            }
            case "DYNAMIC_TYPE_WORD" -> {
                drawTitle(isForward);
                drawContent(isForward);
            }
            default -> {
                if (isForward) {
                    log.warn("未处理的源动态类型 {}: {}", currentDynamic.getType(), JSON.toJSONString(this.dynamic));
                } else {
                    log.warn("未处理的动态类型 {}: {}", currentDynamic.getType(), JSON.toJSONString(this.dynamic));
                }
            }
        }

        drawAddOnCard(isForward);
    }

    /**
     * 获取当前动态
     *
     * @param isForward 是否为转发源动态
     * @return 当前动态
     */
    private Dynamic getCurrentDynamic(boolean isForward) {
        if (isForward) {
            return this.dynamic.getOrigin();
        } else {
            return this.dynamic;
        }
    }

    /**
     * 绘制动态标题
     * @param isForward 是否为转发源动态
     */
    private void drawTitle(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("opus");

        String title = data.getString("title");
        if (title == null) {
            return;
        }

        List<BufferedImage> images = getTitleLineImages(title);

        if (isForward) {
            int height = images.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * images.size();
            this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
        }

        images.forEach(this.painter::drawImage);
    }

    /**
     * 获取动态标题每行图片
     * @param title 标题
     * @return 图片列表
     */
    private List<BufferedImage> getTitleLineImages(String title) {
        List<BufferedImage> images = new ArrayList<>();
        CommonPainter currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);

        for (int codePoint : title.codePoints().toArray()) {
            Font font = fontUtil.findFontForCharacter(codePoint).deriveFont(Font.BOLD, 40);
            if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN, font)) {
                images.add(currentPainter.getImage());
                currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);
            }
            currentPainter.drawElement(codePoint, font);
        }

        images.add(currentPainter.getImage());

        return images;
    }

    /**
     * 绘制动态内容
     * @param isForward 是否为转发源动态
     */
    private void drawContent(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic");
        if ("DYNAMIC_TYPE_FORWARD".equals(currentDynamic.getType())) {
            data = data.getJSONObject("desc");
        } else {
            data = data.getJSONObject("major").getJSONObject("opus");
        }

        if (data.containsKey("summary")) {
            data = data.getJSONObject("summary");
        }

        List<JSONObject> nodes = data.getJSONArray("rich_text_nodes").toList(JSONObject.class);

        List<BufferedImage> images = getContentLineImages(nodes);

        if (isForward) {
            int height = images.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * images.size();
            this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
        }

        images.forEach(this.painter::drawImage);
    }

    /**
     * 获取动态内容每行图片
     * @param richTextNodes 动态内容节点
     * @return 图片列表
     */
    private List<BufferedImage> getContentLineImages(List<JSONObject> richTextNodes) {
        List<BufferedImage> images = new ArrayList<>();
        CommonPainter currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);

        for (JSONObject node : richTextNodes) {
            String type = node.getString("type");
            switch (type) {
                case "RICH_TEXT_NODE_TYPE_TEXT" -> {
                    for (int codePoint : node.getString("text").codePoints().toArray()) {
                        if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN)) {
                            images.add(currentPainter.getImage());
                            currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);
                        }
                        currentPainter.drawElement(codePoint);
                    }
                }
                case "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                    Optional<BufferedImage> optionalImage = bilibili.getBilibiliImage(node.getJSONObject("emoji").getString("icon_url"));
                    if (optionalImage.isPresent()) {
                        BufferedImage image = ImageUtil.resize(optionalImage.get(), EMOJI_SIZE, EMOJI_SIZE);
                        if (currentPainter.isNeedWrap(image, TEXT_MARGIN)) {
                            images.add(currentPainter.getImage());
                            currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);
                        }
                        currentPainter.drawElement(image);
                    }
                }
                case "RICH_TEXT_NODE_TYPE_AT", "RICH_TEXT_NODE_TYPE_WEB", "RICH_TEXT_NODE_TYPE_BV", "RICH_TEXT_NODE_TYPE_OGV_SEASON", "RICH_TEXT_NODE_TYPE_TOPIC", "RICH_TEXT_NODE_TYPE_LOTTERY", "RICH_TEXT_NODE_TYPE_VOTE", "RICH_TEXT_NODE_TYPE_GOODS" -> {
                    String iconUrl = null;
                    if (iconUrlMap.containsKey(type)) {
                        iconUrl = iconUrlMap.get(type);
                    } else if ("RICH_TEXT_NODE_TYPE_GOODS".equals(type)) {
                        String iconName = node.getString("icon_name");
                        if ("shop".equals(iconName)) {
                            iconUrl = "classpath:images/dynamic/shop.png";
                        } else if ("taobao".equals(iconName)) {
                            iconUrl = "classpath:images/dynamic/taobao.png";
                        } else if ("jd".equals(iconName)) {
                            iconUrl = "classpath:images/dynamic/jd.png";
                        } else {
                            log.warn("未处理的图标类型 {}: {}", iconName, JSON.toJSONString(this.dynamic));
                        }
                    }

                    if (iconUrl != null) {
                        try {
                            BufferedImage icon = ImageUtil.resize(ImageIO.read(resourceLoader.getResource(iconUrl).getInputStream()), ICON_SIZE, ICON_SIZE);
                            if (currentPainter.isNeedWrap(icon, TEXT_MARGIN)) {
                                images.add(currentPainter.getImage());
                                currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);
                            }
                            currentPainter.drawElement(icon);
                        } catch (IOException e) {
                            log.error("加载图标 {} 失败", iconUrl, e);
                        }
                    }

                    for (int codePoint : node.getString("text").codePoints().toArray()) {
                        if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN)) {
                            images.add(currentPainter.getImage());
                            currentPainter = factory.create(WIDTH - TEXT_MARGIN * 2, 40, true);
                        }
                        currentPainter.drawElement(codePoint, CommonPainter.COLOR_LINK);
                    }
                }
                default -> log.warn("未处理的动态内容区块类型 {}: {}", type, JSON.toJSONString(this.dynamic));
            }
        }

        images.add(currentPainter.getImage());

        return images;
    }

    /**
     * 绘制动态图片
     * @param isForward 是否为转发源动态
     */
    private void drawImages(boolean isForward) {
        this.painter.setPos(IMAGE_MARGIN, this.painter.getY());

        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("opus");

        List<JSONObject> imageDatas = data.getJSONArray("pics").toList(JSONObject.class);

        if (imageDatas.isEmpty()) {
            return;
        }

        int imageCount = imageDatas.size();
        int lineCount;
        int size;
        if (imageCount == 1) {
            lineCount = 1;
            size = 0;
        } else if (imageCount == 2 || imageCount == 4) {
            lineCount = 2;
            size = (this.painter.getWidth() - IMAGE_MARGIN * 3) / 2;
        } else {
            lineCount = 3;
            size = (this.painter.getWidth() - IMAGE_MARGIN * 4) / 3;
        }

        List<String> urls = new ArrayList<>();
        for (JSONObject image : imageDatas) {
            String url = image.getString("url");
            if (lineCount == 1) {
                url = url + "@518w.webp";
            } else {
                if (image.containsKey("height") && image.containsKey("width") && image.getDouble("height") / image.getDouble("width") >= 3) {
                    url = url + "@" + size + "w_" + size + "h_!header.webp";
                } else {
                    url = url + "@" + size + "w_" + size + "h_1e_1c.webp";
                }
            }
            urls.add(url);
        }

        List<BufferedImage> images = bilibili.asyncGetBilibiliImages(urls)
                .thenApply(optImages -> optImages.stream()
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(image -> {
                            if (size == 0) {
                                return ImageUtil.resizeByWidth(image, this.painter.getWidth() - IMAGE_MARGIN * 2);
                            }
                            if (image.getWidth() == image.getHeight()) {
                                return ImageUtil.resize(image, size, size);
                            } else if (image.getWidth() > image.getHeight()) {
                                BufferedImage resizedImage = ImageUtil.resizeByHeight(image, size);
                                return resizedImage.getSubimage((resizedImage.getWidth() - size) / 2, 0, size, size);
                            } else {
                                BufferedImage resizedImage = ImageUtil.resizeByWidth(image, size);
                                return resizedImage.getSubimage(0, (resizedImage.getHeight() - size) / 2, size, size);
                            }
                        })
                        .collect(Collectors.toList()))
                .join();

        List<List<BufferedImage>> lines = CollectionUtil.splitCollection(images, lineCount);

        if (isForward) {
            int height = lines.stream().mapToInt(line -> line.get(0).getHeight()).sum() + this.painter.getRowSpace() * lines.size();
            this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
        }

        for (List<BufferedImage> line : lines) {
            for (int i = 0; i < line.size(); i++) {
                BufferedImage image = line.get(i);
                if (i != line.size() - 1) {
                    this.painter.drawImage(image, new Point(this.painter.getX(), this.painter.getY())).movePos(image.getWidth() + IMAGE_MARGIN, 0);
                } else {
                    this.painter.drawImage(image).setPos(IMAGE_MARGIN, this.painter.getY());
                }
            }
        }
    }

    /**
     * 绘制充电专属动态区域
     */
    private void drawBlockArea() {
        Dynamic currentDynamic = getCurrentDynamic(false);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("blocked");

        CommonPainter painter = factory.create(this.painter.getWidth() - IMAGE_MARGIN * 2, 200);

        String backgroundUrl = data.getJSONObject("bg_img").getString("img_day");
        Optional<BufferedImage> optionalBackgroundImage = bilibili.getBilibiliImage(backgroundUrl);
        optionalBackgroundImage.ifPresent(sourceImage -> {
            BufferedImage image = ImageUtil.resizeByHeight(sourceImage, 200);
            painter.drawImage(image, new Point(0, 0));
        });

        int iconWidth = 0;
        String iconUrl = data.getJSONObject("icon").getString("img_day");
        Optional<BufferedImage> optionalIconImage = bilibili.getBilibiliImage(iconUrl);
        if (optionalIconImage.isPresent()) {
            BufferedImage image = ImageUtil.resizeByHeight(optionalIconImage.get(), 150);
            iconWidth = image.getWidth();
            painter.drawImage(image, new Point(25, 25));
        }

        String buttonText = data.getJSONObject("button").getString("text");
        Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);
        painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond() - 10) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, COLOR_PINK);
        painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond() - 10) / 2 + 5));

        String title = data.getString("title");
        String hint = data.getString("hint_message");
        List<BufferedImage> lineImages = new ArrayList<>();
        int lineWidth = painter.getWidth() - iconWidth - 25 - size.getFirst() - IMAGE_MARGIN * 8;
        lineImages.add(getOmitTextImage(title, lineWidth, 40));

        CommonPainter currentPainter = factory.create(lineWidth, 40, true);
        List<Integer> tail = "...".codePoints().boxed().toList();
        for (int codePoint : hint.codePoints().toArray()) {
            Font font = fontUtil.findFontForCharacter(codePoint).deriveFont(25f);
            if (lineImages.size() == 1) {
                if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN, font)) {
                    lineImages.add(currentPainter.getImage());
                    currentPainter = factory.create(lineWidth, 40, true);
                }
            } else {
                List<Integer> codePoints = new ArrayList<>();
                codePoints.add(codePoint);
                codePoints.addAll(tail);
                if (currentPainter.isNeedWrap(codePoints, TEXT_MARGIN, font)) {
                    for (int tailCodePoint : tail) {
                        currentPainter.drawElement(tailCodePoint, Color.LIGHT_GRAY);
                    }
                    break;
                }
            }
            currentPainter.drawElement(codePoint, font, Color.LIGHT_GRAY);
        }
        lineImages.add(currentPainter.getImage());

        int heightTotal = lineImages.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * (lineImages.size() - 1);
        painter.setPos(iconWidth + 25 + IMAGE_MARGIN * 2, (painter.getHeight() - heightTotal) / 2);
        for (BufferedImage image : lineImages) {
            painter.drawImage(image);
        }

        BufferedImage image = painter.getImage();

        this.painter.drawImageWithBorder(image, new Point(IMAGE_MARGIN, this.painter.getY()), Color.LIGHT_GRAY, 10, 1);
        this.painter.setPos(TEXT_MARGIN, this.painter.getY() + image.getHeight() + this.painter.getRowSpace());
    }

    /**
     * 绘制文章动态内容
     * @param isForward 是否为转发源动态
     */
    private void drawArticleContent(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("opus");

        List<JSONObject> nodes = new ArrayList<>(data.getJSONObject("summary").getJSONArray("rich_text_nodes").toList(JSONObject.class));

        JSONObject contentNode = new JSONObject();
        contentNode.put("type", "RICH_TEXT_NODE_TYPE_TEXT");
        contentNode.put("text", "...");
        nodes.add(contentNode);

        JSONObject node = new JSONObject();
        node.put("type", "RICH_TEXT_NODE_TYPE_AT");
        node.put("text", "全文");
        nodes.add(node);

        List<BufferedImage> images = getContentLineImages(nodes);

        if (isForward) {
            int height = images.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * images.size();
            this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
        }

        images.forEach(this.painter::drawImage);
    }

    /**
     * 绘制视频内容
     * @param isForward 是否为转发源动态
     */
    private void drawVideoContent(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("desc");

        if (data == null) {
            return;
        }

        List<JSONObject> nodes = new ArrayList<>(data.getJSONArray("rich_text_nodes").toList(JSONObject.class));

        List<BufferedImage> images = getContentLineImages(nodes);

        if (isForward) {
            int height = images.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * images.size();
            this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
        }

        images.forEach(this.painter::drawImage);
    }

    /**
     * 绘制视频封面
     * @param isForward 是否为转发源动态
     */
    private void drawVideoCover(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("archive");

        String coverUrl = data.getString("cover");
        Optional<BufferedImage> optionalImage = bilibili.getBilibiliImage(coverUrl);
        optionalImage.ifPresent(sourceImage -> {
            BufferedImage image = ImageUtil.maskToRoundedRectangle(ImageUtil.resizeByWidth(sourceImage, this.painter.getWidth() - IMAGE_MARGIN * 2), 10);

            int height = image.getHeight() + this.painter.getRowSpace();
            if (isForward) {
                this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
            }

            this.painter.drawImage(image, new Point(IMAGE_MARGIN, this.painter.getY()));

            String timeText = data.getString("duration_text");
            Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(timeText);
            this.painter.drawRoundedRectangle(IMAGE_MARGIN + 13, this.painter.getY() + image.getHeight() - size.getSecond() - 22, size.getFirst() + 16, size.getSecond() + 10, 10, new Color(115, 115, 115, 153));
            this.painter.drawText(timeText, Color.WHITE, new Point(IMAGE_MARGIN + 21, this.painter.getY() + image.getHeight() - size.getSecond() - 17));

            JSONObject stat = data.getJSONObject("stat");
            String counts = stat.getString("play") + "观看   " + stat.getString("danmaku") + "弹幕";
            this.painter.drawText(counts, Color.WHITE, new Point(IMAGE_MARGIN + size.getFirst() + 53, this.painter.getY() + image.getHeight() - size.getSecond() - 17));

            try {
                BufferedImage tv = ImageIO.read(resourceLoader.getResource("classpath:images/dynamic/tv.png").getInputStream());
                this.painter.drawImage(tv, new Point(IMAGE_MARGIN + image.getWidth() - tv.getWidth() - 16, this.painter.getY() + image.getHeight() - tv.getHeight() - 13));
            } catch (IOException e) {
                log.error("加载图标失败", e);
            }

            this.painter.setPos(TEXT_MARGIN, this.painter.getY() + height);
        });
    }

    /**
     * 绘制视频标题
     * @param isForward 是否为转发源动态
     */
    private void drawVideoTitle(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major").getJSONObject("archive");

        String title = data.getString("title");

        BufferedImage image = getOmitTextImage(title, WIDTH - TEXT_MARGIN * 2, 40);

        if (isForward) {
            int height = image.getHeight() + this.painter.getRowSpace();
            this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
        }

        this.painter.drawImage(image);
    }

    /**
     * 获取自动省略的文字图片
     * @param content 文字
     * @param width 图片宽度
     * @param height 图片高度，会自动扩展
     * @return 图片
     */
    private BufferedImage getOmitTextImage(String content, int width, int height) {
        return getOmitTextImage(content, width, height, null, null);
    }

    /**
     * 获取自动省略的文字图片
     * @param content 文字
     * @param width 图片宽度
     * @param height 图片高度，会自动扩展
     * @param size 字体大小
     * @return 图片
     */
    private BufferedImage getOmitTextImage(String content, int width, int height, @Nullable Integer size) {
        return getOmitTextImage(content, width, height, size, null);
    }

    /**
     * 获取自动省略的文字图片
     * @param content 文字
     * @param width 图片宽度
     * @param height 图片高度，会自动扩展
     * @param size 字体大小
     * @param color 字体颜色
     * @return 图片
     */
    private BufferedImage getOmitTextImage(String content, int width, int height, @Nullable Integer size, @Nullable Color color) {
        return getOmitTextImage(content, width, height, size, color, null);
    }

    /**
     * 获取自动省略的文字图片
     * @param content 文字
     * @param width 图片宽度
     * @param height 图片高度，会自动扩展
     * @param size 字体大小
     * @param color 字体颜色
     * @param tail 省略符
     * @return 图片
     */
    private BufferedImage getOmitTextImage(String content, int width, int height, @Nullable Integer size, @Nullable Color color, @Nullable String tail) {
        CommonPainter painter = factory.create(width, height, true);

        List<Integer> tailCodePoints;
        tailCodePoints = Objects.requireNonNullElse(tail, "...").codePoints().boxed().toList();

        for (int codePoint : content.codePoints().toArray()) {
            List<Integer> codePoints = new ArrayList<>();
            codePoints.add(codePoint);
            codePoints.addAll(tailCodePoints);

            if (painter.isNeedWrap(codePoints, TEXT_MARGIN, size)) {
                for (int tailCodePoint : tailCodePoints) {
                    painter.drawElement(tailCodePoint, color, size);
                }

                return painter.getImage();
            }
            painter.drawElement(codePoint, color, size);
        }

        return painter.getImage();
    }

    /**
     * 绘制通用区域
     * @param isForward 是否为转发源动态
     */
    private void drawCommonArea(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("major");

        String type = data.getString("type");

        switch (type) {
            case "MAJOR_TYPE_COMMON" -> {
                JSONObject common = data.getJSONObject("common");

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);

                String coverUrl = common.getString("cover");
                Optional<BufferedImage> optionalImage = bilibili.getBilibiliImage(coverUrl);
                optionalImage.ifPresent(sourceImage -> {
                    BufferedImage image = ImageUtil.resizeByHeight(sourceImage, 200);
                    painter.drawImage(image);

                    painter.drawText(common.getString("title"), new Point(image.getWidth() + 35, 40));
                    painter.drawTip(common.getString("desc"), new Point(image.getWidth() + 35, 105));
                });

                JSONObject badge = common.getJSONObject("badge");
                String badgeText = badge.getString("text");
                if (StringUtil.isNotBlank(badgeText)) {
                    Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(badgeText);
                    painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond() - 10) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, new Color(Integer.parseInt(badge.getString("bg_color").substring(1), 16)));
                    painter.drawText(badgeText, new Color(Integer.parseInt(badge.getString("color").substring(1), 16)), new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond() - 10) / 2 + 5));
                }

                BufferedImage image = painter.getImage();

                int height = image.getHeight() + this.painter.getRowSpace();
                if (isForward) {
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                this.painter.drawImageWithBorder(image, new Point(IMAGE_MARGIN, this.painter.getY()), Color.LIGHT_GRAY, 10, 1);
                this.painter.setPos(TEXT_MARGIN, this.painter.getY() + height);
            }
            case "MAJOR_TYPE_UPOWER_COMMON" -> {
                JSONObject common = data.getJSONObject("upower_common");

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);

                String backgroundUrl = common.getJSONObject("background").getString("light_src");
                Optional<BufferedImage> optionalBackgroundImage = bilibili.getBilibiliImage(backgroundUrl);
                optionalBackgroundImage.ifPresent(sourceImage -> {
                    BufferedImage image = ImageUtil.resizeByHeight(sourceImage, 200);
                    painter.drawImage(image, new Point(0, 0));
                });

                int iconWidth = 0;
                String iconUrl = common.getJSONObject("icon").getString("light_src");
                Optional<BufferedImage> optionalIconImage = bilibili.getBilibiliImage(iconUrl);
                if (optionalIconImage.isPresent()) {
                    BufferedImage image = ImageUtil.resizeByHeight(optionalIconImage.get(), 150);
                    iconWidth = image.getWidth();
                    painter.drawImage(image, new Point(25, 25));
                }

                String buttonText = common.getJSONObject("button").getJSONObject("jump_style").getString("text");
                Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);
                painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond() - 10) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, COLOR_PINK);
                painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond() - 10) / 2 + 5));

                String prefix = common.getString("title_prefix");
                String title = common.getString("title");
                List<BufferedImage> lineImages = new ArrayList<>();
                int lineWidth = painter.getWidth() - iconWidth - 25 - size.getFirst() - IMAGE_MARGIN * 8;
                CommonPainter currentPainter = factory.create(lineWidth, 40, true);
                for (int codePoint : prefix.codePoints().toArray()) {
                    Font font = fontUtil.findFontForCharacter(codePoint).deriveFont(Font.BOLD);
                    if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN, font)) {
                        lineImages.add(currentPainter.getImage());
                        currentPainter = factory.create(lineWidth, 40, true);
                    }
                    currentPainter.drawElement(codePoint, font);
                }
                List<Integer> tail = "...".codePoints().boxed().toList();
                for (int codePoint : title.codePoints().toArray()) {
                    Font font = fontUtil.findFontForCharacter(codePoint);
                    if (lineImages.isEmpty()) {
                        if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN, font)) {
                            lineImages.add(currentPainter.getImage());
                            currentPainter = factory.create(lineWidth, 40, true);
                        }
                    } else {
                        List<Integer> codePoints = new ArrayList<>();
                        codePoints.add(codePoint);
                        codePoints.addAll(tail);
                        if (currentPainter.isNeedWrap(codePoints, TEXT_MARGIN, font)) {
                            for (int tailCodePoint : tail) {
                                currentPainter.drawElement(tailCodePoint);
                            }
                            break;
                        }
                    }
                    currentPainter.drawElement(codePoint, font);
                }
                lineImages.add(currentPainter.getImage());

                int heightTotal = lineImages.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * (lineImages.size() - 1);
                painter.setPos(iconWidth + 25 + IMAGE_MARGIN * 2, (painter.getHeight() - heightTotal) / 2);
                for (BufferedImage image : lineImages) {
                    painter.drawImage(image);
                }

                BufferedImage image = painter.getImage();

                int height = image.getHeight() + this.painter.getRowSpace();
                if (isForward) {
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                this.painter.drawImageWithBorder(image, new Point(IMAGE_MARGIN, this.painter.getY()), Color.LIGHT_GRAY, 10, 1);
                this.painter.setPos(TEXT_MARGIN, this.painter.getY() + height);
            }
        }
    }

    /**
     * 绘制转发源动态的用户名
     */
    private void drawOriginDynamicUname() {
        String uname = this.dynamic.getOrigin().getModules().getJSONObject("module_author").getString("name");

        JSONObject node = new JSONObject();
        node.put("type", "RICH_TEXT_NODE_TYPE_AT");
        node.put("text", "@" + uname);

        List<BufferedImage> images = getContentLineImages(List.of(node));

        int height = images.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * images.size();
        this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);

        images.forEach(this.painter::drawImage);
    }

    /**
     * 绘制附加卡片
     * @param isForward 是否为转发源动态
     */
    private void drawAddOnCard(boolean isForward) {
        Dynamic currentDynamic = getCurrentDynamic(isForward);
        JSONObject data = currentDynamic.getModules().getJSONObject("module_dynamic").getJSONObject("additional");

        if (data == null) {
            return;
        }

        String type = data.getString("type");

        switch (type) {
            case "ADDITIONAL_TYPE_COMMON" -> {
                JSONObject common = data.getJSONObject("common");

                String headText = common.getString("head_text");
                BufferedImage headImage = getOmitTextImage(headText, WIDTH - TEXT_MARGIN * 2, 25, 25, Color.LIGHT_GRAY);
                if (isForward) {
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), headImage.getHeight() + this.painter.getRowSpace(), COLOR_FORWARD);
                }
                this.painter.drawImage(headImage);

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                int coverWidth = 0;
                String coverUrl = common.getString("cover");
                Optional<BufferedImage> optionalCoverImage = bilibili.getBilibiliImage(coverUrl);
                if (optionalCoverImage.isPresent()) {
                    BufferedImage coverImage = ImageUtil.resizeByHeight(optionalCoverImage.get(), 150);
                    coverWidth = coverImage.getWidth();
                    painter.drawImage(coverImage, new Point(25, 25));
                }

                String title = common.getString("title");
                String desc1 = common.getString("desc1");
                String desc2 = common.getString("desc2");
                Pair<Integer, Integer> titleSize = this.painter.getStringWidthAndHeight(title);
                Pair<Integer, Integer> desc1Size = this.painter.getStringWidthAndHeight(desc1, 25);
                Pair<Integer, Integer> desc2Size = this.painter.getStringWidthAndHeight(desc2, 25);
                int marginY = (200 - titleSize.getSecond() - desc1Size.getSecond() - desc2Size.getSecond() - 30) / 2;
                painter.drawText(title, new Point(coverWidth + 50, marginY));
                painter.drawTip(desc1, new Point(coverWidth + 50, marginY + titleSize.getSecond() + 15));
                painter.drawTip(desc2, new Point(coverWidth + 50, marginY + titleSize.getSecond() + desc1Size.getSecond() + 30));

                String buttonText = common.getJSONObject("button").getJSONObject("jump_style").getString("text");
                Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);

                painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond()) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, COLOR_PINK);
                painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond()) / 2 + 5));

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            case "ADDITIONAL_TYPE_GOODS" -> {
                JSONObject goods = data.getJSONObject("goods");

                String headText = goods.getString("head_text");
                BufferedImage headImage = getOmitTextImage(headText, WIDTH - TEXT_MARGIN * 2, 25, 25, Color.LIGHT_GRAY);
                if (isForward) {
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), headImage.getHeight() + this.painter.getRowSpace(), COLOR_FORWARD);
                }
                this.painter.drawImage(headImage);

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                JSONArray items = goods.getJSONArray("items");
                if (items.size() == 1) {
                    JSONObject item = items.getJSONObject(0);

                    int coverWidth = 0;
                    String coverUrl = item.getString("cover");
                    Optional<BufferedImage> optionalCoverImage = bilibili.getBilibiliImage(coverUrl);
                    if (optionalCoverImage.isPresent()) {
                        BufferedImage coverImage = ImageUtil.resizeByHeight(optionalCoverImage.get(), 150);
                        coverWidth = coverImage.getWidth();
                        painter.drawImage(coverImage, new Point(25, 25));
                    }

                    String title = item.getString("name");
                    String price = item.getString("price") + " ";
                    String brief = item.getString("brief");
                    Pair<Integer, Integer> titleSize = this.painter.getStringWidthAndHeight(title);
                    BufferedImage omitTitleImage = getOmitTextImage(title, WIDTH - coverWidth - 50 - IMAGE_MARGIN * 4, 30);
                    if (StringUtil.isNotBlank(price)) {
                        Pair<Integer, Integer> contentSize = this.painter.getStringWidthAndHeight(price, 25);
                        int marginY = (200 - titleSize.getSecond() - contentSize.getSecond() - 25) / 2;
                        painter.drawImage(omitTitleImage, new Point(coverWidth + 50, marginY));
                        painter.drawTip(price, COLOR_LIGHT_BLUE, new Point(coverWidth + 50, marginY + titleSize.getSecond() + 25));
                        painter.drawTip("起", new Point(coverWidth + 50 + contentSize.getFirst(), marginY + titleSize.getSecond() + 25));
                    } else if (StringUtil.isNotBlank(brief)) {
                        Pair<Integer, Integer> contentSize = this.painter.getStringWidthAndHeight(brief, 25);
                        int marginY = (200 - titleSize.getSecond() - contentSize.getSecond() - 25) / 2;
                        painter.drawImage(omitTitleImage, new Point(coverWidth + 50, marginY));
                        painter.drawTip(brief, new Point(coverWidth + 50, marginY + titleSize.getSecond() + 25));
                    } else {
                        painter.drawImage(omitTitleImage, new Point(coverWidth + 50, (200 - titleSize.getSecond()) / 2));
                    }

                    String buttonText = item.getString("jump_desc") + " >";
                    Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);

                    painter.drawText(buttonText, Color.PINK, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 2, (painter.getHeight() - size.getSecond()) - IMAGE_MARGIN * 2));
                } else if (items.size() > 1) {
                    List<String> coverUrls = items.stream()
                            .map(item -> ((JSONObject) item).getString("cover"))
                            .toList();

                    List<BufferedImage> images = bilibili.asyncGetBilibiliImages(coverUrls)
                            .thenApply(optImages -> optImages.stream()
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .map(image -> ImageUtil.resizeByHeight(image, 150))
                                    .collect(Collectors.toList()))
                            .join();

                    int xOffset = 25;
                    for (BufferedImage image : images) {
                        if (xOffset + image.getWidth() <= painter.getWidth() - 25) {
                            painter.drawImage(image, new Point(xOffset, 25));
                            xOffset += image.getWidth() + IMAGE_MARGIN;
                        } else {
                            painter.drawImage(image.getSubimage(0, 0, painter.getWidth() - xOffset - 25, image.getHeight()), new Point(xOffset, 25));
                            break;
                        }
                    }
                }

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            case "ADDITIONAL_TYPE_MATCH" -> {
                JSONObject match = data.getJSONObject("match");

                String headText = match.getString("head_text");
                BufferedImage headImage = getOmitTextImage(headText, WIDTH - TEXT_MARGIN * 2, 25, 25, Color.LIGHT_GRAY);
                if (isForward) {
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), headImage.getHeight() + this.painter.getRowSpace(), COLOR_FORWARD);
                }
                this.painter.drawImage(headImage);

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                JSONObject info = match.getJSONObject("match_info");

                String title = info.getString("title");
                Pair<Integer, Integer> titleSize = this.painter.getStringWidthAndHeight(title);
                painter.drawText(title, new Point(25, (200 - titleSize.getSecond()) / 2));

                painter.drawRectangle(50 + titleSize.getFirst(), 25, 1, 150, Color.LIGHT_GRAY);

                String buttonText = match.getJSONObject("button").getJSONObject("jump_style").getString("text");
                Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);

                painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond()) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, COLOR_PINK);
                painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond()) / 2 + 5));

                int leftMargin = 200 + titleSize.getFirst();
                int rightMargin = painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 - 150;

                JSONObject leftTeam = info.getJSONObject("left_team");
                String leftTeamName = leftTeam.getString("name");
                Pair<Integer, Integer> leftTeamNameSize = this.painter.getStringWidthAndHeight(leftTeamName);
                painter.drawText(leftTeamName, new Point(leftMargin, 200 - leftTeamNameSize.getSecond() - 25));
                String leftTeamIconUrl = leftTeam.getString("pic");
                Optional<BufferedImage> optionalLeftTeamIcon = bilibili.getBilibiliImage(leftTeamIconUrl);
                if (optionalLeftTeamIcon.isPresent()) {
                    BufferedImage leftTeamIcon = ImageUtil.resizeByHeight(optionalLeftTeamIcon.get(), 100);
                    painter.drawImage(leftTeamIcon, new Point(leftMargin + (leftTeamNameSize.getFirst() - leftTeamIcon.getWidth()) / 2, 25));
                }

                JSONObject rightTeam = info.getJSONObject("right_team");
                String rightTeamName = rightTeam.getString("name");
                Pair<Integer, Integer> rightTeamNameSize = this.painter.getStringWidthAndHeight(rightTeamName);
                painter.drawText(rightTeamName, new Point(rightMargin - rightTeamNameSize.getFirst(), 200 - rightTeamNameSize.getSecond() - 25));
                String rightTeamIconUrl = rightTeam.getString("pic");
                Optional<BufferedImage> optionalRightTeamIcon = bilibili.getBilibiliImage(rightTeamIconUrl);
                if (optionalRightTeamIcon.isPresent()) {
                    BufferedImage rightTeamIcon = ImageUtil.resizeByHeight(optionalRightTeamIcon.get(), 100);
                    painter.drawImage(rightTeamIcon, new Point(rightMargin - rightTeamNameSize.getFirst() + (rightTeamNameSize.getFirst() - rightTeamIcon.getWidth()) / 2, 25));
                }

                String score = info.getJSONArray("center_top").stream().map(s -> (String) s).collect(Collectors.joining(" "));
                Pair<Integer, Integer> scoreSize = this.painter.getStringWidthAndHeight(score, 50);
                String tip = info.getString("center_bottom");
                Pair<Integer, Integer> tipSize = this.painter.getStringWidthAndHeight(tip);
                int marginY = (200 - scoreSize.getSecond() - tipSize.getSecond()) / 3;
                painter.drawChapter(score, new Point(leftMargin + (rightMargin - leftMargin -scoreSize.getFirst()) / 2, marginY));
                painter.drawText(tip, new Point(leftMargin + (rightMargin - leftMargin -tipSize.getFirst()) / 2, scoreSize.getSecond() + marginY * 2));

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            case "ADDITIONAL_TYPE_RESERVE" -> {
                JSONObject reserve = data.getJSONObject("reserve");

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                String title = reserve.getString("title");
                String desc = reserve.getJSONObject("desc1").getString("text") + "   " + reserve.getJSONObject("desc2").getString("text");
                Pair<Integer, Integer> titleSize = this.painter.getStringWidthAndHeight(title);
                Pair<Integer, Integer> descSize = this.painter.getStringWidthAndHeight(desc, 25);
                int marginY = (200 - titleSize.getSecond() - descSize.getSecond() - 25) / 2;
                painter.drawText(title, new Point(25, marginY));
                painter.drawTip(desc, new Point(25, marginY + titleSize.getSecond() + 25));

                JSONObject button = reserve.getJSONObject("button").getJSONObject("uncheck");
                String buttonText = button.getString("text");
                Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);

                int iconWidth = 0;
                BufferedImage iconImage = null;
                String iconUrl = button.getString("icon_url");
                Optional<BufferedImage> optionalIconImage = bilibili.getBilibiliImage(iconUrl);
                if (optionalIconImage.isPresent()) {
                    iconImage = ImageUtil.resizeByHeight(optionalIconImage.get(), size.getSecond());
                    iconWidth = iconImage.getWidth();
                }

                painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - iconWidth - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond()) / 2, size.getFirst() + iconWidth + 16, size.getSecond() + 10, 10, COLOR_PINK);
                if (optionalIconImage.isPresent()) {
                    painter.drawImage(iconImage, new Point(painter.getWidth() - size.getFirst() - iconWidth - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond()) / 2 + 5));
                }
                painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond()) / 2 + 5));

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            case "ADDITIONAL_TYPE_UGC" -> {
                JSONObject ugc = data.getJSONObject("ugc");

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                int coverWidth = 0;
                String coverUrl = ugc.getString("cover");
                Optional<BufferedImage> optionalCoverImage = bilibili.getBilibiliImage(coverUrl);
                if (optionalCoverImage.isPresent()) {
                    BufferedImage coverImage = ImageUtil.resizeByHeight(optionalCoverImage.get(), 150);
                    coverWidth = coverImage.getWidth();
                    painter.drawImage(coverImage, new Point(25, 25));
                }

                String timeText = ugc.getString("duration");
                Pair<Integer, Integer> size = painter.getStringWidthAndHeight(timeText, 20);
                painter.drawRoundedRectangle(2 + coverWidth - size.getFirst(), 158 - size.getSecond(), size.getFirst() + 16, size.getSecond() + 10, 10, new Color(115, 115, 115, 153));
                TextWithStyle time = new TextWithStyle(timeText, 20, Color.WHITE);
                painter.drawTextWithStyle(Collections.singletonList(time), new Point(10 + coverWidth - size.getFirst(), 163 - size.getSecond()));

                String title = ugc.getString("title");
                String desc = ugc.getString("desc_second");

                List<BufferedImage> lineImages = new ArrayList<>();
                int lineWidth = painter.getWidth() - coverWidth - 75;
                CommonPainter currentPainter = factory.create(lineWidth, 40, true);
                List<Integer> tail = "...".codePoints().boxed().toList();
                for (int codePoint : title.codePoints().toArray()) {
                    Font font = fontUtil.findFontForCharacter(codePoint);
                    if (lineImages.isEmpty()) {
                        if (currentPainter.isNeedWrap(codePoint, TEXT_MARGIN, font)) {
                            lineImages.add(currentPainter.getImage());
                            currentPainter = factory.create(lineWidth, 40, true);
                        }
                    } else {
                        List<Integer> codePoints = new ArrayList<>();
                        codePoints.add(codePoint);
                        codePoints.addAll(tail);
                        if (currentPainter.isNeedWrap(codePoints, TEXT_MARGIN, font)) {
                            for (int tailCodePoint : tail) {
                                currentPainter.drawElement(tailCodePoint);
                            }
                            break;
                        }
                    }
                    currentPainter.drawElement(codePoint, font);
                }
                lineImages.add(currentPainter.getImage());

                int heightTotal = lineImages.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * lineImages.size();
                Pair<Integer, Integer> descSize = this.painter.getStringWidthAndHeight(desc, 25);
                int marginY = (200 - heightTotal - descSize.getSecond()) / 2;

                painter.setPos(coverWidth + 50, marginY);
                lineImages.forEach(painter::drawImage);
                painter.drawTip(desc);

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            case "ADDITIONAL_TYPE_UPOWER_LOTTERY" -> {
                JSONObject lottery = data.getJSONObject("upower_lottery");

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                String buttonText = lottery.getJSONObject("button").getJSONObject("jump_style").getString("text");
                Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);

                painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond()) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, COLOR_PINK);
                painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond()) / 2 + 5));

                int marginRight = painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 - 25;

                String title = lottery.getString("title");
                String hint = lottery.getJSONObject("hint").getString("text");
                String desc = lottery.getJSONObject("desc").getString("text");
                List<BufferedImage> lineImages = new ArrayList<>();
                lineImages.add(getOmitTextImage(title, marginRight, 40));
                lineImages.add(getOmitTextImage(hint, marginRight, 30, 25, Color.LIGHT_GRAY));
                lineImages.add(getOmitTextImage(desc, marginRight, 30, 25, COLOR_PINK, "... >"));

                int heightTotal = lineImages.stream().mapToInt(BufferedImage::getHeight).sum() + this.painter.getRowSpace() * (lineImages.size() - 1);
                painter.setPos(25, (200 - heightTotal) / 2);
                for (BufferedImage line : lineImages) {
                    painter.drawImage(line);
                }

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            case "ADDITIONAL_TYPE_VOTE" -> {
                JSONObject vote = data.getJSONObject("vote");

                CommonPainter painter = factory.create(WIDTH - IMAGE_MARGIN * 2, 200, false);
                if (isForward) {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, Color.WHITE);
                } else {
                    painter.drawRoundedRectangle(0, 0, painter.getWidth(), painter.getHeight(), 10, COLOR_FORWARD);
                }

                String iconUrl = "classpath:images/dynamic/tick.png";
                try (InputStream input = resourceLoader.getResource(iconUrl).getInputStream()) {
                    BufferedImage icon = ImageUtil.resize(ImageIO.read(input), 150, 150);
                    painter.drawImage(icon, new Point(25, 25));
                } catch (IOException e) {
                    log.error("加载图标 {} 失败", iconUrl, e);
                }

                String buttonText = vote.getJSONObject("button").getJSONObject("jump_style").getString("text");
                Pair<Integer, Integer> size = this.painter.getStringWidthAndHeight(buttonText);

                painter.drawRoundedRectangle(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4, (painter.getHeight() - size.getSecond()) / 2, size.getFirst() + 16, size.getSecond() + 10, 10, COLOR_PINK);
                painter.drawText(buttonText, Color.WHITE, new Point(painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 + 8, (painter.getHeight() - size.getSecond()) / 2 + 5));

                int marginRight = painter.getWidth() - size.getFirst() - IMAGE_MARGIN * 4 - 225;

                String title = vote.getString("title");
                String desc = vote.getString("desc");
                BufferedImage titleImage = getOmitTextImage(title, marginRight, 40);
                Pair<Integer, Integer> descSize = this.painter.getStringWidthAndHeight(desc, 25);

                painter.setPos(200, (200 - titleImage.getHeight() - descSize.getSecond() - this.painter.getRowSpace()) / 2);
                painter.drawImage(titleImage);
                painter.drawTip(desc);

                if (isForward) {
                    int height = painter.getHeight() + this.painter.getRowSpace();
                    this.painter.drawRectangle(0, this.painter.getY(), this.painter.getWidth(), height, COLOR_FORWARD);
                }

                BufferedImage image = painter.getImage();
                this.painter.drawImage(image);
            }
            default -> log.warn("未处理的附加卡片类型 {}: {}", type, JSON.toJSONString(this.dynamic));
        }
    }

    /**
     * 绘制底部版权信息
     */
    private void drawBottom() {
        this.painter.movePos(0, this.painter.getRowSpace());
        this.painter.drawCopyright(properties.getVersion(), TEXT_MARGIN);
    }

    /**
     * 绘制背景
     */
    private void drawBackground() {
        this.painter.createSolidRoundedRectangleBackground(Color.WHITE, 35);
    }
}
