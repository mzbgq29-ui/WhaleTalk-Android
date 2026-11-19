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
    val whaleDark = darkColorScheme(primary = Color(0xFF81D1EA), onPrimary = Color(0xFF003544),
        primaryContainer = Color(0xFF204553), onPrimaryContainer = Color(0xFFB5EBFA),
        secondary = Color(0xFFAFCBD4), onSecondary = Color(0xFF1B343C),
        secondaryContainer = Color(0xFF30464D), onSecondaryContainer = Color(0xFFCCEBF3),
        background = Color(0xFF15191B), surface = Color(0xFF15191B), onSurface = Color(0xFFE3E7E9),
        surfaceContainerLow = Color(0xFF1C2225), surfaceContainer = Color(0xFF20272A), surfaceContainerHigh = Color(0xFF283033),
        surfaceVariant = Color(0xFF30393D), onSurfaceVariant = Color(0xFFC0CCD1), outline = Color(0xFF85949A), outlineVariant = Color(0xFF3D494D))
    val whaleLight = lightColorScheme(primary = Color(0xFF17617A), onPrimary = Color.White,
        primaryContainer = Color(0xFFD7EFF7), onPrimaryContainer = Color(0xFF133B49),
        secondary = Color(0xFF48636D), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE2EEF2), onSecondaryContainer = Color(0xFF233B44),
        background = Color(0xFFF9FAFA), surface = Color(0xFFF9FAFA), onSurface = Color(0xFF192225),
        surfaceContainerLow = Color(0xFFF2F5F6), surfaceContainer = Color(0xFFECF1F3), surfaceContainerHigh = Color(0xFFE5ECEF),
        surfaceVariant = Color(0xFFE8EFF1), onSurfaceVariant = Color(0xFF48585E), outline = Color(0xFF6E7D83), outlineVariant = Color(0xFFCAD4D8))
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val seed = if (dark) whaleDark else whaleLight
        dynamic.copy(primary = seed.primary, onPrimary = seed.onPrimary, primaryContainer = seed.primaryContainer,
            onPrimaryContainer = seed.onPrimaryContainer, secondary = seed.secondary, onSecondary = seed.onSecondary,
            secondaryContainer = seed.secondaryContainer, onSecondaryContainer = seed.onSecondaryContainer)
    } else if (dark) whaleDark else whaleLight
    val typography = MaterialTheme.typography.run {
        copy(
            headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
    MaterialTheme(colorScheme = colors, typography = typography, shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
                val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.25f
                CompositionLocalProvider(LocalBigFishCompact provides compact) {
                    content(dark)
                }
            }
        }
    }
}
