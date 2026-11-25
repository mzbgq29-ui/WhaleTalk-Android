// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LocalBigFishCompact = staticCompositionLocalOf { false }

@Composable internal fun LiquidPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    accent: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalBigFishDark.current
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (accent) colors.primaryContainer else colors.surface,
        border = BorderStroke(
            width = 0.75.dp,
            color = if (accent) colors.primary.copy(alpha = 0.22f)
            else colors.outlineVariant.copy(alpha = if (dark) 0.72f else 0.90f),
        ),
    ) {
        Box(content = content)
    }
}

@Composable internal fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        content = content,
    )
}

@Composable internal fun LiquidOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface,
            contentColor = colors.primary,
        ),
        content = content,
    )
}

@Composable internal fun LiquidChoice(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = CircleShape,
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.outlineVariant,
            selectedBorderColor = colors.primary.copy(alpha = 0.28f),
            borderWidth = 0.9.dp,
            selectedBorderWidth = 1.dp,
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primaryContainer,
            selectedLabelColor = colors.onPrimaryContainer,
        ),
    )
}

/**
 * Floating iOS 26-inspired Liquid Glass navigation dock with spring-driven sliding lens
 * and horizontal touch-scrub gesture support.
 */
@Composable internal fun <T> LiquidGlassBottomBar(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: @Composable (T, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val dark = LocalBigFishDark.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    val currentItems by rememberUpdatedState(items)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val springIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "liquidGlassLensIndex",
    )
    val activeIndex = if (dragging) dragIndex else springIndex
    val velocityDeform = (abs(selectedIndex.toFloat() - springIndex) * 0.14f).coerceAtMost(0.08f)
    val lensScaleX by animateFloatAsState(
        targetValue = if (dragging) 1.07f else 1f + velocityDeform,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "liquidGlassScaleX",
    )
    val lensScaleY by animateFloatAsState(
        targetValue = if (dragging) 1.04f else 1f + velocityDeform * 0.4f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "liquidGlassScaleY",
    )

    val dockGradient = if (dark) {
        Brush.verticalGradient(
            listOf(Color(0xFF232B3B).copy(alpha = 0.88f), Color(0xFF131822).copy(alpha = 0.82f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.93f), Color(0xFFEEF3FA).copy(alpha = 0.84f)),
        )
    }
    val dockRim = if (dark) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.98f), Color(0xFFC8D5E8).copy(alpha = 0.68f)),
        )
    }
    val lensFill = if (dark) {
        Brush.verticalGradient(
            listOf(colors.primary.copy(alpha = 0.28f), Color.White.copy(alpha = 0.07f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.96f), colors.primaryContainer.copy(alpha = 0.82f)),
        )
    }
    val lensRim = if (dark) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.34f), colors.primary.copy(alpha = 0.20f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White, colors.primary.copy(alpha = 0.16f)),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = if (dark) 18.dp else 14.dp,
                    shape = CircleShape,
                    ambientColor = if (dark) Color.Black else colors.primary.copy(alpha = 0.18f),
                    spotColor = if (dark) Color.Black else Color(0xFF0F172A).copy(alpha = 0.22f),
                )
                .clip(CircleShape)
                .background(dockGradient, CircleShape)
                .border(1.dp, dockRim, CircleShape),
        ) {
            val horizontalPad = 5.dp
            val slotWidth = (maxWidth - horizontalPad * 2) / items.size
            val slotPx = with(density) { slotWidth.toPx() }.coerceAtLeast(1f)
            val padPx = with(density) { horizontalPad.toPx() }

            // Interactive horizontal drag across the entire glass dock
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(items.size) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                val idx = ((offset.x - padPx) / slotPx - 0.5f)
                                    .coerceIn(0f, (currentItems.size - 1).toFloat())
                                dragIndex = idx
                                lastHapticIndex = idx.roundToInt().coerceIn(0, currentItems.lastIndex)
                            },
                            onDragEnd = {
                                val targetIdx = dragIndex.roundToInt().coerceIn(0, currentItems.lastIndex)
                                dragging = false
                                val targetItem = currentItems[targetIdx]
                                if (targetItem != currentSelected) {
                                    currentOnSelect(targetItem)
                                }
                            },
                            onDragCancel = {
                                dragging = false
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val idx = ((change.position.x - padPx) / slotPx - 0.5f)
                                    .coerceIn(0f, (currentItems.size - 1).toFloat())
                                dragIndex = idx
                                val hovered = idx.roundToInt().coerceIn(0, currentItems.lastIndex)
                                if (hovered != lastHapticIndex) {
                                    lastHapticIndex = hovered
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val targetItem = currentItems[hovered]
                                    if (targetItem != currentSelected) {
                                        currentOnSelect(targetItem)
                                    }
                                }
                            },
                        )
                    },
            ) {
                // Sliding specular Liquid Glass lens
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (padPx + activeIndex * slotPx).roundToInt(),
                                y = 0,
                            )
                        }
                        .width(slotWidth)
                        .fillMaxHeight()
                        .padding(vertical = 5.dp, horizontal = 2.dp)
                        .graphicsLayer {
                            scaleX = lensScaleX
                            scaleY = lensScaleY
                        }
                        .shadow(
                            elevation = if (dark) 4.dp else 6.dp,
                            shape = CircleShape,
                            ambientColor = if (dark) Color.Black.copy(alpha = 0.5f) else colors.primary.copy(alpha = 0.16f),
                            spotColor = if (dark) Color.Black.copy(alpha = 0.5f) else colors.primary.copy(alpha = 0.14f),
                        )
                        .clip(CircleShape)
                        .background(lensFill, CircleShape)
                        .border(0.9.dp, lensRim, CircleShape),
                ) {
                    // Top internal specular highlight
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(0.76f)
                            .padding(top = 2.dp)
                            .height(10.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = if (dark) 0.16f else 0.72f),
                                        Color.Transparent,
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                    )
                }

                // Foreground tab items
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, item ->
                        val isSelected = item == selected
                        val hoverFocus = if (dragging) abs(dragIndex - index) < 0.45f else isSelected
                        val itemTint by animateColorAsState(
                            targetValue = if (hoverFocus) colors.primary else colors.onSurfaceVariant,
                            animationSpec = tween(durationMillis = 180),
                            label = "dockItemTint",
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (hoverFocus) 1.08f else 1f,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                            label = "dockItemScale",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    this.selected = isSelected
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!isSelected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelect(item)
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CompositionLocalProvider(LocalContentColor provides itemTint) {
                                Box(Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) {
                                    icon(item, hoverFocus)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = label(item),
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (hoverFocus) FontWeight.SemiBold else FontWeight.Medium,
                                color = itemTint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
