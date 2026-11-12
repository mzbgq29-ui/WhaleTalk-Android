package io.astrbot.android

import android.content.Context
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.core.BigFishInput
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.BigFishSetupRepository
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Emulator-only disposable package. No QQ, PRoot process or real model account involved. */
@RunWith(AndroidJUnit4::class)
class BigFishPersistenceTest {
    private lateinit var context: Context
    private lateinit var repository: BigFishSetupRepository
    private lateinit var config: File
    private val original = """{"dashboard":{"port":6185},"custom":"preserve me"}"""
    private val input = BigFishInput("100001", "https://api.deepseek.com/v1", "deepseek-chat", "sk-fixture-not-real")
    @Before fun initialize() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uicheck")) { "Refusing to alter a non-fixture app" }
        File(context.filesDir, "persistent").deleteRecursively(); File(context.filesDir, "operations").deleteRecursively()
        config = File(context.filesDir, "persistent/astrbot/data/cmd_config.json")
        config.parentFile!!.mkdirs(); config.writeText(original)
        repository = BigFishSetupRepository(context)
    }
    @After fun cleanup() {
        File(context.filesDir, "persistent").deleteRecursively(); File(context.filesDir, "operations").deleteRecursively()
    }
    @Test fun processRestartRecoversOriginalConfigAndDesiredServices() {
        repository.request(input, 12345); repository.prepare(setOf(ServiceId.ASTRBOT))
        assertEquals(384, Os.stat(config.path).st_mode and 511)
        assertEquals(384, Os.stat(File(context.filesDir, "persistent/manager/bigfish-request.json").path).st_mode and 511)
        val reopened = BigFishSetupRepository(context)
        assertTrue(reopened.needsRecovery)
        assertEquals(setOf(ServiceId.ASTRBOT), reopened.recover())
        assertEquals("preserve me", JSONObject(config.readText()).getString("custom"))
        assertFalse(JSONObject(config.readText()).has("provider_sources"))
        assertFalse(File(context.filesDir, "persistent/napcat/config/onebot11.json").exists())
        assertTrue(reopened.pending); assertNull(reopened.summary())
    }
    @Test fun committedRecordHasNoSecretAndDoesNotLoseNewRequests() {
        repository.request(input, 12345)
        val request = File(context.filesDir, "persistent/manager/bigfish-request.json")
        val before = request.readText()
        repository.prepare(emptySet()); repository.commit()
        val summary = requireNotNull(repository.summary())
        assertFalse(summary.toString().contains(input.key)); assertFalse(summary.has("key")); assertFalse(summary.has("token"))
        assertFalse(repository.needsRecovery); assertFalse(repository.pending)
        request.writeText(before) // Crash after commit point, before request cleanup.
        repository.cleanCommittedRequest(); assertFalse(repository.pending)
        repository.request(input, 54321); repository.cleanCommittedRequest(); assertTrue(repository.pending)
    }
    @Test fun interruptedSummaryWriteRollsBackWithConfiguration() {
        repository.request(input, 12345); repository.prepare(emptySet())
        val summary = File(context.filesDir, "persistent/manager/bigfish-setup.json")
        summary.writeText("""{"owner":"100001"}""")
        repository.recover()
        assertFalse(summary.exists()); assertEquals("preserve me", JSONObject(config.readText()).getString("custom"))
    }
    @Test fun savedCredentialsCannotBeReusedForAnotherEndpoint() {
        repository.request(input, 12345); repository.prepare(emptySet()); repository.commit()
        assertEquals(input.key, repository.savedKeyFor(input.endpoint))
        assertEquals(input.key, repository.savedKeyFor(input.endpoint + "/"))
        assertEquals("", repository.savedKeyFor("https://other.example/v1"))
        assertEquals("", repository.savedKeyFor("https://api.deepseek.com/another-path"))
        val document = JSONObject(config.readText())
        document.getJSONArray("provider_sources").getJSONObject(0).put("api_base", "https://other.example/v1")
        config.writeText(document.toString())
        assertEquals("", repository.savedKeyFor(input.endpoint))
    }
    @Test fun duplicatePreparationAndRecoveryPathEscapeAreRejected() {
        repository.request(input, 12345); repository.prepare(emptySet())
        assertThrows(IllegalStateException::class.java) { repository.prepare(emptySet()) }
        val journal = File(context.filesDir, "operations/bigfish-setup.json")
        val record = JSONObject(journal.readText())
        record.getJSONArray("files").getJSONObject(0).put("path", "../../must-not-write.json")
        journal.writeText(record.toString())
        assertThrows(IllegalStateException::class.java) { repository.recover() }
        assertTrue(repository.needsRecovery)
        assertFalse(File(context.filesDir.parentFile, "must-not-write.json").exists())
    }
}
