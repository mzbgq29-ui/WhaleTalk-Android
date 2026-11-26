// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppReleaseUpdate(
    val tag: String,
    val versionName: String,
    val versionCode: Long? = null,
    val pageUrl: String,
    val apkUrl: String? = null,
    val apkBytes: Long? = null,
    val notes: String = "",
    val prerelease: Boolean = false,
)

data class AppUpdateCheck(
    val latest: AppReleaseUpdate?,
    val candidate: AppReleaseUpdate?,
    val message: String,
    val source: String,
    val ok: Boolean = true,
)

object AppUpdateStore {
    val state = MutableStateFlow<AppUpdateCheck?>(null)
    fun publish(value: AppUpdateCheck?) { state.value = value }
}

private class GitHubRateLimitException : IllegalStateException("GitHub 接口暂时限流，请稍后再试")

/** Compare and parse GitHub Releases / tags for this WhaleTalk repository. */
internal object GitHubReleaseUpdates {
    const val OWNER = "mzbgq29-ui"
    const val REPO = "WhaleTalk-Android"
    const val RELEASES_API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    const val TAGS_API = "https://api.github.com/repos/$OWNER/$REPO/tags?per_page=10"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
    private val versionCodeHint = Regex("""(?:versionCode|版本代码)\s*[:=]?\s*[`\"]?(\d{5,9})[`\"]?""")

    private val prereleaseToken = Regex("[0-9]+|[^0-9._-]+")

    fun versionNameFromTag(tag: String): String = tag.trim().trimStart('v', 'V')

    fun compareVersionNames(left: String, right: String): Int {
        val a = versionNameFromTag(left).substringBefore('+')
        val b = versionNameFromTag(right).substringBefore('+')
        val coreOrder = compareTokens(coreTokens(a.substringBefore('-')), coreTokens(b.substringBefore('-')))
        if (coreOrder != 0) return coreOrder

        val aSuffix = a.substringAfter('-', "")
        val bSuffix = b.substringAfter('-', "")
        if (aSuffix.isEmpty() || bSuffix.isEmpty()) {
            return aSuffix.isEmpty().compareTo(bSuffix.isEmpty())
        }
        return compareTokens(prereleaseToken.findAll(aSuffix).map { it.value }.toList(),
            prereleaseToken.findAll(bSuffix).map { it.value }.toList())
    }

    private fun coreTokens(version: String): List<String> =
        version.split('.', '_').filter { it.isNotBlank() }.dropLastWhile { part -> part.all { it == '0' } }

    private fun compareTokens(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toBigIntegerOrNull()
            val y = b[i].toBigIntegerOrNull()
            val cmp = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> 1
                y != null -> -1
                else -> a[i].compareTo(b[i], ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    fun isNewer(remoteName: String, remoteCode: Long?, localName: String, localCode: Long): Boolean {
        if (remoteCode != null && remoteCode > 0) return remoteCode > localCode
        return compareVersionNames(remoteName, localName) > 0
    }

    fun versionCodeFromText(text: String): Long? = versionCodeHint.find(text)?.groupValues?.get(1)?.toLongOrNull()

    fun requireSuccessfulResponse(code: Int) {
        if (code == 403 || code == 429) throw GitHubRateLimitException()
        require(code in 200..299) { "GitHub 接口返回 $code" }
    }

    fun parseReleases(json: String, localName: String, localCode: Long): AppUpdateCheck {
        val array = JSONArray(json)
        val updates = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optBoolean("draft")) continue
                add(releaseFrom(item))
            }
        }
        return summarize(updates, localName, localCode, "github-releases", empty = "当前仓库还没有发布安装包")
    }

    fun parseTags(json: String, localName: String, localCode: Long): AppUpdateCheck {
        val array = JSONArray(json)
        val updates = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val tag = item.optString("name").ifBlank { continue }
                val name = versionNameFromTag(tag)
                add(AppReleaseUpdate(tag = tag, versionName = name,
                    pageUrl = "https://github.com/$OWNER/$REPO/releases/tag/${tag.trimStart('/')}",
                    prerelease = name.substringBefore('+').substringAfter('-', "").isNotEmpty()))
            }
        }
        return summarize(updates, localName, localCode, "github-tags",
            empty = "当前仓库还没有版本标签",
            noApk = "发现新版本，发布页尚未提供安装包")
    }

    private fun summarize(updates: List<AppReleaseUpdate>, localName: String, localCode: Long,
        source: String, empty: String, noApk: String? = null): AppUpdateCheck {
        if (updates.isEmpty()) return AppUpdateCheck(null, null, empty, source)
        val byVersion = Comparator<AppReleaseUpdate> { a, b -> compareVersionNames(a.versionName, b.versionName) }
        val latest = updates.filterNot { it.prerelease }.maxWithOrNull(byVersion) ?: updates.maxWith(byVersion)
        val newer = isNewer(latest.versionName, latest.versionCode, localName, localCode)
        val candidate = latest.takeIf { newer }
        val message = when {
            candidate == null -> "已是最新版本 ${latest.versionName}"
            candidate.apkUrl.isNullOrBlank() && noApk != null -> "$noApk（${candidate.versionName}）"
            else -> "发现新版本 ${candidate.versionName}"
        }
        return AppUpdateCheck(latest, candidate, message, source)
    }

    private fun releaseFrom(item: JSONObject): AppReleaseUpdate {
        val tag = item.optString("tag_name").ifBlank { item.optString("name") }
        val name = versionNameFromTag(tag)
        val body = item.optString("body")
        val notes = body.ifBlank { item.optString("name") }.trim().take(2000)
        val code = versionCodeFromText(body) ?: versionCodeFromText(item.optString("name"))
        val assets = item.optJSONArray("assets") ?: JSONArray()
        val apk = pickApk(assets)
        val page = item.optString("html_url").ifBlank { "$RELEASES_PAGE/tag/${tag.trimStart('/')}" }
        return AppReleaseUpdate(tag = tag, versionName = name, versionCode = code, pageUrl = page,
            apkUrl = apk?.optString("browser_download_url")?.ifBlank { null },
            apkBytes = apk?.optLong("size")?.takeIf { it > 0 },
            notes = notes, prerelease = item.optBoolean("prerelease"))
    }

    private fun pickApk(assets: JSONArray): JSONObject? {
        val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.optString("name").contains("arm64", ignoreCase = true) } ?: apks.firstOrNull()
    }
}

