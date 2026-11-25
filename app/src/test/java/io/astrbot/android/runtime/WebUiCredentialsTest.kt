package io.astrbot.android.runtime

import io.astrbot.android.core.ServiceId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebUiCredentialsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun firstLoginAcceptsThePinnedAstrBotPasswordFormat() {
        val persistent = temporary.newFolder()
        writeCredential(persistent, "astrbot", dashboard("astrbot"))

        val login = requireNotNull(WebUiCredentials.initialLogin(persistent, ServiceId.ASTRBOT))
        assertEquals("astrbot", login.username)
        assertEquals(FIXTURE_PASSWORD, login.password)
    }

    @Test fun resetCredentialsUseTheExistingUsernameAndExpireAfterAccountChanges() {
        val persistent = temporary.newFolder()
        val dashboard = dashboard("custom-admin")
        val config = writeCredential(persistent, "custom-admin", dashboard)

        val login = requireNotNull(WebUiCredentials.initialLogin(persistent, ServiceId.ASTRBOT))
        assertEquals("custom-admin", login.username)
        assertTrue(WebUiCredentials.isCurrent(persistent, ServiceId.ASTRBOT, login))

        dashboard.put("password_change_required", false)
        config.writeText(JSONObject().put("dashboard", dashboard).toString())
        assertFalse(WebUiCredentials.isCurrent(persistent, ServiceId.ASTRBOT, login))
        assertNull(WebUiCredentials.initialLogin(persistent, ServiceId.ASTRBOT))
    }

    @Test fun newerPasswordHashTakesPrecedenceOverTheLegacyDigest() {
        val persistent = temporary.newFolder()
        val incompatible = dashboard("astrbot").put("pbkdf2_password", PBKDF2.dropLast(1) + "0")
        writeCredential(persistent, "astrbot", incompatible)

        assertNull(WebUiCredentials.initialLogin(persistent, ServiceId.ASTRBOT))
    }

    private fun writeCredential(persistent: File, username: String, dashboard: JSONObject): File {
        File(persistent, "manager/astrbot-initial-password").apply { parentFile!!.mkdirs(); writeText(FIXTURE_PASSWORD) }
        if (username != "astrbot") File(persistent, "manager/astrbot-initial-username").writeText(username)
        return File(persistent, "astrbot/data/cmd_config.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("dashboard", dashboard).toString())
        }
    }

    private fun dashboard(username: String) = JSONObject()
        .put("username", username)
        .put("password", "09778209c12bf67ef1786a78f8ea5e2c")
        .put("pbkdf2_password", PBKDF2)
        .put("password_change_required", true)

    private companion object {
        const val FIXTURE_PASSWORD = "WhaleTalkFixture7!"
        const val PBKDF2 = "pbkdf2_sha256\$600000\$00112233445566778899aabbccddeeff\$7b7502d5ed2c311e7db1a6ecc3e4aaeefe9bf9d279be39709973f7bcbc50d5ab"
    }
}
