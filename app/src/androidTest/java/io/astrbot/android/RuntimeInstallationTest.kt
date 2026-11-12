package io.astrbot.android

import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.runtime.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

class RuntimeInstallationTest {
    private fun stream(path:String)=(URL("http://127.0.0.1:18936$path").openConnection() as HttpURLConnection).apply{connectTimeout=15000;readTimeout=120000}.inputStream
    @Test fun entireBundledImageExtractsAndBecomesReadyWithoutOldQq() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uicheck"))
        val root=File(context.filesDir,"runtime-data/ubuntu")
        assertFalse("Use a clean verification application",root.exists())
        assertTrue(root.mkdirs())
        val base=stream("/base.json").bufferedReader().use{JSONObject(it.readText())}
        val snapshot=stream("/snapshot.json").bufferedReader().use{JSONObject(it.readText())}
        assertEquals(base.getString("sha256"),snapshot.getString("baseRuntimeSHA256"))
        stream("/bootstrap").use{RuntimeArchiveExtractor.extract(it,root,base.getLong("unpackedBytes"),{phase->android.util.Log.i("FullRuntimeInstall",phase)})}
        assertFalse(RuntimeReadiness.complete(root))
        stream("/snapshot").use{RuntimeArchiveExtractor.extract(it,root,snapshot.getLong("unpackedBytes"),{phase->android.util.Log.i("FullRuntimeInstall",phase)},replace=true)}
        val packages=JSONObject(File(root,"var/lib/astrbot-manager/desktop-packages.json").readText())
        assertEquals(snapshot.getString("bundleSHA256"),packages.getString("bundleSHA256"))
        assertTrue(File(root,"opt/astrbot/main.py").isFile)
        assertTrue(File(root,"opt/astrbot-venv/pyvenv.cfg").isFile)
        assertFalse(File(root,"opt/qq").exists())
        DurableFiles.write(File(root,".astrbot-bootstrap.json"),base)
        assertTrue(RuntimeReadiness.complete(root))
        RuntimeStore.refreshInstallation(context)
        assertTrue(RuntimeStore.state.value.environmentInstalled)
        assertTrue("Fresh clean runtime must be recognized as complete",RuntimeStore.state.value.componentsReady)
        // Upgrade cleanup must leave readiness intact.
        File(root,"opt/qq/.astrbot-components.json").apply{parentFile!!.mkdirs();writeText("legacy fixture")}
        File(root,"opt/qq").deleteRecursively()
        RuntimeStore.refreshInstallation(context)
        assertTrue(RuntimeStore.state.value.componentsReady)
        val committed=File(root,".astrbot-bootstrap.json")
        assertTrue(committed.renameTo(File(root,".astrbot-bootstrap.json.bak")))
        RuntimeStore.refreshInstallation(context)
        assertTrue(committed.isFile)
        assertTrue(RuntimeStore.state.value.componentsReady)
    }
    @Test fun extractorAcceptsRootEntriesAndRejectsEscapingFiles() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"extract-check").apply{mkdirs()}
        fun archive(name:String,content:String):ByteArray {
            val output=java.io.ByteArrayOutputStream()
            TarArchiveOutputStream(GZIPOutputStream(output)).use { tar->
                tar.putArchiveEntry(TarArchiveEntry("./"));tar.closeArchiveEntry()
                val e=TarArchiveEntry(name);val bytes=content.toByteArray();e.size=bytes.size.toLong();e.mode=384
                tar.putArchiveEntry(e);tar.write(bytes);tar.closeArchiveEntry()
            };return output.toByteArray()
        }
        try {
            RuntimeArchiveExtractor.extract(archive("nested/check.txt","retained").inputStream(),root,8)
            assertEquals("retained",File(root,"nested/check.txt").readText())
            val large = "鲸鱼".repeat(50_000) + "tail"
            RuntimeArchiveExtractor.extract(archive("nested/large.txt",large).inputStream(),root,large.toByteArray().size.toLong())
            assertEquals("Multiple copy-buffer boundaries must retain every byte",large,File(root,"nested/large.txt").readText())
            try { RuntimeArchiveExtractor.extract(archive("../outside.txt","bad").inputStream(),root,3);fail("Traversal accepted") }
            catch(_:IllegalArgumentException){}
            assertFalse(File(root.parentFile,"outside.txt").exists())
            val outside=File(context.cacheDir,"outside-extraction").apply{mkdirs()}
            android.system.Os.symlink(outside.path,File(root,"redirect").path)
            var blocked=false
            try { RuntimeArchiveExtractor.extract(archive("redirect/forbidden.txt","bad").inputStream(),root,3,replace=true) }
            catch(_:Exception){blocked=true}
            assertTrue("An existing outside parent link must be rejected",blocked)
            assertFalse(File(outside,"forbidden.txt").exists())
            outside.delete()
        } finally { DurableFiles.delete(root,writableGuestDirectories=true) }
    }
}