internal class AppUpdateChecker(
    private val context: Context,
    private val providedClient: OkHttpClient? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val localName: String = BuildConfig.VERSION_NAME,
    private val localCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val managerCatalogUrl: String = BuildConfig.MANAGER_CATALOG_URL,
) {
    private val client by lazy {
        providedClient ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    private val cache = File(context.filesDir, "manifests/github-releases.json")
    val usesCatalog get() = managerCatalogUrl.isNotBlank()

    fun cached(): AppUpdateCheck? = runCatching {
        val saved = DurableFiles.read(cache) ?: return null
        val source = saved.optString("source")
        val body = saved.getString("body")
        if (source == "github-tags") GitHubReleaseUpdates.parseTags(body, localName, localCode)
        else GitHubReleaseUpdates.parseReleases(body, localName, localCode)
    }.getOrNull()

    suspend fun checkIfDue(): AppUpdateCheck = withContext(Dispatchers.IO) {
        val settings = AutomaticUpdateSettings(context)
        val preview = cached()
        if (preview != null) AppUpdateStore.publish(preview)
        if (!settings.isDue(SCOPE, now()) && preview != null) return@withContext preview
        settings.attempted(SCOPE, now())
        val result = check()
        if (result.ok) settings.succeeded(SCOPE, now())
        result
    }

    suspend fun check(): AppUpdateCheck = withContext(Dispatchers.IO) {
        if (usesCatalog) {
            val status = ManagerApkRepository(context, managerCatalogUrl, providedClient, now).check { }
            val mapped = status.candidate?.let {
                AppReleaseUpdate(tag = it.id, versionName = it.versionName, versionCode = it.versionCode,
                    pageUrl = GitHubReleaseUpdates.RELEASES_PAGE, notes = it.notes, apkBytes = it.bytes)
            }
            val result = AppUpdateCheck(mapped, mapped, status.message, "manager-catalog")
            AppUpdateStore.publish(result)
            return@withContext result
        }
        try {
            val releases = httpGet(GitHubReleaseUpdates.RELEASES_API)
            var result = GitHubReleaseUpdates.parseReleases(releases, localName, localCode)
            var source = "github-releases"
            var body = releases
            if (result.latest == null) {
                val tags = httpGet(GitHubReleaseUpdates.TAGS_API)
                result = GitHubReleaseUpdates.parseTags(tags, localName, localCode)
                source = "github-tags"
                body = tags
            }
            DurableFiles.write(cache, JSONObject().put("source", source).put("body", body).put("checkedAt", now()))
            AppUpdateStore.publish(result)
            result
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            val fallback = cached()
            val message = (failure as? GitHubRateLimitException)?.message ?: "暂时无法检查更新，请稍后重试"
            val result = fallback?.copy(message = message, ok = false) ?: AppUpdateCheck(null, null, message, "github-releases", ok = false)
            AppUpdateStore.publish(result)
            result
        }
    }

    private suspend fun httpGet(url: String): String {
        require(url.startsWith("https://"))
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "WhaleTalk-Android")
            .get().build()
        client.newCall(request).execute().use { response ->
            currentCoroutineContext().ensureActive()
            GitHubReleaseUpdates.requireSuccessfulResponse(response.code)
            val bytes = response.body?.bytes() ?: ByteArray(0)
            require(bytes.size <= 512 * 1024) { "更新信息过大" }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    companion object { const val SCOPE = "github-releases" }
}
