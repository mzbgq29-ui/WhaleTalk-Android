// SPDX-License-Identifier: MIT
package io.astrbot.android

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable internal fun BigFishTheme(content: @Composable (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("bigfish-ui", 0) }
    var selection by remember { mutableStateOf(preferences.getString("theme", "system")) }
    var motion by remember { mutableStateOf(preferences.getBoolean("ocean_motion", false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme") selection = preferences.getString("theme", "system")
            if (key == "ocean_motion") motion = preferences.getBoolean("ocean_motion", false)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true; selection = preferences.getString("theme", "system")
                motion = preferences.getBoolean("ocean_motion", false)
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) resumed = false
        }
        preferences.registerOnSharedPreferenceChangeListener(listener); lifecycle.addObserver(observer)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }
    val dark = when (selection) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = if (dark) 0xFF0C1422.toInt() else 0xFFF3F6FC.toInt()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFA2B8FF), secondary = Color(0xFF70DDD0),
        background = Color(0xFF0C1422), surface = Color(0xFF152135), surfaceVariant = Color(0xFF1D2B40),
        surfaceContainerLow = Color(0xFF152135), surfaceContainer = Color(0xFF1D2B40), surfaceContainerHigh = Color(0xFF24324B))
    else lightColorScheme(primary = Color(0xFF425CB6), secondary = Color(0xFF087E78), background = Color(0xFFF3F6FC),
        surface = Color(0xFFFCFDFF), surfaceVariant = Color(0xFFE9EFF9),
        surfaceContainerLow = Color(0xFFF6F8FF), surfaceContainer = Color(0xFFF3F6FC), surfaceContainerHigh = Color(0xFFE7EDFA))
    val backdrop = rememberLayerBackdrop()
    MaterialTheme(colorScheme = colors, shapes = Shapes(extraSmall = RoundedCornerShape(16.dp), small = RoundedCornerShape(20.dp),
        medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp))) {
        CompositionLocalProvider(LocalBigFishBackdrop provides backdrop, LocalBigFishGlassDark provides dark,
            LocalContentColor provides colors.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.25f
                CompositionLocalProvider(LocalBigFishCompact provides compact) {
                    OceanBackdrop(Modifier.fillMaxSize().layerBackdrop(backdrop), dark, animated = motion && resumed)
                    content(dark)
                }
            }
        }
    }
}
