// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual Android rendering, DES authentication and RAW RFB frames, via an
 * adb-reversed local fixture. This does not sign in to a QQ account.
 */
class StudioQqDesktopTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun frame(name: String) {
        rule.waitUntil(60_000) { rule.onAllNodesWithContentDescription("QQ 登录桌面").fetchSemanticsNodes().size == 1 }
        val image = rule.onNodeWithTag("qq-native-frame").captureToImage()
        val pixels=image.toPixelMap();var red=0;var green=0
        for(y in 0 until pixels.height step 4)for(x in 0 until pixels.width step 4){
            val c=pixels[x,y]
            if(c.red>.65f && c.green<.35f)red++
            if(c.green>.5f && c.red<.35f)green++
        }
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"qq-view-tests").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        assertTrue("Authenticated red frame pixels ($red)",red>100)
        assertTrue("Authenticated green frame pixels ($green)",green>100)
    }
    @Test fun firstOpenRefreshAndReentryDisplayRealAuthenticatedFrames() {
        var visible by mutableStateOf(true)
        rule.setContent { BigFishTheme { Surface { if(visible) StudioQqDesktop(Modifier.fillMaxWidth().height(480.dp),passwordReader={"Fixture7"}) } } }
        frame("first-open")
        rule.onNodeWithText("保存登录画面").performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithText("已保存到相册 Pictures/BigFish，可在手机 QQ 扫一扫中选择这张图片").fetchSemanticsNodes().size == 1 }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection, arrayOf(android.provider.MediaStore.Images.Media._ID),
            android.provider.MediaStore.Images.Media.RELATIVE_PATH + "=?", arrayOf("Pictures/BigFish/"),
            android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            val saved = requireNotNull(context.contentResolver.openInputStream(uri)!!.use(android.graphics.BitmapFactory::decodeStream))
            assertEquals(320, saved.width); assertEquals(200, saved.height)
            assertEquals(android.graphics.Color.rgb(220,40,50), saved.getPixel(10,10))
            context.contentResolver.delete(uri,null,null)
        }
        rule.onNodeWithTag("qq-native-frame").performTouchInput { click(center) }
        rule.onNodeWithTag("qq-native-frame").performTouchInput {
            down(center); moveTo(androidx.compose.ui.geometry.Offset(width.toFloat() + 40, height.toFloat() + 40)); up()
        }
        rule.onNodeWithText("放大画面").performClick()
        rule.onNodeWithTag("qq-native-frame").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(30f, 30f)) }
        rule.onNodeWithText("适应屏幕").performClick()
        rule.onNodeWithText("键盘").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("123abc")
        rule.onNodeWithText("输入到 QQ").performClick()
        rule.onNodeWithText("刷新").performClick()
        frame("refresh")
        rule.runOnIdle { visible=false };rule.waitForIdle()
        rule.runOnIdle { visible=true };rule.waitForIdle()
        frame("reentry")
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        frame("resume")
    }
}
