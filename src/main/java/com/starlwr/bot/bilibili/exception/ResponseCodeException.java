package com.starlwr.bot.bilibili.exception;

import lombok.Getter;

/**
 * 接口返回错误码
 */
@Getter
public class ResponseCodeException extends RuntimeException {
    private final int code;

    private final String message;

    public ResponseCodeException(Integer code, String message) {
        super();
        this.code = code;
        this.message = message;
    }

    @Override
    public String getMessage() {
        return "接口返回错误代码: " + code + ", 信息: " + message;
    }
}
