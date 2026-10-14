package com.starlwr.bot.bilibili.live.gift;

import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.api.model.Gift;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bilibili 礼物服务
 */
@Slf4j
@StarBotComponent
public class BilibiliGiftService {
    private final StarBotBilibiliProperties properties;

    private final BilibiliApiService bilibili;

    private Map<String, String> guards;

    private Map<String, Gift> gifts;

    private Instant lastUpdate = Instant.now();

    @Autowired
    public BilibiliGiftService(StarBotBilibiliProperties properties, BilibiliApiService bilibili) {
        this.properties = properties;
        this.bilibili = bilibili;
    }

    /**
     * 更新大航海信息
     */
    private void updateGuardInfo() {
        guards = bilibili.getGuardInfos();
    }

    /**
     * 更新礼物信息
     */
    private void updateGiftInfo() {
        gifts = bilibili.getGiftInfos().stream().collect(Collectors.toMap(Gift::getId, gift -> gift));
    }

    /**
     * 获取礼物信息
     * @param giftId 礼物 ID
     * @return 礼物信息
     */
    public Optional<Gift> getGiftInfo(@NonNull String giftId) {
        int giftCacheExpire = properties.getLive().getGiftCacheExpire();
        if (gifts == null || Instant.now().isAfter(lastUpdate.plusSeconds(giftCacheExpire))) {
            updateGiftInfo();
            lastUpdate = Instant.now();
        }

        return Optional.ofNullable(gifts.get(giftId));
    }

    /**
     * 获取大航海信息
     * @param type 大航海类型
     * @return 大航海图标
     */
    public Optional<String> getGuardIcon(@NonNull String type) {
        if (guards == null) {
            updateGuardInfo();
        }

        return Optional.ofNullable(guards.get(type));
    }
}
