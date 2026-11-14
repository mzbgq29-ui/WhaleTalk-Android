// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** How long a detected login QR stays "fresh", and how long it may vanish from the frame. */
enum class QrFreshness { NONE, FRESH, STALE, LOST }

internal data class QrBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left + 1).coerceAtLeast(0)
    val height: Int get() = (bottom - top + 1).coerceAtLeast(0)
    fun padded(margin: Int, maxWidth: Int, maxHeight: Int) = QrBounds(
        (left - margin).coerceAtLeast(0),
        (top - margin).coerceAtLeast(0),
        (right + margin).coerceAtMost(maxWidth - 1),
        (bottom + margin).coerceAtMost(maxHeight - 1),
    )
}

internal data class QrInspection(
    val payload: String? = null,
    val bounds: QrBounds? = null,
    val contentHash: Long = 0L,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val displayArgb: IntArray? = null,
) {
    val located: Boolean get() = bounds != null || !payload.isNullOrEmpty()
    val hasDisplay: Boolean get() = displayArgb != null && displayWidth > 0 && displayHeight > 0
}

/**
 * SnowLuma 2.4.2 has no stable QR or quick-login HTTP API.
 * Login codes are recovered from the existing VNC/RFB bitmap when possible.
 * If ZXing cannot decode QQ's styled QR, the white card is still cropped and enlarged
 * so another phone can scan the raw pixels.
 */
internal object QqLoginQr {
    const val STALE_AFTER_MS = 120_000L
    const val LOST_AFTER_MS = 3_000L
    const val SEARCHING_FOR_MS = 4_000L
    private const val DISPLAY_EDGE = 560

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

    fun inspect(bitmap: Bitmap): QrInspection {
        if (bitmap.width <= 0 || bitmap.height <= 0) return QrInspection()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return inspect(bitmap.width, bitmap.height, pixels)
    }

    fun inspect(width: Int, height: Int, pixels: IntArray): QrInspection {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return QrInspection()
        val bounds = locate(width, height, pixels)
        val payload = decodeBest(width, height, pixels, bounds)?.takeIf(::isLikelyLoginPayload)
        val displaySource = bounds ?: payload?.let { QrBounds(0, 0, width - 1, height - 1) }
        val hash = if (displaySource != null) hashRegion(width, height, pixels, displaySource) else hashFrame(width, height, pixels)
        val display = when {
            !payload.isNullOrEmpty() -> {
                val edge = DISPLAY_EDGE
                Triple(edge, edge, renderArgb(payload, edge))
            }
            displaySource != null -> enlarge(width, height, pixels, displaySource.padded(8, width, height), DISPLAY_EDGE)
            else -> null
        }
        return QrInspection(
            payload = payload,
            bounds = bounds,
            contentHash = hash,
            displayWidth = display?.first ?: 0,
            displayHeight = display?.second ?: 0,
            displayArgb = display?.third,
        )
    }

    fun decode(bitmap: Bitmap): String? = inspect(bitmap).payload

    fun encode(text: String, size: Int = 512): Bitmap {
        val edge = size.coerceIn(128, 1024)
        return Bitmap.createBitmap(renderArgb(text, edge), edge, edge, Bitmap.Config.ARGB_8888)
    }

