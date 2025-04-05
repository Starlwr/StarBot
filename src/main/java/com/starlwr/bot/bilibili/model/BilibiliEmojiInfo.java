package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.common.model.EmojiInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Bilibili 表情信息
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEmojiInfo extends EmojiInfo {
    /**
     * 宽度
     */
    private Integer width;

    /**
     * 高度
     */
    private Integer height;

    /**
     * 数量
     */
    private Integer count;

    public BilibiliEmojiInfo(String id, String name, String url) {
        super(id, name, url);
        this.count = 1;
    }

    public BilibiliEmojiInfo(String id, String name, String url, Integer width, Integer height) {
        super(id, name, url);
        this.width = width;
        this.height = height;
        this.count = 1;
    }

    public BilibiliEmojiInfo(String id, String name, String url, Integer width, Integer height, Integer count) {
        super(id, name, url);
        this.width = width;
        this.height = height;
        this.count = count;
    }
}
