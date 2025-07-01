package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.bilibili.event.live.*;
import com.starlwr.bot.bilibili.model.*;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.util.MathUtil;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Bilibili 事件解析器
 */
@Slf4j
@Service
public class BilibiliEventParser {
    private static final Logger rawMessageLogger = LoggerFactory.getLogger("RawMessageLogger");

    @Resource
    private StarBotBilibiliProperties properties;

    @Resource
    private BilibiliApiUtil bilibili;

    @Resource
    private BilibiliGiftService giftService;

    private final Map<String, BiFunction<JSONObject, LiveStreamerInfo, StarBotBaseLiveEvent>> parsers = Map.of(
            "LIVE", BilibiliEventParser.this::parseLiveOnData,
            "PREPARING", BilibiliEventParser.this::parseLiveOffData,
            "INTERACT_WORD", BilibiliEventParser.this::parseOperationData,
            "DANMU_MSG", BilibiliEventParser.this::parseMessageData,
            "SEND_GIFT", BilibiliEventParser.this::parseGiftData,
            "SUPER_CHAT_MESSAGE", BilibiliEventParser.this::parseSuperChatData,
            "USER_TOAST_MSG", BilibiliEventParser.this::parseGuardData,
            "LIKE_INFO_V3_CLICK", BilibiliEventParser.this::parseLikeData,
            "LIKE_INFO_V3_UPDATE", BilibiliEventParser.this::parseLikeUpdateData
    );

    /**
     * 将直播间收到的原始消息转换为事件
     * @param data 原始消息
     * @return 解析成功返回事件，否则返回 null
     */
    public Optional<StarBotBaseLiveEvent> parse(JSONObject data, LiveStreamerInfo source) {
        String type = data.getString("cmd");
        if (properties.getDebug().isLiveRoomRawMessageLog()) {
            rawMessageLogger.debug("{}: {} -> {}", type, source.getRoomId(), data.toJSONString());
        }

        if (parsers.containsKey(type)) {
            try {
                return Optional.ofNullable(parsers.get(type).apply(data, source));
            } catch (Exception e) {
                log.error("处理直播间 {} 的 {} 类型消息异常: {}", source.getRoomId(), type, data.toJSONString(), e);
            }
        }

        return Optional.empty();
    }

