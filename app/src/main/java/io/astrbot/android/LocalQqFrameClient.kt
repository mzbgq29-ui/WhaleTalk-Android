// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.astrbot.android.core.LoopbackRfbClient
import io.astrbot.android.core.RfbStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

internal data class QqFrameState(val bitmap: Bitmap? = null, val message: String = "正在连接本机 QQ 画面…")

/** Bitmap allocation stays on the reader thread; only publication touches the main looper. */
internal class LocalQqFrameClient(password: () -> String) {
    private val mutable = MutableStateFlow(QqFrameState())
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val publicationLock = Any()
    private val pending = AtomicReference<QqFrameState?>(null)
    @Volatile private var closed = false
    private val deliver = Runnable {
        val latest = pending.getAndSet(null)
        if (!closed && latest != null) mutable.value = latest
    }
    private val connection = LoopbackRfbClient(password, onStatus = { status ->
        publish(QqFrameState(message = when (status) {
            RfbStatus.CONNECTING -> "正在连接本机 QQ 画面…"
            RfbStatus.CONNECTED -> "已连接本机桌面，正在等待画面…"
            RfbStatus.RETRYING -> "正在重新连接本机画面…"
            RfbStatus.UNAVAILABLE -> "本机画面未能连接，请重新启动 QQ 服务或刷新"
            RfbStatus.AUTH_REJECTED -> "本机画面认证失败，请重新启动 QQ 服务"
            RfbStatus.NO_FRAME -> "已连上桌面，但 60 秒内没有收到画面，可能 QQ 窗口尚未出现。请重新启动 QQ 服务或刷新"
        }))
    }, onFrame = { pixels, width, height ->
        if (!closed) publish(QqFrameState(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), "已显示本机 QQ 桌面，轻点画面操作。"))
    })
    private fun publish(value: QqFrameState) {
        synchronized(publicationLock) {
            if (!closed) {
                pending.set(value)
                main.removeCallbacks(deliver)
                main.post(deliver)
            }
        }
    }
    fun start() { if (!closed) connection.start() }
    fun click(x: Int, y: Int) = connection.click(x, y)
    fun pointer(x: Int, y: Int, mask: Int) = connection.pointer(x, y, mask)
    fun text(value: String) = connection.text(value)
    fun enter() = connection.enter()
    fun backspace() = connection.backspace()
    fun close() {
        synchronized(publicationLock) { closed = true; pending.set(null); main.removeCallbacksAndMessages(null) }
        connection.close()
    }
}
