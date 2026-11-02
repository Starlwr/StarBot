package com.starlwr.bot.bilibili.risk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GaiaNativeInvocationGateTest {
    private val idle = GaiaNativeInvocationGate.State(true,false,false,false,false)
    @Test fun `official lifecycle prohibits fallback even after all promises fulfilled and DOM misses UI`() {
        val gate = GaiaNativeInvocationGate(1_000, 0)
        gate.observedRisk(0)
        assertFalse(gate.mayInvoke(60_000, idle, false, 1_000, officialStarted = true))
        assertFalse(gate.mayInvoke(90_000, idle.copy(coverageComplete = false), false, 1_000))
    }
    @Test fun `configured zero grace still gives official page at least one second`() {
        val gate = GaiaNativeInvocationGate(0,0)
        gate.observedRisk(0)
        assertFalse(gate.mayInvoke(999,idle,false,0))
        assertTrue(gate.mayInvoke(1000,idle,false,0))
    }

    @Test fun `four seconds without a panel does not establish native inactivity`() {
        val gate = GaiaNativeInvocationGate(10_000,2_000)
        gate.observedRisk(0)
        assertFalse(gate.mayInvoke(0,idle,false,0))
        assertFalse(gate.mayInvoke(4_600,idle,false,0))
        assertTrue(gate.mayInvoke(10_000,idle,false,0))
    }
    @Test fun `pending SDK and late resource activity extend observation beyond grace`() {
        val gate = GaiaNativeInvocationGate(10_000,2_000)
        gate.observedRisk(0)
        assertFalse(gate.mayInvoke(20_000,idle.copy(bootstrapPending=true),false,0))
        assertFalse(gate.mayInvoke(21_000,idle,false,20_500))
        assertFalse(gate.mayInvoke(22_999,idle,false,20_500))
        assertTrue(gate.mayInvoke(23_000,idle,false,20_500))
        assertFalse(gate.mayInvoke(24_000,idle,true,24_000))
        assertFalse(gate.mayInvoke(25_000,idle,false,24_900))
        assertTrue(gate.mayInvoke(27_000,idle,false,24_900))
    }
    @Test fun `hidden native panel and pending native function both prohibit intervention`() {
        val gate = GaiaNativeInvocationGate(10_000,2_000)
        gate.observedRisk(0)
        assertFalse(gate.mayInvoke(60_000,idle.copy(panelPresent=true),false,0))
        assertFalse(gate.mayInvoke(61_000,idle.copy(nativePending=true),false,0))
        assertFalse(gate.mayInvoke(62_000,idle,false,0))
        assertTrue(gate.mayInvoke(64_000,idle,false,0))
    }
    @Test fun `elapsed time alone never triggers without observed web 352 and loaded document`() {
        val gate = GaiaNativeInvocationGate(10_000,2_000)
        assertFalse(gate.mayInvoke(999_999,idle,false,0))
        gate.observedRisk(0)
        assertFalse(gate.mayInvoke(999_999,idle.copy(documentReady=false),false,0))
        gate.reset()
        assertFalse(gate.mayInvoke(999_999,idle,false,0))
    }
}
