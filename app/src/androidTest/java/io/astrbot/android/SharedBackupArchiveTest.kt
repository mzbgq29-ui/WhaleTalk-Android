package io.astrbot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.runtime.RuntimeVersions
import io.astrbot.android.runtime.SharedBackupArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SharedBackupArchiveTest {
    @Test fun exportedArchiveRestoresNestedQqDirectory() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val temporary = Files.createTempDirectory(cache.toPath(), "shared-backup-test").toFile()
        try {
            val source = File(temporary, "source").apply { mkdir() }
            val relative = "home/.config/QQ/fixtures/state.json"
            File(source, relative).apply { parentFile!!.mkdirs(); writeText("fixture") }
            File(source, "astrbot/data").mkdirs()
            File(source, "snowluma").mkdirs()
            val archive = SharedBackupArchive()
            val versions = RuntimeVersions(emptyMap(), "fixture")
            val zip = File(temporary, "backup.zip")
            val exported = archive.write(source, zip, versions, automatic = false)
            val destination = File(temporary, "extracted")
            val imported = archive.read(zip, destination, versions)
            assertEquals(exported.id, imported.id)
            assertEquals("fixture", File(destination, relative).readText())
        } finally {
            temporary.deleteRecursively()
        }
    }
}