    fun displayBitmap(inspection: QrInspection): Bitmap? {
        val pixels = inspection.displayArgb ?: return null
        if (inspection.displayWidth <= 0 || inspection.displayHeight <= 0) return null
        return Bitmap.createBitmap(pixels, inspection.displayWidth, inspection.displayHeight, Bitmap.Config.ARGB_8888)
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
        listOf<LuminanceSource>(source, source.invert()).forEach { luminance ->
            listOf(HybridBinarizer(luminance), GlobalHistogramBinarizer(luminance)).forEach { binarizer ->
                reader.reset()
                val text = runCatching { reader.decode(BinaryBitmap(binarizer), hints).text?.trim() }.getOrNull()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    fun locate(width: Int, height: Int, pixels: IntArray): QrBounds? {
        if (width < 32 || height < 32 || pixels.size < width * height) return null
        val cell = (minOf(width, height) / 90).coerceIn(4, 8)
        val columns = (width + cell - 1) / cell
        val rows = (height + cell - 1) / cell
        val luminance = IntArray(width * height)
        var litCount = 0
        var litSum = 0L
        for (i in 0 until width * height) {
            val value = luminanceOf(pixels[i])
            luminance[i] = value
            if (value >= 40) { litCount++; litSum += value }
        }
        if (litCount < 64) return null
        val median = percentile(luminance, width, height, 50)
        val whiteThr = if (median >= 248) (median - 8).coerceAtLeast(230) else maxOf(248, median + 8)
        val mask = BooleanArray(columns * rows)
        for (cy in 0 until rows) {
            val y0 = cy * cell
            val y1 = minOf(height, y0 + cell)
            for (cx in 0 until columns) {
                val x0 = cx * cell
                val x1 = minOf(width, x0 + cell)
                var white = 0
                val total = (y1 - y0) * (x1 - x0)
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) if (luminance[row + x] >= whiteThr) white++
                }
                mask[cy * columns + cx] = white * 100 >= total * 15
            }
        }
        var best: QrBounds? = null
        var bestScore = 0.0
        val seen = BooleanArray(columns * rows)
        val stackX = IntArray(columns * rows)
        val stackY = IntArray(columns * rows)
        for (startY in 0 until rows) {
            for (startX in 0 until columns) {
                val start = startY * columns + startX
                if (!mask[start] || seen[start]) continue
                var minX = startX
                var maxX = startX
                var minY = startY
                var maxY = startY
                var area = 0
                stackX[0] = startX
                stackY[0] = startY
                seen[start] = true
                var sp = 1
                while (sp > 0) {
                    sp--
                    val cx = stackX[sp]
                    val cy = stackY[sp]
                    area++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    fun visit(nx: Int, ny: Int) {
                        if (nx !in 0 until columns || ny !in 0 until rows) return
                        val index = ny * columns + nx
                        if (!mask[index] || seen[index]) return
                        seen[index] = true
                        stackX[sp] = nx
                        stackY[sp] = ny
                        sp++
                    }
                    visit(cx + 1, cy); visit(cx - 1, cy); visit(cx, cy + 1); visit(cx, cy - 1)
                }
                if (area < 8) continue
                val boxW = maxX - minX + 1
                val boxH = maxY - minY + 1
                val aspect = maxOf(boxW, boxH).toDouble() / minOf(boxW, boxH)
                if (aspect > 1.6) continue
                val left = minX * cell
                val top = minY * cell
                val right = minOf(width - 1, (maxX + 1) * cell - 1)
                val bottom = minOf(height - 1, (maxY + 1) * cell - 1)
                if (minOf(right - left + 1, bottom - top + 1) < minOf(width, height) / 8 &&
                    minOf(right - left + 1, bottom - top + 1) < 96) continue
                val fill = area.toDouble() / (boxW * boxH)
                val variance = regionVariance(luminance, width, left, top, right, bottom)
                if (variance < 200) continue
                val square = when {
                    aspect <= 1.2 -> 1.0
                    aspect <= 1.45 -> 0.6
                    else -> 0.25
                }
                val fillScore = if (fill >= 0.4) 1.0 else 0.5
                val score = area * square * fillScore
                if (score > bestScore) {
                    bestScore = score
                    val pad = maxOf(4, (maxOf(right - left + 1, bottom - top + 1) * 0.03).toInt())
                    best = QrBounds(left, top, right, bottom).padded(pad, width, height)
                }
            }
        }
        return best
    }

    private fun decodeBest(width: Int, height: Int, pixels: IntArray, bounds: QrBounds?): String? {
        if (bounds != null) {
            val crop = bounds.padded(12, width, height)
            val extracted = extract(width, height, pixels, crop)
            decodeArgb(crop.width, crop.height, extracted)?.let { return it }
            val shortEdge = minOf(crop.width, crop.height)
            if (shortEdge in 1 until 420) {
                val scaled = scaleNearest(crop.width, crop.height, extracted, 2)
                decodeArgb(crop.width * 2, crop.height * 2, scaled)?.let { return it }
            }
        }
        val maxEdge = maxOf(width, height)
        val scales = buildList {
            if (maxEdge <= 1280) add(1000)
            if (maxEdge > 700) add(((800 * 1000) / maxEdge).coerceIn(200, 1000))
            add(((560 * 1000) / maxEdge).coerceIn(200, 1000))
        }.distinct()
        for (permille in scales) {
            val sourceWidth: Int
            val sourceHeight: Int
            val source: IntArray
            if (permille == 1000 || (width * permille / 1000) >= width) {
                sourceWidth = width; sourceHeight = height; source = pixels
            } else {
                sourceWidth = (width * permille / 1000).coerceAtLeast(1)
                sourceHeight = (height * permille / 1000).coerceAtLeast(1)
                source = scaleNearestTo(width, height, pixels, sourceWidth, sourceHeight)
            }
            decodeArgb(sourceWidth, sourceHeight, source)?.let { return it }
        }
        return null
    }

    private fun extract(width: Int, height: Int, pixels: IntArray, bounds: QrBounds): IntArray {
        val out = IntArray(bounds.width * bounds.height)
        for (y in 0 until bounds.height) {
            val src = (bounds.top + y) * width + bounds.left
            val dst = y * bounds.width
            pixels.copyInto(out, dst, src, src + bounds.width)
        }
        return out
    }

    private fun enlarge(width: Int, height: Int, pixels: IntArray, bounds: QrBounds, target: Int): Triple<Int, Int, IntArray> {
        val margin = 16
        val inner = target - margin * 2
        val scale = inner.toDouble() / maxOf(bounds.width, bounds.height)
        val dw = (bounds.width * scale).toInt().coerceAtLeast(1)
        val dh = (bounds.height * scale).toInt().coerceAtLeast(1)
        val outW = dw + margin * 2
        val outH = dh + margin * 2
        val out = IntArray(outW * outH) { Color.WHITE }
        val crop = extract(width, height, pixels, bounds)
        for (y in 0 until dh) {
            val sy = y * bounds.height / dh
            val dst = (y + margin) * outW + margin
            for (x in 0 until dw) {
                val sx = x * bounds.width / dw
                out[dst + x] = crop[sy * bounds.width + sx]
            }
        }
        return Triple(outW, outH, out)
    }

    private fun scaleNearest(width: Int, height: Int, pixels: IntArray, factor: Int): IntArray {
        val nw = width * factor
        val nh = height * factor
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = y / factor
            val src = sy * width
            val dst = y * nw
            for (x in 0 until nw) out[dst + x] = pixels[src + x / factor]
        }
        return out
    }

