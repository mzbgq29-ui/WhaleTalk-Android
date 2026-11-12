// SPDX-License-Identifier: MIT
package io.astrbot.android.runtime

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.webkit.WebView
import io.astrbot.android.BuildConfig
import java.util.Locale

/** Explicit, read-only inspection. Does not start guest processes, query QQ or call a cloud model. */
internal object BigFishDeviceReport {
    fun read(context: Context): String {
        fun gib(bytes: Long) = String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val network = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val view = runCatching { WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } }.getOrNull()
        val power = context.getSystemService(PowerManager::class.java)
        return buildString {
            appendLine("大肥鱼 ${BuildConfig.VERSION_NAME} · 设备自检")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("系统架构：${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("ARM64 运行环境：${if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "架构匹配" else "不支持此设备架构"}")
            appendLine("系统页大小：${runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1)} 字节")
            appendLine("可用存储：${gib(context.filesDir.usableSpace)}（建议预留 6 GB）")
            appendLine("内存：可用 ${gib(memory.availMem)} / 总计 ${gib(memory.totalMem)}${if (memory.lowMemory) " · 系统报告内存不足" else ""}")
            appendLine("WebView：${view ?: "不可用，请更新或启用系统 WebView"}")
            appendLine("网络：${if (online) "系统已验证可联网" else "未确认互联网连接，请检查网络"}")
            appendLine("通知：${if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "已允许" else "未允许"}")
            appendLine("电池优化豁免：${if (power.isIgnoringBatteryOptimizations(context.packageName)) "已开启" else "未开启，可在后台设置中调整"}")
            appendLine("本版默认：不启用语音或本地大模型")
            appendLine("玻璃渲染：${if (Build.VERSION.SDK_INT >= 33) "支持 AGSL 折射" else if (Build.VERSION.SDK_INT >= 31) "支持背景模糊" else "兼容玻璃显示"}")
            appendLine("\n这里只检查设备条件，不代表 QQ 已登录或云端模型已通过验证。报告不包含 QQ 号、密钥、登录资料或聊天记录。")
        }
    }
}
