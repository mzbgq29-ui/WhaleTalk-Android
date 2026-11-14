package io.astrbot.android.runtime

import android.content.Context

/** User toggle for reconnecting crashed services and a dropped message channel. Default is on. */
class AutoReconnectSettings(context: Context) {
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)

    fun enabled(): Boolean = preferences.getBoolean(PREFERENCE, true)

    fun setEnabled(value: Boolean) {
        check(preferences.edit().putBoolean(PREFERENCE, value).commit()) { "自动重连设置未能保存" }
    }

    companion object { const val PREFERENCE = "auto_reconnect" }
}
