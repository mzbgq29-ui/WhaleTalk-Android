package io.astrbot.android.runtime
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
class BackupIdentityTest {
    @Test fun legacyBackupKeysRequireExactInstalledSdkFingerprint() {
        val root=Files.createTempDirectory("runtime-version").toFile()
        fun write(path:String,value:String){val f=root.resolve(path);f.parentFile!!.mkdirs();f.writeText(value)}
        try {
            val sdk=listOf("__version__ = '4.29.3'","version = 3.12.3","fixture-source","fixture-dependencies")
            listOf("opt/astrbot/astrbot/__init__.py","opt/astrbot-venv/pyvenv.cfg","opt/astrbot/.astrbot-source.json","var/lib/astrbot-manager/astrbot-python.lock.txt").zip(sdk).forEach{(path,text)->write(path,text)}
            write(".astrbot-bootstrap.json","{\"abi\":\"arm64-v8a\"}")
            write("var/lib/bigfish/runtime-components.json","{\"snowluma\":{\"version\":\"2.4.2\"},\"qq\":{\"version\":\"3.2.35-54395\"}}")
            val identity=JSONObject().put("sdkFingerprint",InstalledVersions.digest(sdk)).put("legacyCompatibilityKeys",org.json.JSONArray(listOf("known-old-key")))
            write("var/lib/bigfish/legacy-backup-identity.json",identity.toString())
            val current=InstalledVersions.read(root)
            assertTrue(current.acceptsBackupKey("known-old-key"))
            assertTrue(current.acceptsBackupKey(current.compatibilityKey))
            assertFalse(current.acceptsBackupKey("arbitrary-key"))
            write("opt/astrbot/.astrbot-source.json","different-sdk")
            assertFalse(InstalledVersions.read(root).acceptsBackupKey("known-old-key"))
        } finally { root.deleteRecursively() }
    }
}
