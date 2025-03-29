package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

/**
 * UP 主
 */
@Getter
@Setter
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Up up = (Up) o;
        return Objects.equals(uid, up.uid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid);
    }
}
