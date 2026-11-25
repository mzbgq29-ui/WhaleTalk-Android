package io.astrbot.android.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.astrbot.android.core.SharedBackupPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SharedBackupInterval { OFF, DAILY, WEEKLY }

data class SharedBackupItem(
    val id: String, val name: String, val createdAt: Long, val bytes: Long, val uri: Uri, val automatic: Boolean,
)

class SharedBackupSettings(context: Context) {
    private val preferences = context.getSharedPreferences("shared-backup", Context.MODE_PRIVATE)
    fun interval(): SharedBackupInterval = runCatching {
        SharedBackupInterval.valueOf(preferences.getString(INTERVAL, SharedBackupInterval.DAILY.name)!!)
    }.getOrDefault(SharedBackupInterval.DAILY)
    fun setInterval(value: SharedBackupInterval) {
        check(preferences.edit().putString(INTERVAL, value.name).commit()) { "自动备份周期未能保存" }
    }
    fun lastSuccess(): Long = preferences.getLong(SUCCESS, 0)
    fun lastAttempt(): Long = preferences.getLong(ATTEMPT, 0)
    fun markAttempt(now: Long = System.currentTimeMillis()) { check(preferences.edit().putLong(ATTEMPT, now).commit()) }
    fun markSuccess(now: Long = System.currentTimeMillis()) { check(preferences.edit().putLong(ATTEMPT, now).putLong(SUCCESS, now).commit()) }
    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        val period = when (interval()) {
            SharedBackupInterval.OFF -> return false
            SharedBackupInterval.DAILY -> 20L * 60 * 60 * 1000
            SharedBackupInterval.WEEKLY -> 6L * 24 * 60 * 60 * 1000
        }
        if (lastSuccess() != 0L && now - lastSuccess() < period) return false
        if (lastAttempt() != 0L && now - lastAttempt() < 3L * 60 * 60 * 1000) return false
        return true
    }
    fun nextAt(now: Long = System.currentTimeMillis()): Long {
        val period = when (interval()) {
            SharedBackupInterval.OFF -> return 0
            SharedBackupInterval.DAILY -> 24L * 60 * 60 * 1000
            SharedBackupInterval.WEEKLY -> 7L * 24 * 60 * 60 * 1000
        }
        val base = lastSuccess().takeIf { it > 0 } ?: now
        var next = base + period
        if (next <= now) next = now + 15 * 60 * 1000
        return next
    }
    companion object {
        private const val INTERVAL = "interval"
        private const val SUCCESS = "success"
        private const val ATTEMPT = "attempt"
    }
}

/** Writes zips under Download/WhaleTalk/backups and keeps the latest five. */
class SharedBackupStore(private val context: Context) {
    private val settings = SharedBackupSettings(context)

    fun interval() = settings.interval()
    fun setInterval(value: SharedBackupInterval) {
        settings.setInterval(value)
        SharedBackupScheduler.schedule(context)
    }
    fun isDue(now: Long = System.currentTimeMillis()) = settings.isDue(now)
    fun markAttempt(now: Long = System.currentTimeMillis()) = settings.markAttempt(now)
    fun markSuccess(now: Long = System.currentTimeMillis()) = settings.markSuccess(now)

    suspend fun publish(file: File, info: SharedBackupInfo): Uri = withContext(Dispatchers.IO) {
        val stamp = STAMP.format(Instant.ofEpochMilli(info.createdAt).atZone(ZoneId.systemDefault()))
        val kind = if (info.automatic) "auto-" else ""
        val name = "WhaleTalk-backup-$stamp-$kind${info.id}.zip"
        val uri = writePublic(file, name)
        runCatching { prune(name) }.onFailure {
            runCatching { RuntimeStore.log(context, LogCategory.MANAGER).append("共享备份已保存，但未能清理旧备份") }
        }
        uri
    }

    fun list(): List<SharedBackupItem> = if (Build.VERSION.SDK_INT >= 29) listMedia() else listLegacy()

