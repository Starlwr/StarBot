package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
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
public class Up extends LiveStreamerInfo {
    public Up(Long uid, String uname, Long roomId) {
        super(uid, uname, roomId);
    }

    public Up(Long uid, String uname, Long roomId, String face) {
        super(uid, uname, roomId, face);
    }

    public Up(PushUser user) {
        super(user.getUid(), user.getUname(), user.getRoomId(), user.getFace());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Up up = (Up) o;
        return Objects.equals(getUid(), up.getUid());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUid());
    }
}
