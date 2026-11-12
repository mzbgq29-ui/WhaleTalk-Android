// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.runtime

import org.json.JSONObject

/** App owns the enabled switch; preserve user-defined trigger and cooldown. */
internal object SnowStatusCommand {
    fun configuration(previous: JSONObject?, enabled: Boolean, inherited: JSONObject? = null): JSONObject {
        val value = inherited?.let { JSONObject(it.toString()) } ?: JSONObject()
        previous?.let { own -> own.keys().forEach { key -> value.put(key, own.get(key)) } }
        value.put("enabled", enabled)
        // Status queries have their own reply and must not incur an LLM request.
        value.put("swallow", true)
        if (!value.has("trigger")) value.put("trigger", "#sl")
        if (!value.has("cooldownSeconds")) value.put("cooldownSeconds", 5)
        return value
    }
}