    fun delete(item: SharedBackupItem) {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(item.uri, null, null)
        else File(item.uri.path ?: return).delete()
    }

    private fun prune(publishedName: String) {
        val existing = list().filterNot { it.name == publishedName }
        val expired = SharedBackupPolicy.idsToDelete(existing.map { it.id to it.createdAt }, SharedBackupPolicy.KEEP - 1).toSet()
        existing.filter { it.id in expired }.forEach { delete(it) }
    }

    private suspend fun writePublic(file: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "无法在下载目录创建备份" }
            try {
                resolver.openOutputStream(uri, "w").use { output ->
                    copyBackup(file, requireNotNull(output) { "无法写入下载目录" })
                }
                currentCoroutineContext().ensureActive()
                val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                check(resolver.update(uri, ready, null, null) == 1) { "无法发布下载目录中的备份" }
                return uri
            } catch (failure: Throwable) {
                runCatching { resolver.delete(uri, null, null) }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }
        val dir = legacyDir()
        check(dir.exists() || dir.mkdirs()) { "无法创建 Download/WhaleTalk/backups" }
        val target = File(dir, name)
        val pending = File.createTempFile(".whaletalk-backup-", ".part", dir)
        try {
            FileOutputStream(pending).use { output ->
                copyBackup(file, output)
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return Uri.fromFile(target)
        } finally {
            pending.delete()
        }
    }

    private suspend fun copyBackup(file: File, output: OutputStream) {
        val buffer = ByteArray(128 * 1024)
        val coroutine = currentCoroutineContext()
        file.inputStream().use { input ->
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        output.flush()
    }

    private fun listMedia(): List<SharedBackupItem> {
        val items = mutableListOf<SharedBackupItem>()
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.VOLUME_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.IS_PENDING} = 0", arrayOf(RELATIVE),
            "${MediaStore.Downloads.DATE_ADDED} DESC, ${MediaStore.Downloads._ID} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val volume = cursor.getColumnIndexOrThrow(MediaStore.Downloads.VOLUME_NAME)
            while (cursor.moveToNext()) {
                val display = cursor.getString(name) ?: continue
                if (!display.startsWith("WhaleTalk-backup-") || !display.endsWith(".zip")) continue
                val uri = Uri.withAppendedPath(MediaStore.Downloads.getContentUri(cursor.getString(volume)), cursor.getLong(id).toString())
                items += SharedBackupItem(uri.toString(), display, cursor.getLong(added) * 1000, cursor.getLong(size), uri, display.contains("-auto-"))
            }
        }
        return items
    }

    private fun listLegacy(): List<SharedBackupItem> = legacyDir().listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("WhaleTalk-backup-") && it.name.endsWith(".zip") }
        .sortedByDescending { it.lastModified() }
        .map { SharedBackupItem(it.name, it.name, it.lastModified(), it.length(), Uri.fromFile(it), it.name.contains("-auto-")) }

    private fun legacyDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WhaleTalk/backups")

    companion object {
        const val RELATIVE = "Download/WhaleTalk/backups/"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }
}

object SharedBackupScheduler {
    private const val ACTION = "dev.whaletalk.android.SHARED_BACKUP"
    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pending(context)
        val settings = SharedBackupSettings(context)
        if (settings.interval() == SharedBackupInterval.OFF) {
            alarm.cancel(pending)
            return
        }
        val next = settings.nextAt(System.currentTimeMillis())
        alarm.set(AlarmManager.RTC_WAKEUP, next, pending)
    }
    fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 41, Intent(context, SharedBackupReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class SharedBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.whaletalk.android.SHARED_BACKUP") return
        val settings = SharedBackupSettings(context)
        if (settings.isDue(System.currentTimeMillis())) {
            runCatching { RuntimeService.command(context, "shared-backup", backupId = "automatic") }
        }
        SharedBackupScheduler.schedule(context)
    }
}
