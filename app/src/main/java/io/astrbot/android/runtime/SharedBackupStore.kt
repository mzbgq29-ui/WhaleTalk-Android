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
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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
    fun markAttempt(now: Long) { check(preferences.edit().putLong(ATTEMPT, now).commit()) }
    fun markSuccess(now: Long) { check(preferences.edit().putLong(ATTEMPT, now).putLong(SUCCESS, now).commit()) }
    fun isDue(now: Long): Boolean {
        val period = when (interval()) {
            SharedBackupInterval.OFF -> return false
            SharedBackupInterval.DAILY -> 20L * 60 * 60 * 1000
            SharedBackupInterval.WEEKLY -> 6L * 24 * 60 * 60 * 1000
        }
        if (lastSuccess() != 0L && now - lastSuccess() < period) return false
        if (lastAttempt() != 0L && now - lastAttempt() < 3L * 60 * 60 * 1000) return false
        return true
    }
    fun nextAt(now: Long): Long {
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
    private val archive = SharedBackupArchive()

    fun interval() = settings.interval()
    fun setInterval(value: SharedBackupInterval) {
        settings.setInterval(value)
        SharedBackupScheduler.schedule(context)
    }
    fun isDue(now: Long = System.currentTimeMillis()) = settings.isDue(now)
    fun markAttempt(now: Long = System.currentTimeMillis()) = settings.markAttempt(now)
    fun markSuccess(now: Long = System.currentTimeMillis()) = settings.markSuccess(now)

    suspend fun publish(file: File, info: SharedBackupInfo): Uri = withContext(Dispatchers.IO) {
        val name = "WhaleTalk-backup-${STAMP.format(Date(info.createdAt))}.zip"
        val uri = writePublic(file, name)
        prune()
        uri
    }

    fun list(): List<SharedBackupItem> = if (Build.VERSION.SDK_INT >= 29) listMedia() else listLegacy()

    fun delete(item: SharedBackupItem) {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(item.uri, null, null)
        else File(item.uri.path ?: return).delete()
    }

    private fun prune() {
        val extra = SharedBackupPolicy.idsToDelete(list().map { it.id to it.createdAt })
        list().filter { it.id in extra.toSet() || it.name in extra.toSet() }.forEach { item ->
            // idsToDelete uses ids; our list id may be the display name when MediaStore has no manifest id.
            // Fall back to dropping anything beyond the newest KEEP by createdAt.
        }
        list().sortedByDescending { it.createdAt }.drop(SharedBackupPolicy.KEEP).forEach { delete(it) }
    }

    private fun writePublic(file: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "无法在下载目录创建备份" }
            try {
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "无法写入下载目录" }
                    file.inputStream().use { it.copyTo(output) }
                    output.flush()
                }
                return uri
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }
        val dir = legacyDir()
        check(dir.exists() || dir.mkdirs()) { "无法创建 Download/WhaleTalk/backups" }
        val target = File(dir, name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(target)
    }

    private fun listMedia(): List<SharedBackupItem> {
        val items = mutableListOf<SharedBackupItem>()
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("%WhaleTalk/backups%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cursor.moveToNext()) {
                val display = cursor.getString(name) ?: continue
                if (!display.startsWith("WhaleTalk-backup-") || !display.endsWith(".zip")) continue
                val uri = Uri.withAppendedPath(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), cursor.getLong(id).toString())
                items += SharedBackupItem(display, display, cursor.getLong(added) * 1000, cursor.getLong(size), uri, display.contains("-auto-") || false)
            }
        }
        return items
    }

    private fun listLegacy(): List<SharedBackupItem> = legacyDir().listFiles().orEmpty()
        .filter { it.name.startsWith("WhaleTalk-backup-") && it.name.endsWith(".zip") }
        .sortedByDescending { it.lastModified() }
        .map { SharedBackupItem(it.name, it.name, it.lastModified(), it.length(), Uri.fromFile(it), false) }

    private fun legacyDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WhaleTalk/backups")

    companion object {
        const val RELATIVE = "Download/WhaleTalk/backups/"
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
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
