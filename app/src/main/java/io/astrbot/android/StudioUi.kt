// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal fun StudioGlyph(
    kind: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
) {
    Canvas(modifier.size(22.dp)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(
            width = (if (selected) 2.0f else 1.75f) * unit,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (kind) {
            "运行" -> {
                val path = Path().apply {
                    moveTo(3f * unit, 9.5f * unit)
                    cubicTo(4f * unit, 17f * unit, 14f * unit, 19.5f * unit, 18.8f * unit, 12.2f * unit)
                    lineTo(21.5f * unit, 6.5f * unit)
                    lineTo(17f * unit, 8.2f * unit)
                    cubicTo(8.5f * unit, 4.4f * unit, 4f * unit, 6.2f * unit, 3f * unit, 9.5f * unit)
                    close()
                }
                if (selected) drawPath(path, color.copy(alpha = 0.16f), style = Fill)
                drawPath(path, color, style = stroke)
                drawCircle(color, 1.1f * unit, point(7.2f, 9.4f))
            }
            "聊天" -> {
                val bubble = Path().apply {
                    moveTo(6f * unit, 4f * unit)
                    lineTo(18f * unit, 4f * unit)
                    cubicTo(20.2f * unit, 4f * unit, 21f * unit, 5.2f * unit, 21f * unit, 7.5f * unit)
                    lineTo(21f * unit, 14.5f * unit)
                    cubicTo(21f * unit, 16.8f * unit, 20.2f * unit, 18f * unit, 18f * unit, 18f * unit)
                    lineTo(10.5f * unit, 18f * unit)
                    lineTo(6f * unit, 21f * unit)
                    lineTo(6.6f * unit, 18f * unit)
                    lineTo(6f * unit, 18f * unit)
                    cubicTo(3.8f * unit, 18f * unit, 3f * unit, 16.8f * unit, 3f * unit, 14.5f * unit)
                    lineTo(3f * unit, 7.5f * unit)
                    cubicTo(3f * unit, 5.2f * unit, 3.8f * unit, 4f * unit, 6f * unit, 4f * unit)
                    close()
                }
                if (selected) drawPath(bubble, color.copy(alpha = 0.16f), style = Fill)
                drawPath(bubble, color, style = stroke)
                for (x in listOf(8.5f, 12f, 15.5f)) drawCircle(color, 1.05f * unit, point(x, 11f))
            }
            "工具" -> {
                for (x in listOf(3.5f, 13.5f)) {
                    for (y in listOf(3.5f, 13.5f)) {
                        if (selected) {
                            drawRoundRect(
                                color.copy(alpha = 0.18f),
                                point(x, y),
                                Size(7f * unit, 7f * unit),
                                CornerRadius(2.4f * unit),
                            )
                        }
                        drawRoundRect(
                            color,
                            point(x, y),
                            Size(7f * unit, 7f * unit),
                            CornerRadius(2.4f * unit),
                            style = stroke,
                        )
                    }
                }
            }
            "设置" -> {
                if (selected) drawCircle(color.copy(alpha = 0.16f), 6.8f * unit, point(12f, 12f))
                drawCircle(color, 6.8f * unit, point(12f, 12f), style = stroke)
                drawCircle(color, 2.5f * unit, point(12f, 12f), style = stroke)
                for ((x, y) in listOf(
                    0f to 1f, 1f to 0f, 0f to -1f, -1f to 0f,
                    0.707f to 0.707f, -0.707f to 0.707f, 0.707f to -0.707f, -0.707f to -0.707f,
                )) {
                    drawLine(
                        color,
                        point(12f + 7.2f * x, 12f + 7.2f * y),
                        point(12f + 9.8f * x, 12f + 9.8f * y),
                        stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> {
                drawCircle(color, 8f * unit, point(12f, 12f), style = stroke)
                drawLine(color, point(8f, 12f), point(16f, 12f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, point(12f, 8f), point(12f, 16f), stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable internal fun StudioSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable internal fun StudioInsetDivider(insetStart: Dp = 0.dp) {
    val dark = LocalBigFishDark.current
    HorizontalDivider(
        modifier = Modifier.padding(start = insetStart),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dark) 0.7f else 0.85f),
    )
}

@Composable internal fun StudioCard(
    title: String? = null,
    subtitle: String? = null,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalBigFishDark.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (accent) colors.primaryContainer else colors.surface,
        border = BorderStroke(
            width = 0.75.dp,
            color = if (accent) colors.primary.copy(alpha = 0.22f)
            else colors.outlineVariant.copy(alpha = if (dark) 0.75f else 0.90f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null || subtitle != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (accent) colors.onPrimaryContainer else colors.onSurface,
                        )
                    }
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (accent) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable internal fun StudioTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
    @DrawableRes iconRes: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalBigFishDark.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (iconRes != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        colors.primaryContainer.copy(alpha = if (dark) 0.58f else 0.82f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.primary,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge != null) {
            Surface(shape = CircleShape, color = colors.primaryContainer) {
                Text(
                    badge,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.onPrimaryContainer,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.outline,
        )
    }
}

@Composable internal fun StudioField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    multiline: Boolean = false,
    numeric: Boolean = false,
    enabled: Boolean = true,
    hint: String? = null,
) {
    var reveal by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MaterialTheme.colorScheme
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) reveal = false }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        minLines = if (multiline) 4 else 1,
        maxLines = if (multiline) 12 else 1,
        singleLine = !multiline,
        supportingText = if (hint == null) null else ({ Text(hint) }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surfaceContainer,
            disabledContainerColor = colors.surfaceContainer.copy(alpha = 0.6f),
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
        ),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (!secret) null else ({
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") }
        }),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
    )
}

@Composable internal fun StudioSwitch(title: String, text: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, change, enabled = enabled)
    }
}

@Composable internal fun StudioStatus(label: String, text: String, okay: Boolean? = null) {
    val dotColor = if (okay == true) Color(0xFF10B981) else MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (okay != null) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(dotColor.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(6.dp).background(dotColor, CircleShape))
            }
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun StudioMascot(modifier: Modifier = Modifier) = Image(
    painterResource(R.drawable.whale_girl),
    "社区二创鲸鱼娘",
    modifier.clip(RoundedCornerShape(30.dp)),
)

@Composable internal fun StudioEmpty(title: String, message: String) = StudioCard {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal enum class StudioBadgeTone { OK, BUSY, WARN, NEUTRAL }

@Composable internal fun StudioBadge(text: String, tone: StudioBadgeTone) {
    val container = when (tone) {
        StudioBadgeTone.OK -> MaterialTheme.colorScheme.primaryContainer
        StudioBadgeTone.BUSY -> MaterialTheme.colorScheme.secondaryContainer
        StudioBadgeTone.WARN -> MaterialTheme.colorScheme.errorContainer
        StudioBadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tone) {
        StudioBadgeTone.OK -> MaterialTheme.colorScheme.onPrimaryContainer
        StudioBadgeTone.BUSY -> MaterialTheme.colorScheme.onSecondaryContainer
        StudioBadgeTone.WARN -> MaterialTheme.colorScheme.onErrorContainer
        StudioBadgeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}
