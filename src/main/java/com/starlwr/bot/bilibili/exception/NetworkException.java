package com.starlwr.bot.bilibili.exception;

import lombok.Getter;

/**
 * 网络异常
 */
@Getter
public class NetworkException extends RuntimeException {
    private final int code;

    public NetworkException(Integer code) {
        super();
        this.code = code;
    }
}
