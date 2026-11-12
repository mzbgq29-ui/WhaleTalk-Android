package io.astrbot.android.runtime

import org.json.JSONObject

/** A persisted platform ID is also a history/memory key; never rename old conversations. */
internal object QqPlatform {
    const val CURRENT = "qq"
    private const val LEGACY = "napcat"
    fun display(value: String): String = if(value == LEGACY) "QQ" else if(value.startsWith("$LEGACY:")) "QQ:"+value.removePrefix("$LEGACY:") else value
    fun matches(id: String) = id == CURRENT || id == LEGACY
    fun id(document: JSONObject): String {
        val platforms = document.optJSONArray("platform") ?: return CURRENT
        return (0 until platforms.length()).mapNotNull { platforms.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "aiocqhttp" && matches(it.optString("id")) }
            ?.optString("id") ?: CURRENT
    }
}
