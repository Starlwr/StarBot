package com.starlwr.bot.bilibili.risk

/** Absence in one DOM sample cannot establish that the native SDK is idle. */
internal class GaiaNativeInvocationGate(private val graceMillis: Long, private val quietMillis: Long) {
    data class State(val documentReady: Boolean, val panelPresent: Boolean, val panelVisible: Boolean,
                     val bootstrapPending: Boolean, val nativePending: Boolean, val coverageComplete: Boolean = true)
    private var firstRiskAt: Long? = null
    private var quietSince: Long? = null

    fun observedRisk(now: Long) { if (firstRiskAt == null) firstRiskAt = now }
    fun reset() { firstRiskAt = null; quietSince = null }

    fun mayInvoke(now: Long, state: State, loading: Boolean, lastActivityAt: Long, officialStarted: Boolean = false): Boolean {
        val risk = firstRiskAt ?: return false
        if (!state.coverageComplete || officialStarted || !state.documentReady || state.panelPresent || state.panelVisible || state.bootstrapPending || state.nativePending || loading) {
            quietSince = null; return false
        }
        val quiet = maxOf(quietSince ?: now, lastActivityAt)
        quietSince = quiet
        return now - risk >= graceMillis.coerceAtLeast(1_000) && now - quiet >= quietMillis.coerceAtLeast(0)
    }
}
