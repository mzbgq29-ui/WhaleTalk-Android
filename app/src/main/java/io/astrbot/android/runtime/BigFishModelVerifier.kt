// SPDX-License-Identifier: MIT
package io.astrbot.android.runtime

import io.astrbot.android.core.BigFishInput
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One explicitly requested, tiny Chat Completions call. No retries, redirects or raw error body. */
internal class BigFishModelVerifier(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()) {
    suspend fun verify(input: BigFishInput) = suspendCancellableCoroutine<Unit> { continuation ->
        val modernOpenAi = io.astrbot.android.core.BigFishModelPresets.completionTokenLimit(input.model)
        val payload = JSONObject().put("model", input.model).put("stream", false)
            .put(if (modernOpenAi) "max_completion_tokens" else "max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "请仅回复 OK。")))
        if (URI(input.endpoint).host?.lowercase() in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")) {
            payload.put("enable_thinking", false)
        }
        val request = Request.Builder().url(input.endpoint + "/chat/completions").header("Authorization", "Bearer " + input.key)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = when (e) {
                    is SocketTimeoutException -> "模型服务响应超时，请检查网络或稍后重试"
                    is UnknownHostException -> "无法解析模型服务域名，请检查接口地址"
                    is SSLException -> "安全连接验证失败，请检查系统时间或接口证书"
                    else -> "未连上模型服务，请检查网络、接口地址后重试"
                }
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val failure = when (it.code) {
                        401, 403 -> "API Key 未通过认证，请检查密钥及服务商"
                        402 -> "模型账户余额不足，请在服务商处充值后重试"
                        404 -> "没有找到这个模型或接口，请检查模型名称和地址"
                        429 -> "模型服务限流或额度不足，请检查账户后重试"
                        in 300..399 -> "接口要求跳转，请填写服务商最终的 HTTPS 接口地址"
                        in 500..599 -> "模型服务暂时不可用，请稍后重试"
                        in 200..299 -> null
                        else -> "模型服务返回 HTTP ${it.code}，请核对配置"
                    }
                    try {
                        check(failure == null) { failure!! }
                        val body = requireNotNull(it.body) { "模型没有返回内容" }
                        val source = body.source()
                        check(!source.request(131073)) { "模型响应过大，未保存返回内容" }
                        val document = JSONObject(source.readUtf8())
                        val result = document.optJSONArray("choices")?.optJSONObject(0)
                        val choice = result?.optJSONObject("message")
                        // Reasoning models may spend the small verification budget before producing text.
                        // A length stop plus nonzero completion usage proves acceptance, not a full chat reply.
                        val acceptedBudget = choice != null && result.optString("finish_reason") == "length" &&
                            (document.optJSONObject("usage")?.optInt("completion_tokens", 0) ?: 0) > 0
                        val hasText = when (val content = choice?.opt("content")) {
                            is String -> content.isNotBlank()
                            is JSONArray -> (0 until content.length()).any {
                                !(content.optJSONObject(it)?.opt("text") as? String).isNullOrBlank()
                            }
                            else -> false
                        }
                        val hasReasoning = choice != null && listOf("reasoning_content", "reasoning").any { key ->
                            !choice.isNull(key) && choice.optString(key).isNotBlank()
                        }
                        check(acceptedBudget || hasText || hasReasoning) {
                            "接口已响应，但返回格式不兼容 Chat Completions"
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Exception) {
                        val message = failure ?: (error as? IllegalStateException)?.message ?: "模型返回格式无法识别，请核对接口"
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            }
        })
    }
}
