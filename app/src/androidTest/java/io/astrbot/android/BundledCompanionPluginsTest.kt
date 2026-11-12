package io.astrbot.android

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.runtime.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BundledCompanionPluginsTest {
    @Test fun offlineDeploymentIsCompleteAndPreservesExistingPluginFiles() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir,"companion-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = root }
        try {
            val data = File(root,"persistent/astrbot/data").apply { mkdirs() }
            DurableFiles.write(File(data,"cmd_config.json"), JSONObject().put("wake_prefix", JSONArray().put("大肥鱼").put("/")))
            val repository = BundledCompanionPlugins(context)
            repository.prepare {}
            val memory = File(data,"plugins/${CompanionConfiguration.MEMORY}")
            val group = File(data,"plugins/${CompanionConfiguration.GROUP}")
            assertTrue(File(memory,"main.py").isFile); assertTrue(File(memory,"LICENSE").isFile)
            assertTrue(File(memory,"core/injection.upstream.py").isFile)
            assertTrue(File(group,"requirements.upstream.txt").isFile)
            assertFalse(File(group,"requirements.txt").readText().lineSequence().any { it.startsWith("pytest") || it.startsWith("hypothesis") })
            assertFalse(File(memory,"requirements.txt").readText().lineSequence().any { it.startsWith("astrbot") })
            assertTrue(File(data,"site-packages/argon2/__init__.py").isFile)
            val native = File(data,"site-packages/_argon2_cffi_bindings").listFiles()!!.first { it.name.endsWith(".so") }.readBytes()
            assertEquals(183, (native[18].toInt() and 255) or ((native[19].toInt() and 255) shl 8))
            val config = requireNotNull(DurableFiles.read(File(data,"config/${CompanionConfiguration.MEMORY}_config.json")))
            assertFalse(config.getJSONObject("retrieval").getBoolean("embedding_enabled"))
            assertFalse(config.getJSONObject("memory_summary").getBoolean("enabled"))
            File(memory,"main.py").writeText("# user edited source — must be preserved")
            repository.prepare {}
            assertEquals("# user edited source — must be preserved", File(memory,"main.py").readText())
        } finally { root.deleteRecursively() }
    }
}
