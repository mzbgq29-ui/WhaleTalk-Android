package io.astrbot.android.runtime

import android.system.Os
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.LocalizedStateException
import io.astrbot.android.requireLocalized
import io.astrbot.android.core.ArchivePaths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/** Shared production extractor, also exercised with the complete payload on Android. */
internal object RuntimeArchiveExtractor {
    suspend fun extract(raw: InputStream, staging: File, expectedBytes: Long, progress: (String)->Unit = {},
        replace: Boolean = false, entriesText: Int = R.string.ui_extracting_ubuntu_entries) {
        data class Link(val name: String, val target: String, val hard: Boolean)
        val links = mutableListOf<Link>()
        val root = staging.canonicalFile.toPath()
        var written = 0L
        var entries = 0
        // Regular files and directories cannot change previously validated parents.
        // Links are deferred, and their parents are checked again without this cache.
        val verifiedParents=mutableSetOf<java.nio.file.Path>()
        // Reuse one copy buffer: a complete image contains tens of thousands of
        // tiny files, so allocating 128 KiB for each one creates gigabytes of GC work.
        val copyBuffer = ByteArray(131072)
        raw.use { input ->
            TarArchiveInputStream(BufferedInputStream(GZIPInputStream(input,131072),131072)).use { archive ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = archive.nextEntry ?: break
                    entries++
                    if (entries % 500 == 0) progress(AppText.get(entriesText, entries))
                    val path = ArchivePaths.entry(root, entry.name, allowUnixEscapes = true)
                    if (path == root) { require(entry.isDirectory) { "归档根节点必须是目录" }; continue }
                    if(verifiedParents.add(path.parent)) requireLocalized(path.parent.toFile().canonicalFile.toPath().startsWith(root)) { AppText.get(R.string.qq_bundle_missing) }
                    when {
                        entry.isSymbolicLink || entry.isLink -> links += Link(entry.name, entry.linkName, entry.isLink)
                        entry.isDirectory -> check(path.toFile().isDirectory || path.toFile().mkdirs())
                        entry.isFile -> {
                            requireLocalized(entry.size >= 0 && entry.size <= 1024L * 1024 * 1024) { AppText.get(R.string.ui_invalid_resource_file_size) }
                            written += entry.size
                            requireLocalized(written <= expectedBytes + 1024 * 1024) { AppText.get(R.string.ui_invalid_extracted_resource_size) }
                            path.parent.toFile().mkdirs()
                            if (replace && java.nio.file.Files.isSymbolicLink(path)) java.nio.file.Files.delete(path)
                            path.toFile().outputStream().use { output ->
                                while (true) {
                                    val count = archive.read(copyBuffer)
                                    if (count < 0) break
                                    if (count > 0) output.write(copyBuffer, 0, count)
                                }
                            }
                            Os.chmod(path.toString(), entry.mode and 511)
                            check(path.toFile().setLastModified(entry.lastModifiedDate.time))
                        }
                        else -> Unit // Device nodes are provided through Android /dev bindings.
                    }
                }
            }
        }
        progress(AppText.get(R.string.ui_preparing_ubuntu_links_entries, links.size))
        links.filter { it.hard }.forEach {
            val path = ArchivePaths.entry(root, it.name, allowUnixEscapes = true)
            val target = ArchivePaths.entry(root, it.target, allowUnixEscapes = true)
            requireLocalized(target.toFile().canonicalFile.toPath().startsWith(root)) { AppText.get(R.string.ui_hard_link_outside_permitted_paths, it.name, it.target) }
            path.parent.toFile().mkdirs()
            // Android app SELinux policy can forbid hard links, even inside the app's data.
            if (replace && java.nio.file.Files.isSymbolicLink(path)) java.nio.file.Files.delete(path)
            target.toFile().copyTo(path.toFile(), overwrite = replace)
            Os.chmod(path.toString(), Os.stat(target.toString()).st_mode and 511)
        }
        links.filterNot { it.hard }.forEach {
            val path = ArchivePaths.entry(root, it.name, allowUnixEscapes = true)
            // A previously created link must not redirect creation outside staging.
            requireLocalized(path.parent.toFile().canonicalFile.toPath().startsWith(root)) { AppText.get(R.string.ui_symbolic_link_parent_outside_permitted_paths, it.name) }
            val target = ArchivePaths.link(root, path, it.target, allowUnixEscapes = true)
            path.parent.toFile().mkdirs()
            try {
                if (replace) java.nio.file.Files.deleteIfExists(path)
                Os.symlink(target.toString(), path.toString())
            }
            catch (failure: android.system.ErrnoException) {
                throw LocalizedStateException(AppText.get(R.string.ui_cannot_prepare_link, it.name, it.target), failure)
            }
        }
    }
}
