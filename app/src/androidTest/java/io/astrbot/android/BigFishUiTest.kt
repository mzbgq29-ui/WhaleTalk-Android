package io.astrbot.android

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.runtime.ManagerState
import io.astrbot.android.runtime.RuntimeStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** UI fixtures only. Does not simulate successful QQ login or label it as a real-device test. */
@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(35)
class BigFishUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun reset(ready: Boolean) {
        check(context.packageName.endsWith(".uicheck"))
        context.getSharedPreferences("bigfish-ui", 0).edit().putString("theme", "light").commit()
        File(context.filesDir, "persistent").deleteRecursively(); File(context.filesDir, "operations").deleteRecursively()
        val root = File(context.filesDir, "runtime-data/ubuntu"); root.deleteRecursively()
        context.getSharedPreferences("manager", 0).edit().clear().commit()
        context.getSharedPreferences("bigfish-ui", 0).edit().clear().putString("theme", "light").commit()
        if (ready) {
            for ((name, content) in mapOf(".astrbot-bootstrap.json" to """{"abi":"x86_64","id":"ui-fixture","components":{}}""",
                "opt/astrbot/main.py" to "# UI fixture; never executed", "opt/astrbot-venv/pyvenv.cfg" to "home=/usr/bin",
                "opt/qq/.astrbot-components.json" to """{"schemaVersion":1,"status":"ui-fixture"}""",
                "opt/astrbot/astrbot/__init__.py" to "__version__ = '4.29.3'")) {
                File(root, name).also { it.parentFile!!.mkdirs(); it.writeText(content) }
            }
        }
        RuntimeStore.state.value = ManagerState()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || result.size > 2000) return
            result += node
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow, 0)
        return result
    }
    private fun awaitText(value: String, enabled: Boolean = false): AccessibilityNodeInfo {
        val deadline = System.nanoTime() + 45_000_000_000L
        while (System.nanoTime() < deadline) {
            nodes().firstOrNull { it.text?.contains(value) == true && it.isVisibleToUser && (!enabled || it.isEnabled) }?.let { return it }
            Thread.sleep(150)
        }
        throw AssertionError("UI text not visible: $value")
    }
    private fun screenshot(name: String) {
        val file = File(context.cacheDir, "bigfish-ui/$name.png"); file.parentFile!!.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        assertFalse("Tap target must have screen bounds", rect.isEmpty)
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
    }
    @After fun cleanup() {
        context.getSharedPreferences("bigfish-ui", 0).edit().putString("theme", "system").commit()
        reset(false)
    }
    @Test fun realIntroRejectsUnsupportedRuntimeAndKeepsAdvancedEntry() {
        reset(false)
        ActivityScenario.launch(BigFishWizardActivity::class.java).use {
            awaitText("准备好这些")
            awaitText("功能中心")
            assertFalse(RuntimeStore.state.value.busy)
            screenshot("intro-light")
        }
    }
    @Test fun invalidInputIsBlockedBeforeConfigOrLoginAndSecretsSurviveRotationOnlyInMemory() {
        reset(true)
        ActivityScenario.launch(BigFishWizardActivity::class.java).use { scenario ->
            val button = awaitText("验证模型并继续", enabled = true)
            tap(button)
            awaitText("这一步还没完成")
            assertFalse(File(context.filesDir, "persistent/manager/bigfish-request.json").exists())
            assertFalse(RuntimeStore.state.value.busy)
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[BigFishForm::class.java].apply {
                    owner = "100001"; key = "sk-ui-fixture-not-real"; error = null
                }
            }
            scenario.recreate()
            awaitText("100001")
            scenario.onActivity { activity -> assertEquals("sk-ui-fixture-not-real", ViewModelProvider(activity)[BigFishForm::class.java].key) }
            // Scroll the actual key field into view before checking its accessibility masking.
            for (attempt in 0..8) {
                if (nodes().any { it.isPassword && it.isVisibleToUser }) break
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(250)
            }
            assertFalse(nodes().any { !it.isPassword && it.text?.contains("sk-ui-fixture-not-real") == true })
            assertTrue(nodes().any { it.isPassword && it.isVisibleToUser })
            screenshot("model-light")
        }
    }
    @Test fun nativeFeatureDirectoryCanSearchWithoutStartingAnyRuntime() {
        reset(false)
        ActivityScenario.launch(BigFishFeaturesActivity::class.java).use {
            awaitText("海屿控制台")
            val field = nodes().first { it.isEditable }
            val input = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "备份") }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            awaitText("备份与恢复")
            assertFalse(nodes().any { it.text?.contains("插件市场") == true && it.isVisibleToUser })
            assertFalse(RuntimeStore.state.value.busy)
            screenshot("features-search")
        }
    }
    @Test fun phoneKeyboardDoesNotCoverThePrimaryAction() {
        reset(true)
        ActivityScenario.launch(BigFishWizardActivity::class.java).use { scenario ->
            awaitText("验证模型并继续", enabled = true)
            var field: AccessibilityNodeInfo? = null
            for (attempt in 0..3) {
                field = nodes().firstOrNull { it.isEditable && it.isVisibleToUser }
                if (field != null) break
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(200)
            }
            tap(requireNotNull(field))
            val frame = android.graphics.Rect()
            var screenHeight = 0
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    activity.window.decorView.getWindowVisibleDisplayFrame(frame)
                    screenHeight = activity.resources.displayMetrics.heightPixels
                }
                if (frame.bottom < screenHeight - 100) break
                Thread.sleep(150)
            }
            assertTrue("The software keyboard must actually be open", frame.bottom < screenHeight - 100)
            val action = awaitText("验证模型并继续", enabled = true)
            val bounds = android.graphics.Rect(); action.getBoundsInScreen(bounds)
            assertTrue("The main action must remain above the keyboard", bounds.bottom <= frame.bottom + 2)
            screenshot("model-keyboard")
        }
    }
    @Test fun darkModeAndLargeFontKeepTheMainActionInsideTheScreen() {
        reset(true)
        context.getSharedPreferences("bigfish-ui", 0).edit().putString("theme", "dark").commit()
        val automation = instrumentation.uiAutomation
        val descriptor = automation.executeShellCommand("settings put system font_scale 1.3")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        try {
            ActivityScenario.launch(BigFishWizardActivity::class.java).use {
                val button = awaitText("验证模型并继续")
                val bounds = android.graphics.Rect(); button.getBoundsInScreen(bounds)
                assertTrue(bounds.top >= 0)
                assertTrue(bounds.bottom <= context.resources.displayMetrics.heightPixels)
                screenshot("model-dark-large-font")
            }
        } finally {
            val reset = automation.executeShellCommand("settings put system font_scale 1.0")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(reset).use { it.readBytes() }
        }
    }
}
