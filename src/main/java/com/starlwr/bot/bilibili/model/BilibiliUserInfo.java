package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Bilibili 用户信息
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliUserInfo extends UserInfo {
    /**
     * 粉丝勋章信息
     */
    private FansMedal fansMedal;

    /**
     * 大航海信息
     */
    private Guard guard;

    /**
     * 荣耀等级
     */
    private Integer honorLevel;

    public BilibiliUserInfo(Long uid, String uname) {
        super(uid, uname);
    }

    public BilibiliUserInfo(Long uid, String uname, String face) {
        super(uid, uname, face);
    }

    public BilibiliUserInfo(Long uid, String uname, FansMedal fansMedal) {
        super(uid, uname);
        this.fansMedal = fansMedal;
    }

    public BilibiliUserInfo(Long uid, String uname, String face, FansMedal fansMedal) {
        super(uid, uname, face);
        this.fansMedal = fansMedal;
    }

    public BilibiliUserInfo(Long uid, String uname, FansMedal fansMedal, Integer honorLevel) {
        super(uid, uname);
        this.fansMedal = fansMedal;
        this.honorLevel = honorLevel;
    }

    public BilibiliUserInfo(Long uid, String uname, String face, FansMedal fansMedal, Integer honorLevel) {
        super(uid, uname, face);
        this.fansMedal = fansMedal;
        this.honorLevel = honorLevel;
    }

    public BilibiliUserInfo(Long uid, String uname, FansMedal fansMedal, Guard guard) {
        super(uid, uname);
        this.fansMedal = fansMedal;
        this.guard = guard;
    }

    public BilibiliUserInfo(Long uid, String uname, String face, FansMedal fansMedal, Guard guard) {
        super(uid, uname, face);
        this.fansMedal = fansMedal;
        this.guard = guard;
    }

    public BilibiliUserInfo(Long uid, String uname, FansMedal fansMedal, Guard guard, Integer honorLevel) {
        super(uid, uname);
        this.fansMedal = fansMedal;
        this.guard = guard;
        this.honorLevel = honorLevel;
    }

    public BilibiliUserInfo(Long uid, String uname, String face, FansMedal fansMedal, Guard guard, Integer honorLevel) {
        super(uid, uname, face);
        this.fansMedal = fansMedal;
        this.guard = guard;
        this.honorLevel = honorLevel;
    }
}
