package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.model.*;
import com.starlwr.bot.bilibili.protobuf.InteractWordV2;
import com.starlwr.bot.bilibili.protobuf.SendGiftV2;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.MathUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Bilibili 事件解析器
 */
@Slf4j
@StarBotComponent
public class BilibiliEventParser {
    private static final Logger liveMessageLogger = LoggerFactory.getLogger("LiveMessageLogger");

    private final StarBotBilibiliProperties properties;

    private final BilibiliApiUtil bilibili;

    private final BilibiliGiftService giftService;

    private final Map<String, BiFunction<JSONObject, LiveStreamerInfo, List<StarBotBaseLiveEvent>>> parsers = Map.ofEntries(
            Map.entry("LIVE", BilibiliEventParser.this::parseLiveOnData),
            Map.entry("PREPARING", BilibiliEventParser.this::parseLiveOffData),
            Map.entry("INTERACT_WORD", BilibiliEventParser.this::parseOperationData),
            Map.entry("INTERACT_WORD_V2", BilibiliEventParser.this::parseOperationDataV2),
            Map.entry("DANMU_MSG", BilibiliEventParser.this::parseMessageData),
            Map.entry("SEND_GIFT", BilibiliEventParser.this::parseGiftData),
            Map.entry("SEND_GIFT_V2", BilibiliEventParser.this::parseGiftDataV2),
            Map.entry("SUPER_CHAT_MESSAGE", BilibiliEventParser.this::parseSuperChatData),
            Map.entry("USER_TOAST_MSG", BilibiliEventParser.this::parseGuardData),
            Map.entry("LIKE_INFO_V3_CLICK", BilibiliEventParser.this::parseLikeData),
            Map.entry("LIKE_INFO_V3_UPDATE", BilibiliEventParser.this::parseLikeUpdateData)
    );

    @Autowired
    public BilibiliEventParser(StarBotBilibiliProperties properties, BilibiliApiUtil bilibili, BilibiliGiftService giftService) {
        this.properties = properties;
        this.bilibili = bilibili;
        this.giftService = giftService;
    }

    /**
     * 将直播间收到的原始消息转换为事件列表
     *
     * @param data 原始消息
     * @param source 主播信息
     * @return 解析出的事件列表
     */
    List<StarBotBaseLiveEvent> parseEvents(JSONObject data, LiveStreamerInfo source) {
        String type = data.getString("cmd");
        if (properties.getDebug().isLiveRoomRawMessageLog()) {
            liveMessageLogger.debug("{}: {} -> {}", type, source.getRoomId(), data.toJSONString());
        }

        try {
            BiFunction<JSONObject, LiveStreamerInfo, List<StarBotBaseLiveEvent>> parser = parsers.get(type);
            if (parser != null) {
                return parser.apply(data, source);
            }
        } catch (Exception e) {
            log.error("处理直播间 {} 的 {} 类型消息异常: {}", source.getRoomId(), type, data.toJSONString(), e);
        }

        return List.of();
    }

