// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.astrbot.android.core.SecretRedactor
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** UI-owned API adapter. Only loopback origins from the local service configuration. */
internal class StudioApi(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).followRedirects(false).build()
    private val authLock = Mutex()
    open class AccountLoginRequired(message: String) : IllegalStateException(message)
    private class AuthExpired : AccountLoginRequired("本机服务认证已失效，请在「本机管理账号」中登录")
    @Volatile private var botToken: String? = null
    private var qqToken: String? = null
    private var botUntil = 0L
    private var qqUntil = 0L
    private fun origin(id: ServiceId) = requireNotNull(RuntimeConfiguration(context).read(id).endpoint) { "本机服务尚未配置" }.origin
    private fun body(value: JSONObject) = value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private suspend fun <T> cancellable(call: Call, block: suspend (Response) -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { block(it) } } finally { watcher.cancel() }
    }

    private suspend fun request(id: ServiceId, path: String, method: String, payload: JSONObject?, token: String?): JSONObject {
        require(path.startsWith("/api/") && !path.contains('\n'))
        val builder = Request.Builder().url(origin(id) + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(method, if (method in setOf("GET", "HEAD")) null else body(payload ?: JSONObject()))
        return cancellable(client.newCall(builder.build())) { response ->
            val loginRejected = response.code == 401 && path == AUTH_LOGIN
            if (response.code == 401 && !loginRejected) throw AuthExpired()
            check(response.code != 403) { "本机服务拒绝此操作，请确认管理账号权限或安全验证" }
            check(response.isSuccessful || loginRejected) { "本机服务暂时无法响应（${response.code}），请确认已启动" }
            val content = requireNotNull(response.body).source().apply { request(4L * 1024 * 1024 + 1) }
            check(content.buffer.size <= 4L * 1024 * 1024) { "返回内容过大，请缩小查询范围" }
            val result = try { JSONObject(content.readUtf8()) } catch (_: Exception) { error("本机服务返回格式无法识别，请查看运行日志") }
            val okay = if (id == ServiceId.SNOWLUMA) result.optInt("code", -1) == 0 else result.optString("status") in setOf("ok", "success")
            if (!okay || loginRejected) {
                val message = SecretRedactor.redact(result.optString("message").ifBlank { "操作未完成，请查看运行日志" }).take(220)
                if (path == AUTH_LOGIN) throw AccountLoginRequired(message)
                error(message)
            }
            result
        }
    }

    private suspend fun loginInternal(username: String, password: String, code: String = ""): Boolean {
        val data = request(ServiceId.ASTRBOT, AUTH_LOGIN, "POST", JSONObject()
            .put("username", username).put("password", password).put("code", code), null).getJSONObject("data")
        botToken = data.getString("token"); botUntil = System.currentTimeMillis() + 50 * 60_000
        return data.optBoolean("change_pwd_hint") || data.optBoolean("password_upgrade_required")
    }
    suspend fun login(username: String, password: String, code: String = "") = withContext(Dispatchers.IO) {
        authLock.withLock { loginInternal(username, password, code) }
    }
    private suspend fun botAuth(): String = authLock.withLock {
        if (botToken == null || System.currentTimeMillis() >= botUntil) {
            val credentials = withContext(Dispatchers.IO) { WebUiCredentials.initialLogin(context, ServiceId.ASTRBOT) }
                ?: throw AccountLoginRequired("当前没有可用的初始密码，请前往「本机管理账号」登录或重置密码")
            withContext(Dispatchers.IO) { loginInternal(credentials.username, credentials.password) }
        }
        requireNotNull(botToken)
    }
    private suspend fun <T> authenticated(block: suspend (String) -> T): T {
        val original = botAuth()
        return try { block(original) } catch (_: AuthExpired) {
            authLock.withLock { if (botToken == original) { botToken = null; botUntil = 0 } }
            // Only a 401 received before execution is retried. Never retry a transport failure.
            block(botAuth())
        }
    }
    suspend fun bot(path: String, method: String = "GET", payload: JSONObject? = null): Any? = authenticated { token ->
        withContext(Dispatchers.IO) { request(ServiceId.ASTRBOT, path, method, payload, token).opt("data") }
    }
    suspend fun applyPersona(id: String) {
        val value = bot("/api/v1/config-profiles/default") as JSONObject
        val config = PersonaTemplates.applyToProfile(value.getJSONObject("config"), id)
        bot("/api/v1/config-profiles/default", "PUT", config)
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); botToken = null; botUntil = 0 }

    suspend fun sendChat(session: String, text: String, onEvent: suspend (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        authenticated { token ->
        val request = Request.Builder().url(origin(ServiceId.ASTRBOT) + "/api/chat/send")
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
            .post(body(JSONObject().put("session_id", session).put("message", text).put("locale", "zh-CN"))).build()
        cancellable(client.newCall(request)) { response ->
                if (response.code == 401) throw AuthExpired()
                check(response.isSuccessful) { "对话服务暂时无法响应（${response.code}）" }
                val source = requireNotNull(response.body).source()
                var total = 0L
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = try { source.readUtf8LineStrict(256L * 1024) } catch (_: java.io.EOFException) { error("对话数据格式不完整或单段过大，请重试") }
                    total += line.length
                    check(total <= 4L * 1024 * 1024) { "本次回复过长，已停止接收" }
                    if (line.startsWith("data:")) {
                        val value = line.removePrefix("data:").trim()
                        if (value.isNotBlank() && value != "[DONE]") onEvent(try { JSONObject(value) } catch (_: Exception) { error("对话返回格式无法识别") })
                    }
                }
        }
        }
    }

    suspend fun uploadKnowledge(id: String, uri: android.net.Uri) = uploadLocal("/api/kb/document/upload", uri, mapOf("kb_id" to id))
    suspend fun uploadSkill(uri: android.net.Uri) = uploadLocal("/api/v1/skills", uri, emptyMap())
    suspend fun uploadPlugin(uri: android.net.Uri) = uploadLocal("/api/v1/plugins/install/upload", uri, mapOf("ignore_version_check" to "false"))
    private suspend fun uploadLocal(path: String, uri: android.net.Uri, fields: Map<String,String>) {
        withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "document.txt"
            val file = File.createTempFile("knowledge-", ".upload", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { out ->
                    val buffer = ByteArray(65536); var bytes = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; bytes += count
                        check(bytes <= 64L * 1024 * 1024) { "单次导入请使用 64 MB 以内的文件" }; out.write(buffer, 0, count) }
                } }
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply { fields.forEach { (key,value) -> addFormDataPart(key,value) } }
                    .addFormDataPart("file", name, object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = file.length()
                        override fun writeTo(sink: okio.BufferedSink) { file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n) } } }
                    }).build()
                authenticated { token ->
                cancellable(client.newCall(Request.Builder().url(origin(ServiceId.ASTRBOT) + path)
                    .header("Authorization", "Bearer $token").post(multipart).build())) { r ->
                    if (r.code == 401) throw AuthExpired()
                    check(r.isSuccessful) { "文件未能上传（${r.code}）" }
                    val source = requireNotNull(r.body).source().apply { request(4L * 1024 * 1024 + 1) }
                    check(source.buffer.size <= 4L * 1024 * 1024) { "操作结果过大，请查看运行日志" }
                    val result = try { JSONObject(source.readUtf8()) } catch (_: Exception) { error("操作结果格式无法识别，请查看运行日志") }
                    check(result.optString("status") == "ok") { SecretRedactor.redact(result.optString("message", "文件未能导入")).take(220) }
                }
                }
            } finally { file.delete() }
        }
    }

    private companion object { const val AUTH_LOGIN = "/api/auth/login" }
}

internal fun urlValue(value: String): String = URLEncoder.encode(value, "UTF-8")
internal fun jsonRows(value: Any?, vararg keys: String): List<JSONObject> {
    if (value is JSONArray) return List(value.length()) { i -> value.optJSONObject(i) ?: JSONObject().put("name", value.optString(i)) }
    if (value is JSONObject) for (key in keys.toList() + listOf("items", "data", "list", "records")) {
        val array = value.optJSONArray(key) ?: continue
        return jsonRows(array)
    }
    return emptyList()
}
