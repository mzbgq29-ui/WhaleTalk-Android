package io.astrbot.android

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.PrepareProgressReading
import io.astrbot.android.runtime.ManagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable internal fun PrepareProgressBar(state: ManagerState, fallback: String, saving: Boolean = false) {
    val fraction = state.progressFraction?.takeIf { !saving }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(fraction != null) {
        if (fraction == null) return@LaunchedEffect
        while (isActive) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
            val processed = state.progressBytes ?: PrepareProgressReading.bytesFromMessage(state.phase)?.first
            val total = state.progressTotalBytes ?: PrepareProgressReading.bytesFromMessage(state.phase)?.second
            val eta = state.progressStartedAtElapsed?.let { PrepareProgressReading.remainingMillis(it, now, fraction) }
            Text("$percent% · ${state.phase}", style = MaterialTheme.typography.bodySmall)
            val extras = buildList {
                if (processed != null && total != null) add("${PrepareProgressReading.formatBytes(processed)} / ${PrepareProgressReading.formatBytes(total)}")
                if (eta != null) add(PrepareProgressReading.formatEta(eta))
            }
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(fallback, style = MaterialTheme.typography.bodySmall)
        }
    }
}