    /**
     * 解析原始直播间开播数据（LIVE）
     * @param data 原始直播间开播数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseLiveOnData(JSONObject data, LiveStreamerInfo source) {
        Long liveTime = data.getLong("live_time");

        if (liveTime != null) {
            Instant timestamp = Instant.ofEpochSecond(liveTime);
            return List.of(new BilibiliLiveOnEvent(source, timestamp));
        }

        return List.of();
    }

    /**
     * 解析原始直播间下播数据（PREPARING）
     * @param data 原始直播间下播数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseLiveOffData(JSONObject data, LiveStreamerInfo source) {
        Instant timestamp = Instant.ofEpochMilli(data.getLong("send_time"));

        return List.of(new BilibiliLiveOffEvent(source, timestamp));
    }

    /**
     * 解析原始直播间操作数据（INTERACT_WORD）
     * @param data 原始直播间操作数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseOperationData(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONObject metaData = data.getJSONObject("data");

        JSONObject senderInfo = metaData.getJSONObject("uinfo");
        JSONObject senderBaseInfo = senderInfo.getJSONObject("base");
        Long senderUid = metaData.getLong("uid");
        String senderUname = metaData.getString("uname");
        String senderFace = senderBaseInfo.getString("face");

        FansMedal fansMedal = null;
        JSONObject fansMedalInfo = metaData.getJSONObject("fans_medal");
        if (fansMedalInfo != null && fansMedalInfo.getLong("target_id") != 0L) {
            Long fansMedalUid = fansMedalInfo.getLong("target_id");
            Long fansMedalRoomId = fansMedalInfo.getLong("anchor_roomid");
            String fansMedalName = fansMedalInfo.getString("medal_name");
            Integer fansMedalLevel = fansMedalInfo.getInteger("medal_level");
            Boolean fansMedalLighted = fansMedalInfo.getInteger("is_lighted") == 1;
            if (completeEvent) {
                String fansMedalUname = completeUname(fansMedalUid, source).orElse(null);
                String fansMedalFace = completeFace(fansMedalUid, source).orElse(null);
                fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalFace, fansMedalName, fansMedalLevel, fansMedalLighted);
            } else {
                fansMedal = new FansMedal(fansMedalUid, null, fansMedalRoomId, fansMedalName, fansMedalLevel, fansMedalLighted);
            }
        }

        JSONObject guardInfo = senderInfo.getJSONObject("medal");
        Guard guard = (guardInfo != null && guardInfo.getInteger("guard_level") != 0)
                ? new Guard(guardInfo.getInteger("guard_level"), guardInfo.getString("guard_icon"))
                : null;

        Integer honorLevel = Optional.ofNullable(senderInfo.getJSONObject("wealth"))
                .map(wealth -> wealth.getInteger("level"))
                .orElse(null);

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, fansMedal, guard, honorLevel);

        Instant timestamp = Instant.ofEpochSecond(metaData.getLong("timestamp"));
        int msgType = metaData.getInteger("msg_type");
        switch (msgType) {
            case 1 -> {
                boolean fromPromotion = metaData.getInteger("is_spread") == 1;
                String promotionSource = metaData.getString("spread_desc");
                if (promotionSource != null && promotionSource.isBlank()) {
                    promotionSource = null;
                }
                return List.of(new BilibiliEnterRoomEvent(source, sender, fromPromotion, promotionSource, timestamp));
            }
            case 2 -> {
                return List.of(new BilibiliFollowEvent(source, sender, timestamp));
            }
            case 3 -> {
                return List.of(new BilibiliShareEvent(source, sender, timestamp));
            }
            default -> {
                log.warn("未处理的直播间操作消息类型: {}, 内容: {}", msgType, data.toJSONString());
                return List.of();
            }
        }
    }

    /**
     * 解析原始新版直播间操作数据（INTERACT_WORD_V2）
     * @param data 原始新版直播间操作数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseOperationDataV2(JSONObject data, LiveStreamerInfo source) {
        JSONObject metaData = data.getJSONObject("data");
        String encodedPayload = metaData.getString("pb");

        InteractWordV2 payload;
        try {
            payload = InteractWordV2.parseFrom(Base64.getDecoder().decode(encodedPayload));
        } catch (IOException | IllegalArgumentException e) {
            log.error("解析直播间 {} 的 INTERACT_WORD_V2 消息异常, 内容: {}", source.getRoomId(), data.toJSONString(), e);
            return List.of();
        }

        boolean completeEvent = properties.getLive().isCompleteEvent();
        InteractWordV2.UserInfo userInfo = payload.getUserInfo();

        long senderUid = payload.getUid();
        String senderUname = payload.getUname();
        String senderFace = userInfo.getBase().getFace();

        FansMedal fansMedal = null;
        InteractWordV2.FansMedalInfo medalInfo = payload.getFansMedal();
        if (medalInfo.isPresent()) {
            if (completeEvent) {
                String fansMedalUname = completeUname(medalInfo.getTargetId(), source).orElse(null);
                Long fansMedalRoomId = completeRoomId(medalInfo.getTargetId(), source).orElse(null);
                String fansMedalFace = completeFace(medalInfo.getTargetId(), source).orElse(null);
                fansMedal = new FansMedal(medalInfo.getTargetId(), fansMedalUname, fansMedalRoomId, fansMedalFace, medalInfo.getName(), medalInfo.getLevel(), medalInfo.isLighted());
            } else {
                fansMedal = new FansMedal(medalInfo.getTargetId(), null, null, medalInfo.getName(), medalInfo.getLevel(), medalInfo.isLighted());
            }
        }

        Guard guard = payload.getPrivilegeType() == 0 ? null : new Guard(payload.getPrivilegeType());

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, fansMedal, guard, userInfo.getWealthLevel());
        Instant timestamp = Instant.ofEpochSecond(payload.getTimestamp());
        int msgType = payload.getMsgType();
        switch (msgType) {
            case 1 -> {
                String promotionSource = payload.getSpreadDesc();
                if (promotionSource != null && promotionSource.isBlank()) {
                    promotionSource = null;
                }
                return List.of(new BilibiliEnterRoomEvent(source, sender, payload.isSpread(), promotionSource, timestamp));
            }
            case 2 -> {
                return List.of(new BilibiliFollowEvent(source, sender, timestamp));
            }
            case 3 -> {
                return List.of(new BilibiliShareEvent(source, sender, timestamp));
            }
            default -> {
                log.warn("未处理的直播间操作消息类型: {}, 内容: {}", msgType, data.toJSONString());
                return List.of();
            }
        }
    }

    /**
     * 解析原始直播间消息数据（DANMU_MSG）
     * @param data 原始直播间消息数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseMessageData(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONArray info = data.getJSONArray("info");
        JSONArray primaryInfo = info.getJSONArray(0);
        JSONObject metaInfo = primaryInfo.getJSONObject(15);

        JSONObject senderInfo = metaInfo.getJSONObject("user");
        JSONObject senderBaseInfo = senderInfo.getJSONObject("base");
        Long senderUid = senderInfo.getLong("uid");

        String senderUname = null;
        String senderFace = null;
        if (senderBaseInfo != null) {
            senderUname = senderBaseInfo.getString("name");
            senderFace = senderBaseInfo.getString("face");
        } else {
            if (completeEvent) {
                senderUname = completeUname(senderUid, source).orElse(null);
                senderFace = completeFace(senderUid, source).orElse(null);
            }
        }

        FansMedal fansMedal = null;
        JSONArray fansMedalInfo = info.getJSONArray(3);
        if (!fansMedalInfo.isEmpty()) {
            Long fansMedalUid = fansMedalInfo.getLong(12);
            String fansMedalUname = fansMedalInfo.getString(2);
            Long fansMedalRoomId = fansMedalInfo.getLong(3);
            String fansMedalName = fansMedalInfo.getString(1);
            Integer fansMedalLevel = fansMedalInfo.getInteger(0);
            Boolean fansMedalLighted = fansMedalInfo.getInteger(11) == 1;
            if (completeEvent) {
                String fansMedalFace = completeFace(fansMedalUid, source).orElse(null);
                fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalFace, fansMedalName, fansMedalLevel, fansMedalLighted);
            } else {
                fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalName, fansMedalLevel, fansMedalLighted);
            }
        }

        JSONObject guardInfo = senderInfo.getJSONObject("medal");
        Guard guard = (guardInfo != null && guardInfo.getInteger("guard_level") != 0)
                ? new Guard(guardInfo.getInteger("guard_level"), guardInfo.getString("guard_icon"))
                : null;

        Integer honorLevel = info.getJSONArray(16).getInteger(0);

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, fansMedal, guard, honorLevel);

        Instant timestamp = Instant.ofEpochMilli(primaryInfo.getLong(4));

        boolean isDanmu = primaryInfo.get(13) instanceof String;
        JSONObject danmuInfo = JSON.parseObject(metaInfo.getString("extra"));
        if (isDanmu) {
            UserInfo reply = null;
            Long replyUid = danmuInfo.getLong("reply_mid");
            if (replyUid != 0L) {
                String replyUname = danmuInfo.getString("reply_uname");
                if (completeEvent) {
                    String replyFace = completeFace(replyUid, source).orElse(null);
                    reply = new UserInfo(replyUid, replyUname, replyFace);
                } else {
                    reply = new UserInfo(replyUid, replyUname);
                }
            }

            String content = danmuInfo.getString("content");

            JSONObject emojiInfos = danmuInfo.getJSONObject("emots");
            if (emojiInfos != null) {
                List<BilibiliEmojiInfo> emojis = new ArrayList<>();
                String contentText = content;

                for (String emojiName: emojiInfos.keySet()) {
                    contentText = contentText.replace(emojiName, "");

                    JSONObject emojiInfo = emojiInfos.getJSONObject(emojiName);
                    String emojiId = emojiInfo.getString("emoticon_unique");
                    String emojiUrl = emojiInfo.getString("url");
                    Integer emojiWidth = emojiInfo.getInteger("width");
                    Integer emojiHeight = emojiInfo.getInteger("height");
                    Integer emojiCount = emojiInfo.getInteger("count");
                    BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(emojiId, emojiName, emojiUrl, emojiWidth, emojiHeight, emojiCount);
                    emojis.add(emoji);
                }

                return List.of(new BilibiliDanmuEvent(source, sender, reply, content, contentText, emojis, timestamp));
            }

            return List.of(new BilibiliDanmuEvent(source, sender, reply, content, timestamp));
        } else {
            JSONObject emojiInfo = primaryInfo.getJSONObject(13);
            String emojiId = emojiInfo.getString("emoticon_unique");
            String emojiName = danmuInfo.getString("content");
            String emojiUrl = emojiInfo.getString("url");
            Integer emojiWidth = emojiInfo.getInteger("width");
            Integer emojiHeight = emojiInfo.getInteger("height");
            BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(emojiId, emojiName, emojiUrl, emojiWidth, emojiHeight);

            return List.of(new BilibiliEmojiEvent(source, sender, emoji, timestamp));
        }
    }

    /**
     * 解析原始直播间礼物数据（SEND_GIFT）
     * @param data 原始直播间礼物数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseGiftData(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONObject metaData = data.getJSONObject("data");

        Long senderUid = metaData.getLong("uid");
        String senderUname = metaData.getString("uname");
        String senderFace = metaData.getString("face");

        FansMedal fansMedal = null;
        JSONObject fansMedalInfo = metaData.getJSONObject("sender_uinfo").getJSONObject("medal");
        if (fansMedalInfo != null) {
            Long fansMedalUid = fansMedalInfo.getLong("ruid");
            String fansMedalName = fansMedalInfo.getString("name");
            Integer fansMedalLevel = fansMedalInfo.getInteger("level");
            Boolean fansMedalLighted = fansMedalInfo.getInteger("is_light") == 1;
            if (completeEvent) {
                String fansMedalUname = completeUname(fansMedalUid, source).orElse(null);
                Long fansMedalRoomId = completeRoomId(fansMedalUid, source).orElse(null);
                String fansMedalFace = completeFace(fansMedalUid, source).orElse(null);
                fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalFace, fansMedalName, fansMedalLevel, fansMedalLighted);
            } else {
                fansMedal = new FansMedal(fansMedalUid, null, null, fansMedalName, fansMedalLevel, fansMedalLighted);
            }
        }

        Guard guard = (fansMedalInfo != null && fansMedalInfo.getInteger("guard_level") != 0)
                ? new Guard(fansMedalInfo.getInteger("guard_level"), fansMedalInfo.getString("guard_icon"))
                : null;

        Integer honorLevel = metaData.getInteger("wealth_level");

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, fansMedal, guard, honorLevel);

        Instant timestamp = Instant.ofEpochSecond(metaData.getLong("timestamp"));

        Long giftId = metaData.getLong("giftId");
        String giftName = metaData.getString("giftName");
        double giftPrice = MathUtil.divide(metaData.getInteger("discount_price"), 1000.0);
        Integer giftCount = metaData.getInteger("num");
        String giftUrl = metaData.getJSONObject("gift_info").getString("img_basic");
        GiftInfo gift = new GiftInfo(giftId, giftName, giftPrice, giftCount, giftUrl);

        String coinType = metaData.getString("coin_type");
        JSONObject randomGiftInfo = metaData.getJSONObject("blind_gift");
        GiftInfo randomGift = null;
        if ("gold".equals(coinType) && randomGiftInfo != null) {
            Long randomGiftId = randomGiftInfo.getLong("original_gift_id");
            String randomGiftName = randomGiftInfo.getString("original_gift_name");
            double randomGiftPrice = MathUtil.divide(randomGiftInfo.getInteger("original_gift_price"), 1000.0);
            String randomGiftUrl = null;
            if (completeEvent) {
                randomGiftUrl = giftService.getGiftInfo(randomGiftId).map(Gift::getUrl).orElse(null);
            }
            randomGift = new GiftInfo(randomGiftId, randomGiftName, randomGiftPrice, giftCount, randomGiftUrl);
        }

        if ("silver".equals(coinType)) {
            return List.of(new BilibiliFreeGiftEvent(source, sender, gift, timestamp));
        }
        if ("gold".equals(coinType)) {
            return List.of(randomGift == null
                    ? new BilibiliPaidGiftEvent(source, sender, gift, timestamp)
                    : new BilibiliRandomGiftEvent(source, sender, randomGift, gift, timestamp));
        }

        log.warn("未处理的直播间礼物消息, 内容: {}", data.toJSONString());
        return List.of();
    }

    /**
     * 解析原始新版直播间礼物数据（SEND_GIFT_V2）
     * @param data 原始新版直播间礼物数据
     * @param source 主播信息
     * @return 事件列表
     */
    private List<StarBotBaseLiveEvent> parseGiftDataV2(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONObject metaData = data.getJSONObject("data");
        String encodedPayload = metaData.getString("pb");

        SendGiftV2 payload;
        try {
            payload = SendGiftV2.parseFrom(Base64.getDecoder().decode(encodedPayload));
        } catch (IOException | IllegalArgumentException e) {
            log.error("解析直播间 {} 的 SEND_GIFT_V2 消息异常, 内容: {}", source.getRoomId(), data.toJSONString(), e);
            return List.of();
        }

        SendGiftV2.Medal medal = payload.getSenderInfo().getMedal();

        FansMedal fansMedal = null;
        if (medal.isPresent()) {
            if (completeEvent) {
                String fansMedalUname = completeUname(medal.getRuid(), source).orElse(null);
                Long fansMedalRoomId = completeRoomId(medal.getRuid(), source).orElse(null);
                String fansMedalFace = completeFace(medal.getRuid(), source).orElse(null);
                fansMedal = new FansMedal(medal.getRuid(), fansMedalUname, fansMedalRoomId, fansMedalFace, medal.getName(), medal.getLevel(), medal.isLighted());
            } else {
                fansMedal = new FansMedal(medal.getRuid(), null, null, medal.getName(), medal.getLevel(), medal.isLighted());
            }
        }

        Guard guard = payload.getGuardLevel() == 0 ? null : new Guard(payload.getGuardLevel(), medal.getGuardIcon());
        Integer honorLevel = payload.getWealthInfo().getLevel();
        BilibiliUserInfo sender = new BilibiliUserInfo(payload.getUid(), payload.getUname(), payload.getFace(), fansMedal, guard, honorLevel);

        SendGiftV2.BlindGift blindGift = payload.getBlindGift();
        List<StarBotBaseLiveEvent> events = new ArrayList<>(payload.getGifts().size());
        for (SendGiftV2.GiftItem item : payload.getGifts()) {
            Instant timestamp = Instant.ofEpochSecond(item.getTimestamp());
            GiftInfo gift = new GiftInfo(item.getId(), item.getName(), MathUtil.divide(item.getDiscountPrice(), 1000.0), item.getCount(), item.getImageUrl());

            GiftInfo randomGift = null;
            if (blindGift.isPresent()) {
                String randomGiftUrl = null;
                if (completeEvent) {
                    randomGiftUrl = giftService.getGiftInfo(blindGift.getId()).map(Gift::getUrl).orElse(null);
                }
                randomGift = new GiftInfo(blindGift.getId(), blindGift.getName(), MathUtil.divide(blindGift.getPrice(), 1000.0), item.getCount(), randomGiftUrl);
            }

            if ("silver".equals(item.getCoinType())) {
                events.add(new BilibiliFreeGiftEvent(source, sender, gift, timestamp));
            } else if ("gold".equals(item.getCoinType())) {
                events.add(randomGift == null
                        ? new BilibiliPaidGiftEvent(source, sender, gift, timestamp)
                        : new BilibiliRandomGiftEvent(source, sender, randomGift, gift, timestamp));
            } else {
                log.warn("未处理的直播间礼物消息, 内容: {}", data.toJSONString());
            }
        }
        return events;
    }

