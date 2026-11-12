package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test

class BigFishOptionsTest {
    @Test fun balancedDefaultHasBoundedWorkAndModesIncreaseTogether() {
        val modes = BigFishResourceMode.entries
        assertEquals(BigFishResourceMode.BALANCED, BigFishOptions().mode)
        assertTrue(modes.all { it.turns in 1..24 && it.toolSteps in 1..12 && it.repliesPerMinute in 1..20 })
        modes.zipWithNext().forEach { (a, b) ->
            assertTrue(a.turns < b.turns && a.replyTokens < b.replyTokens && a.toolSteps < b.toolSteps)
        }
    }
    @Test fun wakeWordCannotInjectCommandsOrControlCharacters() {
        assertEquals("小鲸鱼", BigFishOptions(wakeWord = "小鲸鱼").wakeWord)
        listOf("", "/reset", "hi\nthere", "a b", "a".repeat(17), "'\";").forEach { word ->
            assertThrows(IllegalArgumentException::class.java) { BigFishOptions(wakeWord = word) }
        }
    }
    @Test fun presetEndpointsAreDistinctHttpsBasesWithoutSecrets() {
        val presets = BigFishModelPresets.all
        assertEquals(presets.size, presets.map { it.endpoint }.toSet().size)
        presets.forEach { assertEquals(it.endpoint, BigFishInput.normalizeEndpoint(it.endpoint)) }
    }
    @Test fun modernTokenLimitDoesNotMisclassifyOtherModels() {
        listOf("o3", "o4-mini", "gpt-5.1", "openai/gpt-5").forEach { assertTrue(BigFishModelPresets.completionTokenLimit(it)) }
        listOf("gpt-4.1-mini", "deepseek-chat", "qwen-plus", "foo-gpt-5").forEach { assertFalse(BigFishModelPresets.completionTokenLimit(it)) }
    }
}
