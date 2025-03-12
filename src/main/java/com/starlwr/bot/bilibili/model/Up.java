package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UP 主
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Up {
    /**
     * UID
     */
    private Long uid;

    /**
     * 昵称
     */
    private String uname;

    /**
     * 房间号
     */
    private Long roomId;

    /**
     * 头像
     */
    private String face;
}
