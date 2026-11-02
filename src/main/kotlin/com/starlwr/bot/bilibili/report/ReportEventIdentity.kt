package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import java.util.UUID
import java.util.WeakHashMap

/** Upstream identifiers survive retransmission; otherwise distinguish each received event.
 * Weak keys preserve retry identity without keeping event objects alive after delivery. */
object ReportEventIdentity {
    private val identities = WeakHashMap<StarBotExternalBaseEvent, String>()
    @JvmStatic @Synchronized fun id(event: StarBotExternalBaseEvent): String =
        identities.getOrPut(event) { "received:${UUID.randomUUID()}" }

    @JvmStatic @Synchronized fun <T : StarBotExternalBaseEvent> bind(event: T, identity: String?): T {
        if (!identity.isNullOrBlank()) identities[event] = identity
        return event
    }

    @JvmStatic fun <T : StarBotExternalBaseEvent> bindPacket(event: T, packet: JSONObject): T {
        val command = packet.getString("cmd")?.substringBefore(':')
        val data = packet.getJSONObject("data")
        val upstream = when (command) {
            "DANMU_MSG" -> runCatching {
                val extra = packet.getJSONArray("info")?.getJSONArray(0)?.getJSONObject(15)?.getString("extra")
                extra?.let { JSON.parseObject(it).getString("id_str") }
            }.getOrNull()
            "SEND_GIFT" -> data?.getString("tid")
            "SUPER_CHAT_MESSAGE" -> data?.getString("id")
            "USER_TOAST_MSG" -> data?.getString("order_id")
            else -> null
        }?.takeIf { it.isNotBlank() && it != "0" }
        return bind(event, upstream?.let { "$command:$it" })
    }
}
