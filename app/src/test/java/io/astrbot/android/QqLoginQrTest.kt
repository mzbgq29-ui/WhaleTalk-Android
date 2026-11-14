package io.astrbot.android

import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO

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

    @Test fun decodesTheStyledQqLoginCrop() {
        val (width, height, pixels) = loadPng("qq-login-qr-crop.png")
        val payload = QqLoginQr.decodeArgb(width, height, pixels)
        assertNotNull("ZXing should decode the cropped QQ login QR", payload)
        assertTrue(payload!!.startsWith("https://txz.qq.com/"))
        assertTrue(QqLoginQr.isLikelyLoginPayload(payload))
        val located = QqLoginQr.locate(width, height, pixels)
        assertNotNull("the crop itself should still be recognized as a QR card", located)
        assertTrue(located!!.width >= width * 3 / 4)
        assertTrue(located.height >= height * 3 / 4)
    }

    @Test fun locatesAndReadsTheQrInA720x960Framebuffer() {
        val (width, height, pixels) = loadPng("qq-login-frame-720x960.png")
        assertEquals(720, width)
        assertEquals(960, height)
        val inspection = QqLoginQr.inspect(width, height, pixels)
        val bounds = inspection.bounds
        assertNotNull("should locate the white QR card in the VNC frame", bounds)
        assertTrue("located region should be a substantial square, was ${bounds!!.width}x${bounds.height}",
            minOf(bounds.width, bounds.height) >= 280)
        val aspect = maxOf(bounds.width, bounds.height).toDouble() / minOf(bounds.width, bounds.height)
        assertTrue("located region should be roughly square, aspect=$aspect", aspect <= 1.35)
        assertTrue(inspection.hasDisplay)
        assertTrue(inspection.displayWidth >= 400)
        if (inspection.payload != null) {
            assertTrue(inspection.payload!!.startsWith("https://txz.qq.com/"))
            assertTrue(QqLoginQr.isLikelyLoginPayload(inspection.payload!!))
        } else {
            // Enlarged raw crop is still a success: another phone can scan it.
            assertTrue(inspection.displayArgb!!.any { pixel -> (pixel and 0x00FFFFFF) < 0x404040 })
            assertTrue(inspection.displayArgb!!.any { pixel -> (pixel and 0x00FFFFFF) > 0xC0C0C0 })
        }
    }

    @Test fun contentHashChangesWhenTheCropChanges() {
        val (width, height, pixels) = loadPng("qq-login-qr-crop.png")
        val first = QqLoginQr.inspect(width, height, pixels).contentHash
        val inverted = IntArray(pixels.size) { pixels[it] xor 0x00FFFFFF }
        val second = QqLoginQr.inspect(width, height, inverted).contentHash
        assertNotEquals(first, second)
    }

    private fun loadPng(name: String): Triple<Int, Int, IntArray> {
        val url = requireNotNull(javaClass.getResource("/io/astrbot/android/$name")) { "missing $name" }
        val image = ImageIO.read(url)
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        return Triple(width, height, pixels)
    }
}
