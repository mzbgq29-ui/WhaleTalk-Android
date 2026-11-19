package io.astrbot.android.runtime

import org.json.JSONObject

/** A persisted platform ID is also a history/memory key; never rename old conversations. */
internal object QqPlatform {
    const val CURRENT = "qq"
    private const val LEGACY = "napcat"
    fun display(value: String): String = if(value == LEGACY) "QQ" else if(value.startsWith("$LEGACY:")) "QQ:"+value.removePrefix("$LEGACY:") else value
    fun matches(id: String) = id == CURRENT || id == LEGACY
    fun configuration(document: JSONObject): JSONObject? {
        val platforms = document.optJSONArray("platform") ?: return null
        return (0 until platforms.length()).mapNotNull { platforms.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "aiocqhttp" && matches(it.optString("id")) }
    }
    fun id(document: JSONObject): String = configuration(document)?.optString("id") ?: CURRENT

    data class Bridge(val enabled: Boolean, val port: Int, val token: String)

    fun bridge(document: JSONObject): Bridge? = configuration(document)?.let {
        Bridge(it.optBoolean("enable", true), it.optInt("ws_reverse_port", 6199), it.optString("ws_reverse_token"))
    }
}
