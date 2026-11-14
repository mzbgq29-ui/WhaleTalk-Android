// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** How long a detected login QR stays "fresh", and how long it may vanish from the frame. */
enum class QrFreshness { NONE, FRESH, STALE, LOST }

/**
 * SnowLuma 2.4.2 has no stable QR or quick-login HTTP API.
 * Login codes are recovered from the existing VNC/RFB bitmap when possible.
 */
internal object QqLoginQr {
    const val STALE_AFTER_MS = 120_000L
    const val LOST_AFTER_MS = 3_000L
    private const val MAX_EDGE = 480

    fun isLikelyLoginPayload(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 8 || trimmed.length > 2048) return false
        if (trimmed.any { it.isISOControl() }) return false
        val lower = trimmed.lowercase()
        if (listOf("qq.com", "tencent.com", "qlogo.cn", "gtimg.cn").any { it in lower }) return true
        if (lower.startsWith("tencent://") || lower.startsWith("wtlogin://")) return true
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false
        return trimmed.length >= 24 && trimmed.none { it.isWhitespace() }
    }

    fun freshness(now: Long, firstSeenAtMs: Long, lastSeenAtMs: Long): QrFreshness {
        if (firstSeenAtMs <= 0 || lastSeenAtMs <= 0) return QrFreshness.NONE
        if (now - lastSeenAtMs > LOST_AFTER_MS) return QrFreshness.LOST
        if (now - firstSeenAtMs > STALE_AFTER_MS) return QrFreshness.STALE
        return QrFreshness.FRESH
    }

    fun decode(bitmap: Bitmap): String? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(width * height)
        if (width == bitmap.width && height == bitmap.height) {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            try {
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        return decodeArgb(width, height, pixels)
    }

    fun encode(text: String, size: Int = 512): Bitmap {
        val edge = size.coerceIn(128, 1024)
        return Bitmap.createBitmap(renderArgb(text, edge), edge, edge, Bitmap.Config.ARGB_8888)
    }

    fun renderArgb(text: String, size: Int): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 2,
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        ))
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return pixels
    }

    fun decodeArgb(width: Int, height: Int, pixels: IntArray): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val reader = QRCodeReader()
        listOf(source, source.invert()).forEach { luminance ->
            listOf(HybridBinarizer(luminance), GlobalHistogramBinarizer(luminance)).forEach { binarizer ->
                reader.reset()
                val text = runCatching { reader.decode(BinaryBitmap(binarizer), hints).text?.trim() }.getOrNull()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }
}
