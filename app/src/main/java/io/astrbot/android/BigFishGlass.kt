// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.sin

internal val LocalBigFishBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalBigFishGlassDark = staticCompositionLocalOf { false }
internal val LocalBigFishCompact = staticCompositionLocalOf { false }

/** Animated source layer. Glass panels sample this layer rather than blurring their own text. */
@Composable internal fun OceanBackdrop(modifier: Modifier, dark: Boolean, animated: Boolean = false) {
    val drift = if (animated) {
        val transition = rememberInfiniteTransition(label = "ocean")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "tide")
        value
    } else .5f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(if (dark) Color(0xFF071326) else Color(0xFFEAF1FF))
        val x = sin(drift * 3.14159f) * w * .16f
        fun glow(color: Color, center: Offset, radius: Float) {
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
        }
        glow(if (dark) Color(0xFF3846B4) else Color(0xFFBBAEFF), Offset(w * .10f + x, h * .14f), w * 1.08f)
        glow(if (dark) Color(0xFF077D9B) else Color(0xFF84E4EB), Offset(w * .93f - x * .6f, h * .57f), w * .94f)
        glow(if (dark) Color(0xFF64419B) else Color(0xFFF5B9E2), Offset(w * .10f + x * .4f, h * .93f), w * .94f)
        val line = if (dark) Color(0xFFB8E8FF).copy(alpha = .09f) else Color.White.copy(alpha = .44f)
        for (index in 0..3) {
            val top = h * .20f + index * h * .19f + x * .2f
            val path = Path().apply {
                moveTo(-w * .2f, top)
                cubicTo(w * .16f, top - w * .34f, w * .70f, top + w * .32f, w * 1.2f, top - w * .12f)
            }
            drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = if (dark) .12f else .65f), Color.Transparent), Offset(w * .83f, h * .12f), w * .16f), w * .16f, Offset(w * .83f, h * .12f))
    }
}

@Composable internal fun Modifier.glassMaterial(shape: Shape = RoundedCornerShape(28.dp), accent: Boolean = false): Modifier {
    val backdrop = LocalBigFishBackdrop.current
    val dark = LocalBigFishGlassDark.current
    // A sampling layer may be empty during its first layout, after IME resize, or in
    // a separate window. Always cover the previous page before drawing the sample.
    val base = if (dark) Color(0xFF14213A) else Color(0xFFEDF2FC)
    val tint = if (accent) { if (dark) Color(0xFF17315F).copy(alpha = .63f) else MaterialTheme.colorScheme.primary.copy(alpha = .16f) }
        else if (dark) Color(0xFF152641).copy(alpha = .58f) else Color.White.copy(alpha = .49f)
    if (backdrop == null) return this.background(base, shape).background(tint, shape).clip(shape)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(10.dp.toPx())
            val edge = minOf(12.dp.toPx(), size.minDimension * .18f)
            lens(edge, minOf(16.dp.toPx(), size.minDimension * .22f))
        },
        highlight = { Highlight(width = .9f.dp, alpha = if (dark) .65f else .9f) },
        shadow = { Shadow(radius = 16.dp, color = Color(0xFF11234C).copy(alpha = if (dark) .26f else .11f)) },
        onDrawBehind = { drawRect(base) },
        onDrawSurface = {
            drawRect(tint)
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .09f else .24f), Color.Transparent, Color.White.copy(alpha = .035f))))
        }
    ).clip(shape)
}

@Composable internal fun LiquidPanel(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp),
    accent: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glassMaterial(shape, accent), content = content)
}

@Composable internal fun LiquidButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    outlined: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dark = LocalBigFishGlassDark.current
    val foreground = if (outlined) MaterialTheme.colorScheme.onSurface else if (dark) Color(0xFFE7EEFF) else Color(0xFF203B8A)
    // Controls sit on the panel's foreground. A second refracting layer here can
    // sample with transformed coordinates and makes labels appear duplicated.
    val fill = if (outlined) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer
    Button(onClick, modifier.heightIn(min = 48.dp).border(.8.dp,
        MaterialTheme.colorScheme.outline.copy(alpha = .24f), RoundedCornerShape(50)),
        enabled = enabled, interactionSource = interaction, elevation = null,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = foreground,
            disabledContainerColor = fill, disabledContentColor = foreground.copy(alpha = .38f)),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp), content = content)
}

@Composable internal fun LiquidOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) = LiquidButton(onClick, modifier, enabled, outlined = true, content = content)

@Composable internal fun LiquidChoice(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(selected, onClick, label, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(50), border = null,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, labelColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = if (LocalBigFishGlassDark.current) Color(0xFFE7EEFF) else Color(0xFF203B8A)))
}
