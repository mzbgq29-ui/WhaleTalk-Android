package io.astrbot.android.core

object SecretRedactor {
    // Remove terminal presentation before matching labels or credential values.
    // Match actual ESC/C1 introducers only; ordinary bracketed text is preserved.
    private val terminalOsc = Regex("(?:\u001B\\]|\u009D)[^\u0007\u001B\u009C]*(?:\u0007|\u001B\\\\|\u009C)")
    private val terminalCsi = Regex("(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]")
    // Match query parameters first so a generic label cannot consume the next URL parameter.
    private val labeledOrQuery = Regex(
        """(?i)([?&](?:api_?key|access_token|token|key|secret)=)([^&\s"'<>]*)|""" +
            """(["']?(?:password|passwd|token|access_token|api_key|apikey|secret|authorization)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)"""
    )
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val basic = Regex("""(?i)(Basic\s+)[A-Za-z0-9+/=._~-]+""")
    private val initial = Regex("(?i)((?:Initial password|WebUi Token):\\s*)\\S+")
    private val authHeader = Regex("(?im)^(\\s*Authorization\\s*:\\s*)[^\\r\\n]+")
    private val apiKeyHeader = Regex("""(?im)^([ \t]*(?:x-)?api[-_]?key[ \t]*:[ \t]*)[^\r\n]+""")
    private val known = Regex(
        """\b(?:sk-[A-Za-z0-9_-]{12,}|AIza[0-9A-Za-z_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"""
    )
    private val qqLoginUrl = Regex("https?://txz\\.qq\\.com/p\\?[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    private val qrTerminalLine = Regex("^[ \\t\\u2580-\\u259F]{6,}\\r?$", RegexOption.MULTILINE)

    fun redact(text: String): String {
        var result = terminalCsi.replace(terminalOsc.replace(text, ""), "")
        result = qrTerminalLine.replace(result, "[登录二维码已隐藏]")
        result = qqLoginUrl.replace(result, "[登录二维码链接已隐藏]")
        result = initial.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = authHeader.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = apiKeyHeader.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = bearer.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = basic.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = labeledOrQuery.replace(result) {
            val (query, value, label) = it.destructured
            when {
                label.isNotEmpty() -> label + "[已隐藏]"
                value.isNotEmpty() -> query + "[已隐藏]"
                else -> it.value
            }
        }
        return known.replace(result, "[已隐藏]")
    }
}
