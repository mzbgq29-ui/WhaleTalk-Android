package io.astrbot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.WebUiCredentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class WebUiCredentialsResetTest {
    @Test fun resetKeepsTheAccountAndTwoFactorSettings() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val persistent = Files.createTempDirectory(cache.toPath(), "astrbot-credential-test").toFile()
        try {
            val dashboard = JSONObject().put("username", "custom-admin").put("jwt_secret", "old-token-key")
                .put("totp", JSONObject().put("enable", true).put("secret", "fixture"))
            val config = File(persistent, "astrbot/data/cmd_config.json").apply {
                parentFile!!.mkdirs()
                writeText(JSONObject().put("dashboard", dashboard).put("wake_prefix", "fixture").toString())
            }

            WebUiCredentials.reset(persistent, ServiceId.ASTRBOT)

            val login = requireNotNull(WebUiCredentials.initialLogin(persistent, ServiceId.ASTRBOT))
            assertEquals("custom-admin", login.username)
            val saved = JSONObject(config.readText())
            val updated = saved.getJSONObject("dashboard")
            assertTrue(updated.getBoolean("password_change_required"))
            assertTrue(updated.getJSONObject("totp").getBoolean("enable"))
            assertEquals("fixture", updated.getJSONObject("totp").getString("secret"))
            assertNotEquals("old-token-key", updated.getString("jwt_secret"))
            assertEquals("fixture", saved.getString("wake_prefix"))
        } finally {
            persistent.deleteRecursively()
        }
    }
}
