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
        val bytes = requireNotNull(javaClass.getResourceAsStream("/io/astrbot/android/$name")) { "missing $name" }
            .use { it.readBytes() }
        require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        var offset = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        val idat = java.io.ByteArrayOutputStream()
        while (offset + 12 <= bytes.size) {
            val length = readInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val start = offset + 8
            val chunk = bytes.copyOfRange(start, start + length)
            when (type) {
                "IHDR" -> {
                    width = readInt(chunk, 0)
                    height = readInt(chunk, 4)
                    bitDepth = chunk[8].toInt() and 255
                    colorType = chunk[9].toInt() and 255
                    require(bitDepth == 8 && colorType == 2 && chunk[12].toInt() == 0) { "unsupported PNG $name" }
                }
                "IDAT" -> idat.write(chunk)
            }
            offset = start + length + 4
            if (type == "IEND") break
        }
        val raw = inflate(idat.toByteArray())
        val stride = width * 3
        val pixels = IntArray(width * height)
        val current = ByteArray(stride)
        val previous = ByteArray(stride)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src].toInt() and 255
            src++
            for (i in 0 until stride) {
                val sample = raw[src + i].toInt() and 255
                val left = if (i >= 3) current[i - 3].toInt() and 255 else 0
                val up = previous[i].toInt() and 255
                val upLeft = if (i >= 3) previous[i - 3].toInt() and 255 else 0
                current[i] = (when (filter) {
                    0 -> sample
                    1 -> sample + left
                    2 -> sample + up
                    3 -> sample + (left + up) / 2
                    4 -> sample + paeth(left, up, upLeft)
                    else -> error("PNG filter $filter")
                } and 255).toByte()
            }
            src += stride
            for (x in 0 until width) {
                val r = current[x * 3].toInt() and 255
                val g = current[x * 3 + 1].toInt() and 255
                val b = current[x * 3 + 2].toInt() and 255
                pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            System.arraycopy(current, 0, previous, 0, stride)
        }
        return Triple(width, height, pixels)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255 shl 24) or (bytes[offset + 1].toInt() and 255 shl 16) or
            (bytes[offset + 2].toInt() and 255 shl 8) or (bytes[offset + 3].toInt() and 255)

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            out.write(buffer, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
