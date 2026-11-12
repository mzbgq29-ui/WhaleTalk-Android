package io.astrbot.android.core

/** Pixel geometry shared by painting and touch hit testing. */
data class RfbViewport(val width: Int, val height: Int, val viewWidth: Int, val viewHeight: Int,
                       val fit: Boolean, val panX: Float? = null, val panY: Float? = null) {
    init { require(minOf(width, height, viewWidth, viewHeight) > 0) }
    private val fittedScale = minOf(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
    val scale = if (fit) fittedScale else maxOf(1f, fittedScale * 2f)
    val sourceX = if (fit) 0f else (panX ?: ((width - viewWidth / scale).coerceAtLeast(0f) / 2)).coerceIn(0f, (width - viewWidth / scale).coerceAtLeast(0f))
    val sourceY = if (fit) 0f else (panY ?: ((height - viewHeight / scale).coerceAtLeast(0f) / 2)).coerceIn(0f, (height - viewHeight / scale).coerceAtLeast(0f))
    val offsetX = ((viewWidth - width * scale) / 2).coerceAtLeast(0f)
    val offsetY = ((viewHeight - height * scale) / 2).coerceAtLeast(0f)
    fun remote(x: Float, y: Float, clamp: Boolean = false): Pair<Int, Int>? {
        val px = (x - offsetX) / scale + sourceX
        val py = (y - offsetY) / scale + sourceY
        if (!clamp && (px < 0 || py < 0 || px >= width || py >= height)) return null
        return px.toInt().coerceIn(0, width - 1) to py.toInt().coerceIn(0, height - 1)
    }
}
