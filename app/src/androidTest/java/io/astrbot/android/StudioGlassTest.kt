// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Reproduces the reported defect: dark text behind a floating plane must not show through. */
class StudioGlassTest {
    @get:Rule val rule = createComposeRule()

    private fun compareUnderlay(tag: String, toggle: () -> Unit) {
        rule.mainClock.advanceTimeBy(200)
        val before = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        rule.runOnIdle(toggle)
        rule.mainClock.advanceTimeByFrame()
        val after = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        var changed = 0; var total = 0
        // Ignore rounded corners, edges, shadows and tiny animation quantization changes.
        for (y in (before.height / 4) until (before.height * 3 / 4))
            for (x in (before.width / 5) until (before.width * 4 / 5)) {
                val a = before[x,y]; val b = after[x,y]
                if (abs(a.red-b.red) + abs(a.green-b.green) + abs(a.blue-b.blue) > .04f) changed++
                total++
            }
        assertTrue("Underlying text changed $changed of $total foreground pixels", changed < total * .003f)
    }

    @Test fun floatingGlassDoesNotCopyUnderlyingText() {
        var underlay by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BigFishTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (underlay) Text("MMMMMMMM\nMMMMMMMM\nMMMMMMMM", color = Color.Black, fontSize = 36.sp)
                    LiquidPanel(Modifier.size(300.dp,200.dp).testTag("plane")) {
                        Text("清晰的文字", Modifier.padding(20.dp))
                    }
                }
            }
        }
        compareUnderlay("plane") { underlay = true }
    }

    @Test fun missingSamplingLayerStillCoversUnderlyingText() {
        var underlay by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BigFishTheme {
                CompositionLocalProvider(LocalBigFishBackdrop provides null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (underlay) Text("MMMMMMMM\nMMMMMMMM\nMMMMMMMM", color = Color.Black, fontSize = 36.sp)
                        LiquidPanel(Modifier.size(300.dp,200.dp).testTag("fallback")) { Text("采样未就绪", Modifier.padding(20.dp)) }
                    }
                }
            }
        }
        compareUnderlay("fallback") { underlay = true }
    }

    @Test fun dialogHasItsOwnReadableForeground() {
        var underlay by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BigFishTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (underlay) Text("MMMMMMMM\nMMMMMMMM\nMMMMMMMM", color = Color.Black, fontSize = 36.sp)
                    AlertDialog(onDismissRequest = {}, modifier = Modifier.testTag("dialog"), title = { Text("确认操作") },
                        text = { Text("弹窗中的文字保持清晰，后面的内容不能透进来。") },
                        confirmButton = { TextButton(onClick = {}) { Text("知道了") } })
                }
            }
        }
        compareUnderlay("dialog") { underlay = true }
    }
}
