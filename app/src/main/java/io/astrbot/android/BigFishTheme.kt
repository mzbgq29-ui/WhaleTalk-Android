// SPDX-License-Identifier: MIT
package io.astrbot.android

import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalBigFishDark = staticCompositionLocalOf { false }

@Composable internal fun BigFishTheme(content: @Composable (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("bigfish-ui", 0) }
    var selection by remember { mutableStateOf(preferences.getString("theme", "system")) }
    var dynamicColor by remember { mutableStateOf(preferences.getBoolean("dynamic_color", false)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme") selection = preferences.getString("theme", "system")
            if (key == "dynamic_color") dynamicColor = preferences.getBoolean("dynamic_color", false)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = when (selection) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val whaleDark = darkColorScheme(
        primary = Color(0xFF5EA8FF), onPrimary = Color(0xFF002A5C),
        primaryContainer = Color(0xFF152E52), onPrimaryContainer = Color(0xFFC7E1FF),
        secondary = Color(0xFFA6B5CC), onSecondary = Color(0xFF192434),
        secondaryContainer = Color(0xFF232C3D), onSecondaryContainer = Color(0xFFD6E2F5),
        tertiary = Color(0xFF4CD3A9), onTertiary = Color(0xFF003828),
        background = Color(0xFF0D1017), onBackground = Color(0xFFF1F5FA),
        surface = Color(0xFF171B24), onSurface = Color(0xFFF1F5FA),
        surfaceContainerLowest = Color(0xFF12161E), surfaceContainerLow = Color(0xFF171B24),
        surfaceContainer = Color(0xFF1D222E), surfaceContainerHigh = Color(0xFF242B38),
        surfaceContainerHighest = Color(0xFF2C3444),
        surfaceVariant = Color(0xFF232A38), onSurfaceVariant = Color(0xFF9AA6BA),
        outline = Color(0xFF667388), outlineVariant = Color(0xFF283040),
        error = Color(0xFFFF6B6B), onError = Color(0xFF4A0A0A),
        errorContainer = Color(0xFF3D181B), onErrorContainer = Color(0xFFFFD6D6),
    )
    val whaleLight = lightColorScheme(
        primary = Color(0xFF0066D6), onPrimary = Color.White,
        primaryContainer = Color(0xFFE5F0FF), onPrimaryContainer = Color(0xFF003371),
        secondary = Color(0xFF4E5D73), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8EEF6), onSecondaryContainer = Color(0xFF1E2B3D),
        tertiary = Color(0xFF1D8A6E), onTertiary = Color.White,
        background = Color(0xFFF2F4F8), onBackground = Color(0xFF111827),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111827),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF6F8FB), surfaceContainerHigh = Color(0xFFEDF1F7),
        surfaceContainerHighest = Color(0xFFE4EAF3),
        surfaceVariant = Color(0xFFEAEEF5), onSurfaceVariant = Color(0xFF5B6678),
        outline = Color(0xFF8C97A8), outlineVariant = Color(0xFFE2E7F0),
        error = Color(0xFFD93025), onError = Color.White,
        errorContainer = Color(0xFFFDECEC), onErrorContainer = Color(0xFF7A1410),
    )
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val seed = if (dark) whaleDark else whaleLight
        dynamic.copy(
            primary = seed.primary, onPrimary = seed.onPrimary, primaryContainer = seed.primaryContainer,
            onPrimaryContainer = seed.onPrimaryContainer, secondary = seed.secondary, onSecondary = seed.onSecondary,
            secondaryContainer = seed.secondaryContainer, onSecondaryContainer = seed.onSecondaryContainer,
            tertiary = seed.tertiary, onTertiary = seed.onTertiary,
        )
    } else if (dark) whaleDark else whaleLight
    val typography = MaterialTheme.typography.run {
        copy(
            headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
            bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalBigFishDark provides dark,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
                val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.25f
                CompositionLocalProvider(LocalBigFishCompact provides compact) {
                    content(dark)
                }
            }
        }
    }
}
