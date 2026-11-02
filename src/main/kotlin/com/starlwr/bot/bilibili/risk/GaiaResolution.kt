package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot

/**
 * Gaia 挑战解算结果。
 *
 * @property vtoken 验证码通过后取得的验证令牌(gaia_vtoken), 供 StarBot 对原请求重签重试
 * @property harvestedEnvelope 浏览器环境自行通过后直接带回的完整 API 响应
 *         ({code:0, data:{token, host_list}}), StarBot 无需再发自身请求
 */
data class GaiaResolution @JvmOverloads constructor(
    val vtoken: String? = null,
    val harvestedEnvelope: JSONObject? = null,
    val browserIdentity: RequestIdentitySnapshot? = null,
    val officialWsAuthenticated: Boolean = false,
) {
    val resolved: Boolean
        get() = !vtoken.isNullOrBlank() || harvestedEnvelope != null
}
