// SPDX-License-Identifier: MIT
package io.astrbot.android.runtime

import io.astrbot.android.core.BigFishInput
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read-only model discovery. Never follows redirects or includes a key in URL/error text. */
internal class BigFishModelCatalog(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()) {
    suspend fun fetch(endpoint: String, key: String): List<String> {
        val input = BigFishInput("100001", endpoint, "model-discovery", key)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(input.endpoint + "/models")
                .header("Authorization", "Bearer " + input.key).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("获取模型列表失败，请检查网络和 API 地址，也可手动填写模型名称"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            check(it.isSuccessful) { when (it.code) {
                                401, 403 -> "API Key 无效或无权读取模型列表，请检查密钥权限"
                                404, 405 -> "此服务商未开放模型列表，请手动填写模型名称"
                                429 -> "获取过于频繁或额度不足，请稍后重试"
                                in 300..399 -> "请填写服务商最终 HTTPS API 地址，模型列表不接受跳转"
                                else -> "获取模型列表失败（HTTP ${it.code}），可手动填写模型名称"
                            } }
                            val source = requireNotNull(it.body).source()
                            check(!source.request(2L * 1024 * 1024 + 1)) { "模型列表过大，请手动填写模型名称" }
                            val rows = try { JSONObject(source.readUtf8()).optJSONArray("data") } catch (_: Exception) { null }
                            check(rows != null && rows.length() <= 10000) { "服务商返回的模型列表格式不兼容，请手动填写" }
                            val names = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.optString("id") }
                                .filter { name -> name.isNotBlank() && name.length <= 160 && name.none(Char::isISOControl) && name != "null" }.distinct().sorted()
                            check(names.isNotEmpty()) { "没有返回可选模型，请确认密钥权限，或手动填写模型名称" }
                            if (continuation.isActive) continuation.resume(names)
                        } catch (error: Exception) {
                            val message = (error as? IllegalStateException)?.message ?: "无法读取模型列表，请手动填写模型名称"
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                        }
                    }
                }
            })
        }
    }
}
