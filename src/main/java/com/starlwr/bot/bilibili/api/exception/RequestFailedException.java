package com.starlwr.bot.bilibili.api.exception;

/**
 * 请求失败
 */
public class RequestFailedException extends RuntimeException {
    public RequestFailedException(String message) {
        super(message);
    }

    public RequestFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
