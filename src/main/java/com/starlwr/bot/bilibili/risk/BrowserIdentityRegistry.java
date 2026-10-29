package com.starlwr.bot.bilibili.risk;

import com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行级浏览器身份登记表（内存 only, 不持久化）。
 *
 * 记录最近一次经浏览器通道验证成功的身份快照（uid 已校验、挑战已绑定）。
 * 语义（用户约定）:
 * - -352 熔断激活期间: 新建/重连优先使用该身份（浏览器环境当前可通过）;
 * - 非 -352 期间: 新建/重连优先 JVM 自身身份;
 * - 曾以浏览器身份连接成功的会话, 其重连继续沿用浏览器身份（由连接器 sticky 标记表达）;
 * - 浏览器身份也 -352 时清除登记, 回到 JVM 优先并继续熔断逻辑。
 */
@StarBotComponent
public class BrowserIdentityRegistry {
    private final AtomicReference<RequestIdentitySnapshot> lastVerified = new AtomicReference<>();

    public void recordVerified(RequestIdentitySnapshot identity) {
        if (identity != null && identity.isBrowser()) lastVerified.set(identity);
    }

    public RequestIdentitySnapshot lastVerified() {
        return lastVerified.get();
    }

    /** 浏览器身份也被 -352 拒绝时清除, 回到 JVM 优先。 */
    public void clear() {
        lastVerified.set(null);
    }
}
