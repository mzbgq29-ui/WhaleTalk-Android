package io.astrbot.android

import org.junit.Assert.*
import org.junit.Test

class QqLoginQrTest {
    @Test fun acceptsOfficialLoginUrls() {
        assertTrue(QqLoginQr.isLikelyLoginPayload("https://txz.qq.com/p?k=abcDEF123"))
        assertTrue(QqLoginQr.isLikelyLoginPayload("https://xui.ptlogin2.qq.com/cgi-bin/qr"))
        assertTrue(QqLoginQr.isLikelyLoginPayload("tencent://im/login?k=1"))
        assertTrue(QqLoginQr.isLikelyLoginPayload("A".repeat(24) + "LoginToken"))
    }

    @Test fun rejectsUnrelatedPayloads() {
        assertFalse(QqLoginQr.isLikelyLoginPayload("https://example.com/login"))
        assertFalse(QqLoginQr.isLikelyLoginPayload("short"))
        assertFalse(QqLoginQr.isLikelyLoginPayload("abc def ghi jklmnopqrstuv"))
    }

    @Test fun tracksFreshnessWindows() {
        assertEquals(QrFreshness.NONE, QqLoginQr.freshness(1000, 0, 0))
        assertEquals(QrFreshness.FRESH, QqLoginQr.freshness(10_000, 1_000, 9_500))
        assertEquals(QrFreshness.STALE, QqLoginQr.freshness(200_000, 1_000, 199_000))
        assertEquals(QrFreshness.LOST, QqLoginQr.freshness(20_000, 1_000, 10_000))
    }

    @Test fun roundTripsAGeneratedQrCode() {
        val payload = "https://txz.qq.com/p?k=WhaleTalkLoginTest"
        val size = 256
        val pixels = QqLoginQr.renderArgb(payload, size)
        assertEquals(payload, QqLoginQr.decodeArgb(size, size, pixels))
    }
}
