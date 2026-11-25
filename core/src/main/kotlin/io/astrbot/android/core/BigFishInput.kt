// SPDX-License-Identifier: MIT
package io.astrbot.android.core

import java.net.URI

/** Validated setup values. Never use data-class toString(): it would disclose the API key. */
class BigFishInput(owner: String, endpoint: String, model: String, key: String, friends: String = "", groups: String = "", val options: BigFishOptions = BigFishOptions()) {
    val owner = owner.trim().also { require(it.matches(Regex("[1-9][0-9]{4,19}"))) { "请填写主人的 QQ 号，不是机器人的小号" } }
    val endpoint = normalizeEndpoint(endpoint)
    val model = model.trim().also { require(it.length in 1..160 && it.none(Char::isISOControl)) { "请填写有效的模型名称" } }
    val key = key.trim().also { require(it.length in 8..8192 && it.none { c -> c.isWhitespace() || c.isISOControl() }) { "请填写完整的 API Key" } }
    val friends = (parseIds(friends) + this.owner).distinct()
    val groups = parseIds(groups)
    override fun toString() = "BigFishInput([private])"

    companion object {
        fun normalizeEndpoint(raw: String): String {
            val text = raw.trim().removeSuffix("/").removeSuffix("/chat/completions")
            val uri = runCatching { URI(text) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535) && uri.rawQuery == null && uri.rawFragment == null && text.length <= 1000 && text.none(Char::isISOControl)) {
                "接口地址须为 HTTPS，不能带密钥、查询参数或账号密码"
            }
            return text
        }
        fun parseIds(raw: String): List<String> {
            require(raw.length <= 4096) { "QQ 号或群号列表过长" }
            val values = raw.trim().split(Regex("[\\s,，;；]+" )).filter(String::isNotBlank).distinct()
            require(values.size <= 100 && values.all { it.matches(Regex("[1-9][0-9]{4,19}")) }) { "QQ 号或群号格式不正确，请用逗号分隔" }
            return values
        }
    }
}
