// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal fun StudioGlyph(kind: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(1.8f * unit)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (kind) {
            "鲸鱼" -> {
                val path = Path().apply { moveTo(3*unit,9*unit); cubicTo(4*unit,17*unit,14*unit,20*unit,19*unit,12*unit)
                    lineTo(22*unit,6*unit); lineTo(17*unit,8*unit); cubicTo(8*unit,4*unit,4*unit,6*unit,3*unit,9*unit); close() }
                drawPath(path, color, style = stroke); drawCircle(color, 1*unit, point(7f,9f))
            }
            "聊天" -> { drawRoundRect(color, point(3f,3f), Size(18*unit,15*unit), CornerRadius(5*unit), style=stroke)
                drawLine(color, point(6f,18f), point(5f,22f), 1.8f*unit); drawLine(color,point(5f,22f),point(11f,18f),1.8f*unit)
                for (x in listOf(8f,12f,16f)) drawCircle(color,unit,point(x,10f)) }
            "工具" -> for (x in listOf(3f,14f)) for (y in listOf(3f,14f))
                drawRoundRect(color, point(x,y), Size(7*unit,7*unit), CornerRadius(2*unit), style=stroke)
            "我的" -> { drawCircle(color,4*unit,point(12f,7f),style=stroke)
                drawArc(color,180f,180f,false,point(4f,14f),Size(16*unit,13*unit),style=stroke) }
            else -> { drawCircle(color,8*unit,point(12f,12f),style=stroke)
                drawLine(color,point(8f,12f),point(16f,12f),1.8f*unit); drawLine(color,point(12f,8f),point(12f,16f),1.8f*unit) }
        }
    }
}

@Composable internal fun StudioCard(title: String? = null, subtitle: String? = null, accent: Boolean = false,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LiquidPanel(modifier.fillMaxWidth().animateContentSize(), accent = accent) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable internal fun StudioTile(title: String, subtitle: String, onClick: () -> Unit, badge: String? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).heightIn(min = 76.dp).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(title.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun StudioField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false,
    multiline: Boolean = false, numeric: Boolean = false, enabled: Boolean = true, hint: String? = null) {
    var reveal by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) reveal = false }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        minLines = if (multiline) 4 else 1, maxLines = if (multiline) 12 else 1, singleLine = !multiline,
        supportingText = if (hint == null) null else ({ Text(hint) }), shape = RoundedCornerShape(18.dp),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (!secret) null else ({ TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") } }),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
}

@Composable internal fun StudioSwitch(title: String, text: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, change, enabled = enabled)
    }
}

@Composable internal fun StudioStatus(label: String, text: String, okay: Boolean? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (okay != null) Box(Modifier.size(7.dp).background(if (okay) Color(0xFF20A784) else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun StudioMascot(modifier: Modifier = Modifier) = Image(painterResource(R.drawable.whale_girl),
    "社区二创鲸鱼娘", modifier.clip(RoundedCornerShape(30.dp)))

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
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = content)
    }
}