    /**
     * 解析原始直播间醒目留言数据（SUPER_CHAT_MESSAGE）
     * @param data 原始直播间醒目留言数据
     * @param source 主播信息
     * @return 事件
     */
    private List<StarBotBaseLiveEvent> parseSuperChatData(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONObject metaData = data.getJSONObject("data");

        JSONObject senderInfo = metaData.getJSONObject("uinfo");
        JSONObject senderBaseInfo = senderInfo.getJSONObject("base");
        Long senderUid = senderInfo.getLong("uid");
        String senderUname = senderBaseInfo.getString("name");
        String senderFace = senderBaseInfo.getString("face");

        FansMedal fansMedal = null;
        JSONObject fansMedalInfo = senderInfo.getJSONObject("medal");
        if (fansMedalInfo != null) {
            Long fansMedalUid = fansMedalInfo.getLong("ruid");
            String fansMedalName = fansMedalInfo.getString("name");
            Integer fansMedalLevel = fansMedalInfo.getInteger("level");
            Boolean fansMedalLighted = fansMedalInfo.getInteger("is_light") == 1;
            if (completeEvent) {
                String fansMedalUname = completeUname(fansMedalUid, source).orElse(null);
                Long fansMedalRoomId = completeRoomId(fansMedalUid, source).orElse(null);
                String fansMedalFace = completeFace(fansMedalUid, source).orElse(null);
                fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalFace, fansMedalName, fansMedalLevel, fansMedalLighted);
            } else {
                fansMedal = new FansMedal(fansMedalUid, null, null, fansMedalName, fansMedalLevel, fansMedalLighted);
            }
        }