    private fun scaleNearestTo(width: Int, height: Int, pixels: IntArray, newWidth: Int, newHeight: Int): IntArray {
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val sy = y * height / newHeight
            val src = sy * width
            val dst = y * newWidth
            for (x in 0 until newWidth) out[dst + x] = pixels[src + x * width / newWidth]
        }
        return out
    }

    private fun hashRegion(width: Int, height: Int, pixels: IntArray, bounds: QrBounds): Long {
        var hash = 0x9e3779b97f4a7c15uL xor bounds.width.toULong() xor (bounds.height.toULong() shl 16)
        val sample = 32
        for (y in 0 until sample) {
            val sy = bounds.top + y * bounds.height / sample
            for (x in 0 until sample) {
                val sx = bounds.left + x * bounds.width / sample
                val lum = luminanceOf(pixels[sy * width + sx]).toULong()
                hash = hash * 1099511628211uL xor lum
            }
        }
        return hash.toLong()
    }

    private fun hashFrame(width: Int, height: Int, pixels: IntArray): Long =
        hashRegion(width, height, pixels, QrBounds(0, 0, width - 1, height - 1))

    private fun regionVariance(luminance: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int): Double {
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        var y = top
        while (y <= bottom) {
            val row = y * width
            var x = left
            while (x <= right) {
                val value = luminance[row + x].toDouble()
                sum += value
                sumSq += value * value
                count++
                x += 2
            }
            y += 2
        }
        if (count < 8) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    private fun percentile(luminance: IntArray, width: Int, height: Int, percent: Int): Int {
        val step = maxOf(1, (width * height) / 80000)
        val values = ArrayList<Int>(1 + width * height / step)
        var index = 0
        while (index < width * height) {
            val value = luminance[index]
            if (value >= 40) values.add(value)
            index += step
        }
        if (values.isEmpty()) return 0
        values.sort()
        return values[((values.size - 1) * percent / 100).coerceIn(0, values.lastIndex)]
    }

    private fun luminanceOf(pixel: Int): Int {
        val r = (pixel shr 16) and 255
        val g = (pixel shr 8) and 255
        val b = pixel and 255
        return (r * 54 + g * 183 + b * 19) shr 8
    }
}
