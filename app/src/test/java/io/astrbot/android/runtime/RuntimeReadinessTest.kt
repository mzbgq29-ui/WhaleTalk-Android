package io.astrbot.android.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeReadinessTest {
    @Test fun cleanCompleteRuntimeWorksWithoutOldQqDirectoryAndStaysReadyAfterUpgradeCleanup() {
        val root=Files.createTempDirectory("runtime-readiness").toFile()
        try {
            RuntimeReadiness.requiredFiles.forEach { path->File(root,path).apply { parentFile!!.mkdirs();writeText("fixture") } }
            assertFalse(File(root,"opt/qq").exists())
            assertTrue(RuntimeReadiness.complete(root))
            File(root,"opt/qq/.astrbot-components.json").apply { parentFile!!.mkdirs();writeText("legacy") }
            assertTrue(RuntimeReadiness.complete(root))
            File(root,"opt/qq").deleteRecursively()
            assertTrue(RuntimeReadiness.complete(root))
            File(root,"var/lib/bigfish/runtime-components.json").delete()
            assertFalse(RuntimeReadiness.complete(root))
        } finally { root.deleteRecursively() }
    }
    @Test fun extractionStagingAndIncompleteGuestNeverCountAsCommittedRuntime() {
        val root=Files.createTempDirectory("runtime-staging").toFile()
        try {
            RuntimeReadiness.requiredFiles.filterNot{it==".astrbot-bootstrap.json"}.forEach { path->File(root,path).apply{parentFile!!.mkdirs();writeText("fixture")} }
            assertFalse(RuntimeReadiness.complete(root))
            File(root,".astrbot-bootstrap.json").writeText("fixture")
            assertTrue(RuntimeReadiness.complete(root))
            File(root,"opt/astrbot/main.py").delete()
            assertFalse(RuntimeReadiness.complete(root))
        } finally { root.deleteRecursively() }
    }
}
