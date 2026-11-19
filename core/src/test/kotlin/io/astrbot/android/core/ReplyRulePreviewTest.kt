package io.astrbot.android.core

import io.astrbot.android.core.ReplyRulePreview.Result.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyRulePreviewTest {
    private fun evaluate(id: String, group: Boolean, message: String = "", mentions: Boolean = false,
                         options: BigFishOptions = BigFishOptions()) =
        ReplyRulePreview.evaluate("100001", "100002", "200001", options, id, group, mentions, message)

    @Test fun anUnknownGroupCannotWakeTheBot() {
        assertEquals(NOT_ALLOWED, evaluate("200005", group = true, mentions = true))
        assertEquals(NOT_AWAKENED, evaluate("200001", group = true, message = "你好"))
        assertEquals(ALLOWED, evaluate("200001", group = true, message = "  鲸语你好"))
    }

    @Test fun mentionsAndCommandsWorkWhenTextWakeIsDisabled() {
        val options = BigFishOptions(groupMentionOnly = true)
        assertEquals(NOT_AWAKENED, evaluate("200001", group = true, message = "鲸语你好", options = options))
        assertEquals(ALLOWED, evaluate("200001", group = true, mentions = true, options = options))
        assertEquals(ALLOWED, evaluate("200001", group = true, message = "/帮助", options = options))
    }

    @Test fun privateChatsStillRespectTheWakeSetting() {
        assertEquals(ALLOWED, evaluate("100001", group = false))
        assertEquals(ALLOWED, evaluate("100002", group = false))
        assertEquals(NOT_ALLOWED, evaluate("100003", group = false))
        assertEquals(NOT_AWAKENED, evaluate("100001", group = false, options = BigFishOptions(friendNeedsWake = true)))
    }
}
