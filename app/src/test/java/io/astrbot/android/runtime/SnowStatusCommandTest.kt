package io.astrbot.android.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnowStatusCommandTest {
    @Test fun repairsOldDisabledDefaultWithoutModifyingInput() {
        val old = JSONObject().put("enabled", false).put("swallow", false)
            .put("trigger", "#sl").put("cooldownSeconds", 5)
        val next = SnowStatusCommand.configuration(old, true)
        assertTrue(next.getBoolean("enabled"))
        assertTrue(next.getBoolean("swallow"))
        assertEquals("#sl", next.getString("trigger"))
        assertFalse(old.getBoolean("enabled"))
        assertFalse(old.getBoolean("swallow"))
    }
    @Test fun nativeDisableAndCustomTriggerSurvivePreparation() {
        val custom = JSONObject().put("enabled", true).put("trigger", "!status").put("cooldownSeconds", 12)
        val next = SnowStatusCommand.configuration(custom, false)
        assertFalse(next.getBoolean("enabled"))
        assertEquals("!status", next.getString("trigger"))
        assertEquals(12, next.getInt("cooldownSeconds"))
        val enabled = SnowStatusCommand.configuration(next, true)
        assertEquals("!status", enabled.getString("trigger"))
        assertTrue(enabled.getBoolean("swallow"))
    }
    @Test fun newProfilesEnableLocalStatusAndHaveBoundedCooldown() {
        val next = SnowStatusCommand.configuration(null, true)
        assertTrue(next.getBoolean("enabled"))
        assertTrue(next.getBoolean("swallow"))
        assertEquals("#sl", next.getString("trigger"))
        assertEquals(5, next.getInt("cooldownSeconds"))
    }
    @Test fun accountOverlayKeepsGlobalTriggerAndOwnCooldown() {
        val global = JSONObject().put("trigger", "!health").put("cooldownSeconds", 20)
        val overlay = JSONObject().put("enabled", false).put("cooldownSeconds", 10)
        val next = SnowStatusCommand.configuration(overlay, true, global)
        assertEquals("!health", next.getString("trigger"))
        assertEquals(10, next.getInt("cooldownSeconds"))
        assertTrue(next.getBoolean("enabled"))
        assertEquals(20, global.getInt("cooldownSeconds"))
    }
}
