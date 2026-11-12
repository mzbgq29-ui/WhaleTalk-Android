// SPDX-License-Identifier: MIT
package io.astrbot.android.runtime

import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.core.QqState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Normal authenticated 2.4.2 API. An HTTP page alone never means QQ is online. */
data class QqObservation(val state: QqState, val account: String? = null)

class SnowLumaHealth(private val client: OkHttpClient, private val endpoint: LocalEndpoint) {
    private var token: String? = null
    private var until = 0L
    fun query(password: String): QqObservation = runCatching {
        if (token == null || System.currentTimeMillis() >= until) {
            val login = call("/api/login", JSONObject().put("password", password), false)
            check(login.optBoolean("success") && !login.optBoolean("mustChangePassword"))
            token = login.getString("token"); until = System.currentTimeMillis() + 30 * 60_000
        }
        val list = call("/api/qq-list").getJSONArray("list")
        if (list.length() == 0) return QqObservation(QqState.LOGGED_OUT)
        val uin = list.getJSONObject(0).getString("uin")
        check(uin.matches(Regex("[1-9][0-9]{4,19}")))
        val status = call("/api/debug/invoke", JSONObject().put("uin", uin).put("action", "get_status").put("params", JSONObject()))
        check(status.optString("status") == "ok" && status.optInt("retcode", -1) == 0)
        if (status.getJSONObject("data").optBoolean("online")) QqObservation(QqState.ONLINE, uin)
        else QqObservation(QqState.OFFLINE)
    }.getOrElse { until = 0L; QqObservation(QqState.UNKNOWN) }

    private fun call(path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject {
        val request = Request.Builder().url(endpoint.origin + path)
        if (authenticated) request.header("Authorization", "Bearer ${requireNotNull(token)}")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return client.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful)
            JSONObject(response.peekBody(256 * 1024).string())
        }
    }
}
