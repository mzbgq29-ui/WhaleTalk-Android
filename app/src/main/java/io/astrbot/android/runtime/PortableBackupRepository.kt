package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized
import io.astrbot.android.requireNotNullLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SAF transport and prepared imports; live data changes only through BackupRepository's journal. */
class PortableBackupRepository(private val context: Context) {
    private val files = context.filesDir
    private val imports = File(files, "staging/portable-import")
    private val export = File(files, "staging/portable-export")
    private val manifest = File(imports, "verified.json")
    private val data = File(imports, "data")
    private val archive = PortableBackupArchive()
    val hasInterruptedWork: Boolean get() = export.exists() || (imports.exists() && !DurableFiles.exists(manifest))
    val preparedInfo: PortableBackupInfo? get() = runCatching {
        val value = DurableFiles.read(manifest)?.takeIf { data.isDirectory } ?: return@runCatching null
        if (value.optString("format") == SharedBackupArchive.FORMAT) SharedBackupArchive().info(value).let {
            PortableBackupInfo(it.id, it.createdAt, it.bytes, it.count, it.versions)
        } else archive.info(value)
    }.getOrNull()

    suspend fun prepareImport(uri: Uri, versions: RuntimeVersions, progress: (String) -> Unit): PortableBackupInfo = withContext(Dispatchers.IO) {
        discardImport()
        checkLocalized(imports.mkdirs()) { AppText.get(R.string.ui_cannot_create_portable_backup_staging_directory) }
        val inputFile = File(imports, "source.zip")
        try {
            progress(AppText.get(R.string.ui_reading_selected_portable_backup))
            requireLocalized(imports.usableSpace >= RESERVE) { AppText.get(R.string.ui_insufficient_portable_backup_staging_space) }
            requireNotNullLocalized(context.contentResolver.openInputStream(uri)) { AppText.get(R.string.ui_cannot_read_selected_portable_backup) }.use { input ->
                FileOutputStream(inputFile).use { output -> transfer(input, output, imports); output.fd.sync() }
            }
            progress(AppText.get(R.string.ui_verifying_portable_backup_scope_versions_and_file_digests))
            val info = if (SharedBackupArchive().peekFormat(inputFile) == SharedBackupArchive.FORMAT) {
                val shared = SharedBackupArchive().read(inputFile, data, versions)
                PortableBackupInfo(shared.id, shared.createdAt, shared.bytes, shared.count, shared.versions)
            } else archive.read(inputFile, data, versions)
            inputFile.delete()
            info
        } catch (failure: Throwable) { discardImport(); throw failure }
    }

    suspend fun verifyPrepared(versions: RuntimeVersions): PortableBackupInfo = withContext(Dispatchers.IO) {
        val metadata = requireNotNullLocalized(DurableFiles.read(manifest)) { AppText.get(R.string.ui_import_a_portable_backup_first) }
        if (metadata.optString("format") == SharedBackupArchive.FORMAT) {
            SharedBackupArchive().verify(data, metadata, versions).let {
                PortableBackupInfo(it.id, it.createdAt, it.bytes, it.count, it.versions)
            }
        } else archive.verify(data, metadata, versions, exclusive = true)
    }

    suspend fun beginRestore(beforeSnapshot: BackupInfo, versions: RuntimeVersions) {
        val metadata = withContext(Dispatchers.IO) { requireNotNullLocalized(DurableFiles.read(manifest)) { AppText.get(R.string.ui_portable_backup_manifest_is_missing) } }
        val shared = metadata.optString("format") == SharedBackupArchive.FORMAT
        val scopes = if (shared) io.astrbot.android.core.SharedBackupPolicy.SCOPES else PortableBackupArchive.SCOPES
        BackupRepository(files).beginPortableRestore(beforeSnapshot.id, versions, data, scopes = scopes) { candidate ->
            if (shared) SharedBackupArchive().verify(candidate, metadata, versions)
            else archive.verify(candidate, metadata, versions, exclusive = candidate == data)
        }
    }

    suspend fun prepareExport(versions: RuntimeVersions, progress: (String) -> Unit): PortableBackupInfo = withContext(Dispatchers.IO) {
        discardExport()
        checkLocalized(export.mkdirs()) { AppText.get(R.string.ui_cannot_create_portable_backup_export_directory) }
        progress(AppText.get(R.string.ui_creating_a_portable_backup_without_qq_login_sessions))
        // The controller holds maintenance and has stopped QQ. Upgraded installs may export before their first QQ start.
        io.astrbot.android.core.SnowStateMigration.migrate(File(files, "persistent"), DurableFiles::move)
        try { archive.write(File(files, "persistent"), File(export, "backup.zip"), versions) }
        catch (failure: Throwable) { discardExport(); throw failure }
    }

    suspend fun deliverExport(uri: Uri, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val file = File(export, "backup.zip")
        requireLocalized(file.isFile && file.length() in 1..PortableBackupArchive.MAX_ARCHIVE) { AppText.get(R.string.ui_portable_backup_export_file_is_missing) }
        progress(AppText.get(R.string.ui_services_resumed_writing_to_the_selected_backup_file))
        requireNotNullLocalized(context.contentResolver.openOutputStream(uri, "wt")) { AppText.get(R.string.ui_cannot_write_the_selected_backup_file) }.use { output ->
            file.inputStream().use { input ->
                checkLocalized(transfer(input, output, null) == file.length()) { AppText.get(R.string.ui_portable_backup_write_is_incomplete_export_it_again) }
            }
            output.flush()
        }
    }

    fun discardImport() = DurableFiles.delete(imports)
    fun discardExport() = DurableFiles.delete(export)
    fun cleanupInterruptedWork() {
        discardExport()
        if (!DurableFiles.exists(manifest)) discardImport()
    }

    private suspend fun transfer(input: InputStream, output: OutputStream, local: File?): Long {
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            requireLocalized(size <= PortableBackupArchive.MAX_ARCHIVE) { AppText.get(R.string.ui_portable_backup_exceeds_the_2_gib_limit) }
            requireLocalized(local == null || local.usableSpace >= RESERVE) { AppText.get(R.string.ui_insufficient_portable_backup_staging_space) }
            output.write(buffer, 0, count)
        }
        return size
    }

    companion object { private const val RESERVE = 64L * 1024 * 1024 }
}
