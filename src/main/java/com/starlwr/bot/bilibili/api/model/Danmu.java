package com.starlwr.bot.bilibili.api.model;

import com.starlwr.bot.core.event.model.UserInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 弹幕
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Danmu {
    /**
     * 弹幕类型
     */
    private DanmuType type;

    /**
     * 观众信息
     */
    private BilibiliUserInfo sender;

    /**
     * 回复的用户信息
     */
    private UserInfo reply;

    /**
     * 内容
     */
    private String content;

    /**
     * 去除了表情等内容后的纯文本内容
     */
    private String contentText;

    /**
     * 弹幕中包含的表情信息
     */
    private List<BilibiliEmojiInfo> emojis;

    /**
     * 发送时间戳
     */
    private Instant timestamp;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Danmu danmu)) return false;
        return type == danmu.type && Objects.equals(sender.getUid(), danmu.sender.getUid()) && Objects.equals(content, danmu.content) && Objects.equals(timestamp, danmu.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sender.getUid(), content, timestamp);
    }
}
