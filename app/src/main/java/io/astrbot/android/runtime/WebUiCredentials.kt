package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import io.astrbot.android.core.ServiceId
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Read for credential controls or local login assistance; never put in logs or saved Activity state. */
object WebUiCredentials {
    internal data class Login(val username: String, val password: String, val signature: String? = null)

    /** Only the generated first-login AstrBot account; never retain a user's replacement password. */
    internal fun initialLogin(context: Context, id: ServiceId): Login? = initialLogin(File(context.filesDir, "persistent"), id)
    internal fun initialLogin(persistent: File, id: ServiceId): Login? = runCatching {
        val password = read(persistent, id) ?: return null
        if (id == ServiceId.SNOWLUMA) return Login("local", password)
        val file = File(persistent, "astrbot/data/cmd_config.json")
        require(file.isFile && file.length() <= 8 * 1024 * 1024)
        val dashboard = JSONObject(file.readText().removePrefix("\uFEFF")).getJSONObject("dashboard")
        val username = dashboard.optString("username")
        val generatedUsername = File(persistent, "manager/astrbot-initial-username").takeIf { it.isFile && it.length() <= 4096 }?.readText() ?: "astrbot"
        require(username.isNotBlank() && username == generatedUsername && dashboard.optBoolean("password_change_required"))
        val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
        require(dashboard.optString("password").equals(md5, ignoreCase = true))
        val pbkdf2 = dashboard.optString("pbkdf2_password")
        if (pbkdf2.isNotEmpty()) {
            val parts = pbkdf2.split('$')
            require(parts.size == 4 && parts[0] == "pbkdf2_sha256")
            val rounds = parts[1].toInt()
            require(rounds in 1..1_000_000 && parts[2].matches(Regex("[0-9a-fA-F]{32}")) && parts[3].matches(Regex("[0-9a-fA-F]{64}")))
            val salt = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
            val actual = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
            val expected = parts[3].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(MessageDigest.isEqual(actual, expected))
        }
        Login(username, password, signature(dashboard))
    }.getOrNull()

    /** Cheap per-attempt revalidation also covers SPA setup/logout without a document reload. */
    internal fun isCurrent(context: Context, id: ServiceId, login: Login): Boolean =
        isCurrent(File(context.filesDir, "persistent"), id, login)

    internal fun isCurrent(persistent: File, id: ServiceId, login: Login): Boolean = runCatching {
        if (id == ServiceId.SNOWLUMA) read(persistent, id) == login.password
        else {
            val file = File(persistent, "astrbot/data/cmd_config.json")
            require(file.isFile && file.length() <= 8 * 1024 * 1024)
            login.signature != null && signature(JSONObject(file.readText().removePrefix("\uFEFF")).getJSONObject("dashboard")) == login.signature
        }
    }.getOrDefault(false)

    private fun signature(dashboard: JSONObject): String {
        val fields = JSONObject()
        listOf("username", "password", "pbkdf2_password", "password_change_required").forEach { fields.put(it, dashboard.opt(it)) }
        return MessageDigest.getInstance("SHA-256").digest(fields.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun read(context: Context, id: ServiceId): String? = read(File(context.filesDir, "persistent"), id)

    /** Call only after stopping this service; business configuration and QQ sessions stay in place. */
    internal fun reset(persistent: File, id: ServiceId) {
        require(id == ServiceId.ASTRBOT) { "此入口仅管理聊天引擎账号" }
        val file = File(persistent, "astrbot/data/cmd_config.json")
        require(file.isFile && file.length() <= 8 * 1024 * 1024)
        val config = JSONObject(file.readText().removePrefix("\uFEFF"))
        val random = SecureRandom()
        fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val password = "Aa7!${randomHex(16)}"
        val dashboard = config.getJSONObject("dashboard")
        val currentUsername = (dashboard.opt("username") as? String).orEmpty().ifBlank { "astrbot" }
        val salt = ByteArray(16).also(random::nextBytes)
        val rounds = 600_000
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        dashboard.put("username", currentUsername)
            .put("password", MessageDigest.getInstance("MD5").digest(password.toByteArray()).hex())
            .put("pbkdf2_password", "pbkdf2_sha256\$$rounds\$${salt.hex()}\$${derived.hex()}")
            .put("password_change_required", true).put("password_storage_upgraded", true)
            .put("jwt_secret", randomHex(32))
        val initial = File(persistent, "manager/astrbot-initial-password")
        val username = File(persistent, "manager/astrbot-initial-username")
        val old = initial.takeIf { it.isFile }?.readBytes()
        val oldUsername = username.takeIf { it.isFile }?.readBytes()
        try {
            writeSecret(username, currentUsername.toByteArray())
            writeSecret(initial, password.toByteArray())
            writeSecret(file, config.toString().toByteArray(Charsets.UTF_8))
        } catch (failure: Exception) {
            val committed = runCatching {
                val stored = JSONObject(file.readText().removePrefix("\uFEFF")).getJSONObject("dashboard")
                signature(stored) == signature(dashboard)
            }.getOrDefault(false)
            if (!committed) runCatching {
                if (old == null) AtomicFile(initial).delete() else writeSecret(initial, old)
                if (oldUsername == null) AtomicFile(username).delete() else writeSecret(username, oldUsername)
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun writeSecret(file: File, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            Os.fchmod(output.fd, 384)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        check(file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) { "后台凭据未能保存" }
        DurableFiles.sync(file.parentFile!!)
    }

    private fun read(persistent: File, id: ServiceId): String? = runCatching {
        val file = File(persistent, if (id == ServiceId.SNOWLUMA) "snowluma/bootstrap-password" else "manager/astrbot-initial-password")
        require(file.isFile && file.length() <= if (id == ServiceId.SNOWLUMA) 8 * 1024 * 1024 else 4096)
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val value = if (id == ServiceId.SNOWLUMA) text.trim() else text.trim()
        value.takeIf { it.isNotBlank() && it.length <= 4096 }
    }.getOrNull()
}
