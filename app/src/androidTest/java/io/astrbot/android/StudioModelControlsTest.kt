package io.astrbot.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StudioModelControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun tutorialAndSearchableModelSelectionWorkOnAndroid() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("studio-model-recents", 0).edit().clear().commit()
        var selected = ""
        rule.setContent { BigFishTheme { Surface { Column {
            StudioModelHelp()
            StudioModelPicker("https://example.invalid/v1", true, { "fixture-key" }, { selected = it }, fetch = { base, key ->
                assertEquals("https://example.invalid/v1", base); assertEquals("fixture-key", key)
                listOf("chat-alpha", "chat-beta", "embedding-gamma")
            })
        } } } }
        rule.onNodeWithText("模型 API 填写教程").performClick()
        rule.onNodeWithText("连接云端模型 · 4 步").assertIsDisplayed()
        rule.onNodeWithText("知道了").performClick()
        rule.onNodeWithText("获取模型列表").performClick()
        rule.waitForIdle()
        rule.waitUntil(10000) { runCatching { rule.onAllNodesWithText("选择模型（3）").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        rule.onNode(hasSetTextAction()).performTextInput("beta")
        rule.onNodeWithText("chat-alpha").assertDoesNotExist()
        rule.onNodeWithText("chat-beta").performClick()
        rule.runOnIdle { assertEquals("chat-beta", selected) }
        rule.onNodeWithText("最近选择 · 当前服务商").assertIsDisplayed()
        rule.onNodeWithText("清空记录").performClick()
        rule.onNodeWithText("最近选择 · 当前服务商").assertDoesNotExist()
    }
}
