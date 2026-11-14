// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.astrbot.android.R
import io.astrbot.android.core.OneBotState
import io.astrbot.android.core.QqState

/** Optional alerts when QQ or the message channel drops after a confirmed session. */
class ConnectionAlerts(private val context: Context) {
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)

    fun enabled(): Boolean = preferences.getBoolean(PREFERENCE, true)

    fun setEnabled(value: Boolean) {
        check(preferences.edit().putBoolean(PREFERENCE, value).commit()) { "掉线通知设置未能保存" }
    }

    fun maybeNotify(previousQq: QqState, nextQq: QqState, previousOneBot: OneBotState, nextOneBot: OneBotState) {
        if (!enabled()) return
        if (previousQq == QqState.ONLINE && nextQq != QqState.ONLINE && nextQq != QqState.UNKNOWN) {
            notify("QQ 已掉线", "鲸语检测到 QQ 账号已离线，可打开应用查看登录画面。")
        }
        if (previousOneBot == OneBotState.CONNECTED && nextOneBot != OneBotState.CONNECTED && nextOneBot != OneBotState.UNKNOWN) {
            notify("消息通道已断开", "鲸语与 QQ 的消息通道已断开，可在应用中查看连接状态或重新启动服务。")
        }
    }

    private fun notify(title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "连接提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val PREFERENCE = "disconnect_alerts"
        private const val CHANNEL = "connection-alerts"
        private const val NOTIFICATION_ID = 1040
    }
}
