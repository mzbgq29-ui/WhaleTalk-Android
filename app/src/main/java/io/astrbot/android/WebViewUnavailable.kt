// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun WebViewUnavailable(onSettings: () -> Unit, onClose: () -> Unit) {
    BigFishTheme {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LiquidPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("先启用系统 WebView", style = MaterialTheme.typography.headlineSmall)
                    Text("当前系统的网页组件不可用。请在手机应用设置中启用或更新 Android System WebView／Chrome，然后重新打开登录页。")
                    LiquidButton(onSettings, Modifier.fillMaxWidth()) { Text("打开手机应用设置") }
                    LiquidOutlinedButton(onClose, Modifier.fillMaxWidth()) { Text("返回") }
                }
            }
        }
    }
}