        Guard guard = (fansMedalInfo != null && fansMedalInfo.getInteger("guard_level") != 0)
                ? new Guard(fansMedalInfo.getInteger("guard_level"), fansMedalInfo.getString("guard_icon"))
                : null;

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, fansMedal, guard, null);

        String content = metaData.getString("message");

        Double value = metaData.getDouble("price");

        Instant timestamp = Instant.ofEpochMilli(data.getLong("send_time"));

        return List.of(new BilibiliSuperChatEvent(source, sender, content, value, timestamp));
    }

    /**
     * 解析原始直播间大航海数据（USER_TOAST_MSG）
     * @param data 原始直播间大航海数据
     * @param source 主播信息
     * @return 事件
     */
    private List<StarBotBaseLiveEvent> parseGuardData(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONObject metaData = data.getJSONObject("data");

        Long senderUid = metaData.getLong("uid");
        String senderUname = metaData.getString("username");
        String senderFace = null;
        if (completeEvent) {
            senderFace = completeFace(senderUid, source).orElse(null);
        }

        Integer guardLevel = metaData.getInteger("guard_level");
        String guardIcon = null;
        if (completeEvent) {
            guardIcon = giftService.getGuardIcon(metaData.getString("role_name")).orElse(null);
        }
        Guard guard = new Guard(guardLevel, guardIcon);

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, null, guard, null);

        GuardOperateType operateType = GuardOperateType.of(metaData.getInteger("op_type"));

        double price = MathUtil.divide(metaData.getInteger("price"), 1000.0);

        Integer count = metaData.getInteger("num");

        String unit = metaData.getString("unit");

        Instant timestamp = Instant.ofEpochSecond(metaData.getLong("start_time"));

        switch (guardLevel) {
            case 1 -> {
                return List.of(new BilibiliGovernorEvent(source, sender, operateType, price, count, unit, timestamp));
            }
            case 2 -> {
                return List.of(new BilibiliCommanderEvent(source, sender, operateType, price, count, unit, timestamp));
            }
            case 3 -> {
                return List.of(new BilibiliCaptainEvent(source, sender, operateType, price, count, unit, timestamp));
            }
            default -> {
                log.warn("未处理的直播间大航海消息类型: {}, 内容: {}", guardLevel, data.toJSONString());
                return List.of();
            }
        }
    }

    /**
     * 解析原始直播间点赞数据（LIKE_INFO_V3_CLICK）
     * @param data 原始直播间点赞数据
     * @param source 主播信息
     * @return 事件
     */
    private List<StarBotBaseLiveEvent> parseLikeData(JSONObject data, LiveStreamerInfo source) {
        boolean completeEvent = properties.getLive().isCompleteEvent();

        JSONObject metaData = data.getJSONObject("data");

        JSONObject senderInfo = metaData.getJSONObject("uinfo");
        JSONObject senderBaseInfo = senderInfo.getJSONObject("base");
        Long senderUid = senderInfo.getLong("uid");
        String senderUname = senderBaseInfo.getString("name");
        String senderFace = senderBaseInfo.getString("face");

        FansMedal fansMedal = null;
        JSONObject fansMedalInfo = senderInfo.getJSONObject("medal");
        if (fansMedalInfo != null) {
            Long fansMedalUid = fansMedalInfo.getLong("ruid");
            String fansMedalName = fansMedalInfo.getString("name");
            Integer fansMedalLevel = fansMedalInfo.getInteger("level");
            Boolean fansMedalLighted = fansMedalInfo.getInteger("is_light") == 1;
            if (completeEvent) {
                String fansMedalUname = completeUname(fansMedalUid, source).orElse(null);
                Long fansMedalRoomId = completeRoomId(fansMedalUid, source).orElse(null);
                String fansMedalFace = completeFace(fansMedalUid, source).orElse(null);
                fansMedal = new FansMedal(fansMedalUid, fansMedalUname, fansMedalRoomId, fansMedalFace, fansMedalName, fansMedalLevel, fansMedalLighted);
            } else {
                fansMedal = new FansMedal(fansMedalUid, null, null, fansMedalName, fansMedalLevel, fansMedalLighted);
            }
        }

        Guard guard = (fansMedalInfo != null && fansMedalInfo.getInteger("guard_level") != 0)
                ? new Guard(fansMedalInfo.getInteger("guard_level"), fansMedalInfo.getString("guard_icon"))
                : null;

        BilibiliUserInfo sender = new BilibiliUserInfo(senderUid, senderUname, senderFace, fansMedal, guard, null);

        return List.of(new BilibiliLikeEvent(source, sender));
    }

    /**
     * 解析原始直播间点赞数更新数据（LIKE_INFO_V3_UPDATE）
     * @param data 原始直播间点赞数更新数据
     * @param source 主播信息
     * @return 事件
     */
    private List<StarBotBaseLiveEvent> parseLikeUpdateData(JSONObject data, LiveStreamerInfo source) {
        Integer count = data.getJSONObject("data").getInteger("click_count");

        return List.of(new BilibiliLikeUpdateEvent(source, count));
    }

    /**
     * 自动补全昵称
     * @param uid 用户 UID
     * @param source 主播信息
     * @return 昵称
     */
    private Optional<String> completeUname(@NonNull Long uid, LiveStreamerInfo source) {
        if (uid.equals(source.getUid())) {
            return Optional.ofNullable(source.getUname());
        }

        return bilibili.getUnameByUid(uid);
    }

    /**
     * 自动补全房间号
     * @param uid 用户 UID
     * @param source 主播信息
     * @return 房间号
     */
    private Optional<Long> completeRoomId(@NonNull Long uid, LiveStreamerInfo source) {
        if (uid.equals(source.getUid())) {
            return Optional.ofNullable(source.getRoomId());
        }

        return bilibili.getRoomIdByUid(uid);
    }

    /**
     * 自动补全头像
     * @param uid 用户 UID
     * @param source 主播信息
     * @return 头像
     */
    private Optional<String> completeFace(@NonNull Long uid, LiveStreamerInfo source) {
        if (uid.equals(source.getUid())) {
            return Optional.ofNullable(source.getFace());
        }

        return bilibili.getFaceByUid(uid);
    }
}
