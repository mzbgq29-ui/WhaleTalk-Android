package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.core.BigFishInput
import org.json.JSONArray
import java.security.MessageDigest

/** Contains only model IDs, indexed by a hash of the normalized endpoint. Never stores credentials. */
internal class ModelRecents(context: Context) {
    private val prefs = context.getSharedPreferences("studio-model-recents", Context.MODE_PRIVATE)
    private fun id(endpoint: String): String = MessageDigest.getInstance("SHA-256")
        .digest(BigFishInput.normalizeEndpoint(endpoint).toByteArray()).joinToString("") { "%02x".format(it) }
    fun read(endpoint: String): List<String> = runCatching {
        val rows = JSONArray(prefs.getString(id(endpoint), "[]"))
        (0 until minOf(rows.length(), 8)).map { rows.getString(it) }.filter { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }
    }.getOrDefault(emptyList())
    fun remember(endpoint: String, model: String) {
        if (model.isBlank() || model.length > 160 || model.any(Char::isISOControl)) return
        runCatching { prefs.edit().putString(id(endpoint), JSONArray((listOf(model) + read(endpoint)).distinct().take(8)).toString()).apply() }
    }
    fun clear(endpoint: String) { runCatching { prefs.edit().remove(id(endpoint)).apply() } }
}
