package io.astrbot.android.core

/** Decides which persistent files enter a shared automatic backup, and which old archives to drop. */
object SharedBackupPolicy {
    const val KEEP = 5
    val SCOPES = listOf("astrbot", "snowluma", "home/.config/QQ")

    private val skippedSegments = setOf(
        "cache", "code cache", "code_cache", "gpucache", "gpu_cache", "dawncache", "shadercache",
        "crash_files", "crashpad", "logs", "log", "tmp", "temp", ".cache", "cachestorage",
        "service worker", "service-worker", "cacheddata", "cached data", "thumbnails",
    )
    private val mediaSegments = setOf("pic", "video", "emojis", "emoji", "pfile", "nt_emoji")

    fun included(relative: String): Boolean {
        val path = normalize(relative)
        if (path.isEmpty()) return false
        return SCOPES.any { path == it || path.startsWith("$it/") }
    }

    fun excluded(relative: String): Boolean {
        val path = normalize(relative)
        if (path.isEmpty()) return true
        val parts = path.split('/')
        if (parts.any { it.lowercase() in skippedSegments }) return true
        val name = parts.last().lowercase()
        if (name.endsWith(".log") || name.endsWith(".tmp") || name == "core") return true
        val lowerParts = parts.map { it.lowercase() }
        if (lowerParts.contains("nt_data") && lowerParts.any { it in mediaSegments }) return true
        return false
    }

    fun retain(relative: String) = included(relative) && !excluded(relative)

    fun idsToDelete(createdAtById: List<Pair<String, Long>>, keep: Int = KEEP): List<String> {
        require(keep >= 1)
        return createdAtById.sortedByDescending { it.second }.drop(keep).map { it.first }
    }

    private fun normalize(relative: String) = relative.replace('\\', '/').trim().trimStart('/')
}
