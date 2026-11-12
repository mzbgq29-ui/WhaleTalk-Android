package io.astrbot.android

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class StudioPersonaWarehouseTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeWarehouseSearchFavoriteImportAndApplyUseLocalProfileContract() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val server=MockWebServer()
        val writes=CopyOnWriteArrayList<Pair<String,JSONObject>>()
        val original=JSONObject("""{"agent_runner":{"runner_type":"local","config":{"persona":{"persona_id":"before"},"model":{"provider_id":"fixture-model"},"compression":{"max_turns":12}}},"other":42}""")
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
            val data:Any=when {
                r.path=="/api/auth/login"->JSONObject().put("token","fixture-only")
                r.path=="/api/persona/list"->org.json.JSONArray()
                r.method=="POST"&&r.path=="/api/persona/create"->{writes.add(r.path!! to JSONObject(r.body.readUtf8()));JSONObject()}
                r.method=="GET"&&r.path=="/api/v1/config-profiles/default"->JSONObject().put("config",original)
                r.method=="PUT"&&r.path=="/api/v1/config-profiles/default"->{writes.add(r.path!! to JSONObject(r.body.readUtf8()));JSONObject()}
                else->return MockResponse().setResponseCode(404)
            }
            return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("status","ok").put("data",data).toString())
        }}
        server.start()
        val config=File(context.filesDir,"persistent/astrbot/data/cmd_config.json")
        val old=if(config.exists())config.readBytes() else null
        config.parentFile!!.mkdirs();config.writeText(JSONObject().put("dashboard",JSONObject().put("host","127.0.0.1").put("port",server.port)).toString())
        val vm=StudioModel(context.applicationContext as Application)
        try {
            runBlocking { vm.api.login("fixture","fixture-password") }
            rule.setContent { BigFishTheme { Surface { StudioPersonaLibrary(vm) } } }
            rule.waitUntil(20000){runCatching { rule.onAllNodesWithText("11 张角色卡 · 支持酒馆 V1 / V2 / V3").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)}
            rule.onNode(hasSetTextAction()).performTextInput("深海鲸鱼")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.waitForIdle()
            rule.onAllNodesWithText("收藏").onLast().performClick()
            rule.onNodeWithText("已收藏").assertIsDisplayed()
            File(context.filesDir,"persona-warehouse-proof.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            rule.onNodeWithText("预览与导入").performClick()
            rule.onNodeWithText("导入并应用").performClick()
            rule.waitUntil(20000){writes.size==2&&!vm.busy}
            rule.runOnIdle {
                assertNull(vm.error)
                assertEquals(2,writes.first().second.getJSONArray("begin_dialogs").length())
                val persona=writes.first().second.getString("persona_id")
                val profile=writes.last().second
                val agent=profile.getJSONObject("agent_runner").getJSONObject("config")
                assertEquals(persona,agent.getJSONObject("persona").getString("persona_id"))
                assertEquals("fixture-model",agent.getJSONObject("model").getString("provider_id"))
                assertEquals(12,agent.getJSONObject("compression").getInt("max_turns"))
                assertEquals(42,profile.getInt("other"))
            }
        } finally {
            vm.api.close();server.shutdown()
            if(old==null)config.delete() else config.writeBytes(old)
        }
    }
}