    /**
     * 解析原始直播间开播数据（LIVE）
     * @param data 原始直播间开播数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseLiveOnData(JSONObject data, LiveStreamerInfo source) {
        Long liveTime = data.getLong("live_time");

        if (liveTime != null) {
            Instant timestamp = Instant.ofEpochSecond(liveTime);
            return new BilibiliLiveOnEvent(source, timestamp);
        }

        return null;
    }

    /**
     * 解析原始直播间下播数据（PREPARING）
     * @param data 原始直播间下播数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseLiveOffData(JSONObject data, LiveStreamerInfo source) {
        Instant timestamp = Instant.ofEpochMilli(data.getLong("send_time"));

        return new BilibiliLiveOffEvent(source, timestamp);
    }

    /**
     * 解析原始直播间操作数据（INTERACT_WORD）
     * @param data 原始直播间操作数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseOperationData(JSONObject data, LiveStreamerInfo source) {
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

        Integer msgType = metaData.getInteger("msg_type");
        switch (msgType) {
            case 1 -> {
                boolean fromPromotion = metaData.getInteger("is_spread") == 1;
                String promotionSource = Optional.of(metaData.getString("spread_desc"))
                        .filter(s -> !s.isBlank())
                        .orElse(null);

                return new BilibiliEnterRoomEvent(source, sender, fromPromotion, promotionSource, timestamp);
            }
            case 2 -> {
                return new BilibiliFollowEvent(source, sender, timestamp);
            }
            case 3 -> {
                return new BilibiliShareEvent(source, sender, timestamp);
            }
            default -> {
                log.warn("未处理的直播间操作消息类型: {}, 内容: {}", msgType, data.toJSONString());
                return null;
            }
        }
    }

    /**
     * 解析原始直播间消息数据（DANMU_MSG）
     * @param data 原始直播间消息数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseMessageData(JSONObject data, LiveStreamerInfo source) {
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

                return new BilibiliDanmuEvent(source, sender, reply, content, contentText, emojis, timestamp);
            }

            return new BilibiliDanmuEvent(source, sender, reply, content, timestamp);
        } else {
            JSONObject emojiInfo = primaryInfo.getJSONObject(13);
            String emojiId = emojiInfo.getString("emoticon_unique");
            String emojiName = danmuInfo.getString("content");
            String emojiUrl = emojiInfo.getString("url");
            Integer emojiWidth = emojiInfo.getInteger("width");
            Integer emojiHeight = emojiInfo.getInteger("height");
            BilibiliEmojiInfo emoji = new BilibiliEmojiInfo(emojiId, emojiName, emojiUrl, emojiWidth, emojiHeight);

            return new BilibiliEmojiEvent(source, sender, emoji, timestamp);
        }
    }

    /**
     * 解析原始直播间礼物数据（SEND_GIFT）
     * @param data 原始直播间礼物数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseGiftData(JSONObject data, LiveStreamerInfo source) {
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
        if ("silver".equals(coinType)) {
            return new BilibiliFreeGiftEvent(source, sender, gift, timestamp);
        } else if ("gold".equals(coinType)) {
            if (randomGiftInfo == null) {
                return new BilibiliPaidGiftEvent(source, sender, gift, timestamp);
            } else {
                Long randomGiftId = randomGiftInfo.getLong("original_gift_id");
                String randomGiftName = randomGiftInfo.getString("original_gift_name");
                double randomGiftPrice = MathUtil.divide(metaData.getInteger("total_coin"), 1000.0);
                String randomGiftUrl = null;
                if (completeEvent) {
                    randomGiftUrl = giftService.getGiftInfo(randomGiftId).map(Gift::getUrl).orElse(null);
                }
                GiftInfo randomGift = new GiftInfo(randomGiftId, randomGiftName, randomGiftPrice, giftCount, randomGiftUrl);

                return new BilibiliRandomGiftEvent(source, sender, randomGift, gift, timestamp);
            }
        }

        log.warn("未处理的直播间礼物消息, 内容: {}", data.toJSONString());
        return null;
    }

    /**
     * 解析原始直播间醒目留言数据（SUPER_CHAT_MESSAGE）
     * @param data 原始直播间醒目留言数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseSuperChatData(JSONObject data, LiveStreamerInfo source) {
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

        return new BilibiliSuperChatEvent(source, sender, content, value, timestamp);
    }

    /**
     * 解析原始直播间大航海数据（USER_TOAST_MSG）
     * @param data 原始直播间大航海数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseGuardData(JSONObject data, LiveStreamerInfo source) {
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

        Instant timestamp = Instant.ofEpochMilli(data.getLong("send_time"));

        switch (guardLevel) {
            case 1 -> {
                return new BilibiliGovernorEvent(source, sender, operateType, price, count, unit, timestamp);
            }
            case 2 -> {
                return new BilibiliCommanderEvent(source, sender, operateType, price, count, unit, timestamp);
            }
            case 3 -> {
                return new BilibiliCaptainEvent(source, sender, operateType, price, count, unit, timestamp);
            }
            default -> {
                log.warn("未处理的直播间大航海消息类型: {}, 内容: {}", guardLevel, data.toJSONString());
                return null;
            }
        }
    }

    /**
     * 解析原始直播间点赞数据（LIKE_INFO_V3_CLICK）
     * @param data 原始直播间点赞数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseLikeData(JSONObject data, LiveStreamerInfo source) {
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

        return new BilibiliLikeEvent(source, sender);
    }

    /**
     * 解析原始直播间点赞数更新数据（LIKE_INFO_V3_UPDATE）
     * @param data 原始直播间点赞数更新数据
     * @param source 主播信息
     * @return 事件
     */
    private StarBotBaseLiveEvent parseLikeUpdateData(JSONObject data, LiveStreamerInfo source) {
        Integer count = data.getJSONObject("data").getInteger("click_count");

        return new BilibiliLikeUpdateEvent(source, count);
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
