package io.astrbot.android

import androidx.test.platform.app.InstrumentationRegistry
import io.astrbot.android.core.SnowStateMigration
import io.astrbot.android.runtime.PortableBackupArchive
import io.astrbot.android.runtime.RuntimeVersions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SnowPortableBackupTest {
    @Test fun migratedSettingsAndMatchingCredentialRoundTripThroughRealArchive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "snow-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root,"persistent").apply { mkdirs() }
            File(source,"astrbot/data").mkdirs(); File(source,"astrbot/data/test.json").writeText("{}")
            val legacy = File(source,"napcat/snowluma/config").apply { mkdirs() }
            File(legacy,"webui.json").writeText("fixture-auth-hash")
            File(legacy.parentFile,"bootstrap-password").writeText("fixture-admin-password")
            File(source,"home/.config").mkdirs(); File(source,"home/.config/private-qq-session").writeText("excluded")
            SnowStateMigration.migrate(source)
            val versions = RuntimeVersions(mapOf("AstrBot" to "4.29.3"), "fixture-compatible")
            val archive = PortableBackupArchive(); val zip = File(root,"backup.zip")
            archive.write(source, zip, versions)
            val restored = File(root,"restored")
            archive.read(zip, restored, versions)
            assertEquals("fixture-admin-password", File(restored,"snowluma/bootstrap-password").readText())
            assertEquals("fixture-auth-hash", File(restored,"snowluma/config/webui.json").readText())
            assertFalse(File(restored,"home").exists())
            assertFalse(File(restored,"napcat/snowluma").exists())
            assertFalse(File(restored,"napcat").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun oldPortableArchiveImportsCredentialsSymlinksAndGuestPathsIntoCanonicalNamespace() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"legacy-backup-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val source=File(root,"source").apply{mkdirs()}
            File(source,"astrbot/data").mkdirs()
            File(source,"astrbot/data/history.json").writeText("{\"origin\":\"napcat:FriendMessage:123\"}")
            File(source,"snowluma/config").mkdirs()
            File(source,"snowluma/bootstrap-password").writeText("fixture-retained-password")
            File(source,"snowluma/config/account.json").writeText("{\"path\":\"/persistent/napcat/config/snowluma/config\",\"token\":\"fixture-private\"}")
            android.system.Os.symlink("../../snowluma/config",File(source,"astrbot/data/snow-link").path)
            android.system.Os.symlink("../../snowluma",File(source,"astrbot/data/snow-root").path)
            val versions=RuntimeVersions(mapOf("AstrBot" to "4.29.3"),"new-key",legacyCompatibilityKeys=setOf("old-key"))
            val modern=File(root,"modern.zip");PortableBackupArchive().write(source,modern,versions)
            val old=File(root,"old.zip")
            fun legacyName(name:String)=if(name=="snowluma"||name.startsWith("snowluma/"))"napcat/config/"+name else name
            java.util.zip.ZipFile(modern).use { input ->
                val manifest=org.json.JSONObject(input.getInputStream(input.getEntry("manifest.json")).bufferedReader().readText())
                val sourceEntries=manifest.getJSONArray("entries")
                val entries=(0 until sourceEntries.length()).map { sourceEntries.getJSONObject(it) }.toMutableList()
                for(entry in entries) {
                    entry.put("name",legacyName(entry.getString("name")))
                    if(entry.getString("kind")=="symlink")entry.put("target",if(entry.getString("name").endsWith("snow-root"))"../../napcat/config/snowluma" else "../../napcat/config/snowluma/config")
                }
                for(name in listOf("napcat","napcat/config"))entries.add(org.json.JSONObject().put("name",name).put("kind","directory").put("bytes",0).put("sha256","").put("executable",false).put("target",""))
                val sorted=entries.sortedBy{it.getString("name")}
                manifest.put("schema",1).put("format","astrbot-android-portable").put("scopes",org.json.JSONArray(listOf("astrbot","napcat/config")))
                    .put("entries",org.json.JSONArray(sorted)).getJSONObject("versions").put("compatibilityKey","old-key")
                val bytes=manifest.toString().toByteArray()
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                java.util.zip.ZipOutputStream(old.outputStream()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"));zip.write(bytes);zip.closeEntry()
                    for(entry in sorted.filter{it.getString("kind")=="file"}) {
                        val name=entry.getString("name")
                        val original=if(name.startsWith("napcat/config/snowluma"))name.removePrefix("napcat/config/") else name
                        zip.putNextEntry(java.util.zip.ZipEntry("data/$name"));input.getInputStream(input.getEntry("data/$original")).use{it.copyTo(zip)};zip.closeEntry()
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("complete.json"));zip.write(org.json.JSONObject().put("manifestSha256",digest).put("entries",sorted.size).toString().toByteArray());zip.closeEntry()
                }
            }
            val restored=File(root,"restored")
            PortableBackupArchive().read(old,restored,versions)
            assertFalse(File(restored,"napcat").exists())
            assertEquals("fixture-retained-password",File(restored,"snowluma/bootstrap-password").readText())
            val account=org.json.JSONObject(File(restored,"snowluma/config/account.json").readText())
            assertEquals("/persistent/snowluma/config",account.getString("path"))
            assertEquals("fixture-private",account.getString("token"))
            assertEquals(File(restored,"snowluma/config").canonicalPath,File(restored,"astrbot/data/snow-link").canonicalPath)
            assertEquals(File(restored,"snowluma").canonicalPath,File(restored,"astrbot/data/snow-root").canonicalPath)
            assertTrue(File(restored,"astrbot/data/history.json").readText().contains("napcat:FriendMessage:123"))
            val roundtrip=File(root,"roundtrip.zip");PortableBackupArchive().write(restored,roundtrip,versions)
            PortableBackupArchive().read(roundtrip,File(root,"again"),versions)
            assertEquals("fixture-retained-password",File(root,"again/snowluma/bootstrap-password").readText())
        } finally { root.deleteRecursively() }
    }

}
