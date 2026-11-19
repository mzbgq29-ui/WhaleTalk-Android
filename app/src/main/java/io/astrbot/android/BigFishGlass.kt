// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

internal val LocalBigFishCompact = staticCompositionLocalOf { false }

@Composable internal fun LiquidPanel(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp),
    accent: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = shape, color = if (accent) colors.primaryContainer else colors.surface) {
        Box(content = content)
    }
}

@Composable internal fun LiquidButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = MaterialTheme.shapes.small, content = content)
}

@Composable internal fun LiquidOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = MaterialTheme.shapes.small, content = content)
}

@Composable internal fun LiquidChoice(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(selected, onClick, label, modifier.heightIn(min = 48.dp), enabled = enabled)
}
