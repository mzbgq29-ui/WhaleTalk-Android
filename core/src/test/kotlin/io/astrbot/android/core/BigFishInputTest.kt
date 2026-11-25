package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test

class BigFishInputTest {
    private fun valid() = BigFishInput("100001", "https://api.deepseek.com/v1", "deepseek-chat", "sk-test-value-not-real")
    @Test fun normalizesInputAndDoesNotPrintSecrets() {
        val value = valid()
        assertEquals(listOf("100001"), value.friends)
        assertEquals("https://api.deepseek.com/v1", BigFishInput.normalizeEndpoint(" https://api.deepseek.com/v1/chat/completions/ "))
        assertFalse(value.toString().contains(value.key)); assertFalse(value.toString().contains(value.owner))
    }
    @Test fun rejectsInsecureOrCredentialBearingUrls() {
        for (url in listOf("http://example.org/v1", "https://user:password@example.org", "https://example.org?key=hidden", "https://example.org/#key", "", "https:///oops", "https://example.org:0", "https://example.org:70000")) {
            assertThrows(IllegalArgumentException::class.java) { BigFishInput.normalizeEndpoint(url) }
        }
    }
    @Test fun validatesOwnerAndSecretAtBoundary() {
        for (owner in listOf("", "0", "1000", "012345", "abc", "123456789012345678901")) {
            assertThrows(IllegalArgumentException::class.java) { BigFishInput(owner, valid().endpoint, valid().model, valid().key) }
        }
        for (key in listOf("", "short", "sk-key\r\nInjected: value", "a".repeat(8193))) {
            assertThrows(IllegalArgumentException::class.java) { BigFishInput("100001", valid().endpoint, valid().model, key) }
        }
    }
    @Test fun parsesAllowListsWithoutDroppingInvalidValues() {
        val value = BigFishInput("100001", valid().endpoint, valid().model, valid().key, "100002，100002\n100003", "200001;200002")
        assertEquals(listOf("100002", "100003", "100001"), value.friends)
        assertEquals(listOf("200001", "200002"), value.groups)
        assertThrows(IllegalArgumentException::class.java) { BigFishInput.parseIds("100001,bad") }
        assertThrows(IllegalArgumentException::class.java) { BigFishInput.parseIds((100000..100100).joinToString(",")) }
    }
}
