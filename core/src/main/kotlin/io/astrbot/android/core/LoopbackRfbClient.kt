// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

enum class RfbStatus { CONNECTING, CONNECTED, RETRYING, UNAVAILABLE, AUTH_REJECTED, NO_FRAME }

/** Restricted adapter for our password-protected loopback x11vnc server.
 * RFB 3.3/3.7/3.8, VNC authentication, RAW rectangles and DesktopSize only.
 * Protocol reference: noVNC 1.3.0 core/rfb.js; no vendor implementation copied.
 */
class LoopbackRfbClient(
    private val password: () -> String,
    private val onStatus: (RfbStatus) -> Unit,
    // The array is borrowed only until this callback returns; a UI must copy it.
    private val onFrame: (IntArray, Int, Int) -> Unit,
    private val port: Int = 5920,
) {
    companion object { const val FIRST_FRAME_TIMEOUT_MS = 60_000L }
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outputLock = Any()
    private val lifecycleLock = Any()
    @Volatile private var socket: Socket? = null
    private class Binding(val socket: Socket, val stream: DataOutputStream) {
        @Volatile var ready = false
    }
    @Volatile private var binding: Binding? = null
    @Volatile private var closed = false
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var pixels = IntArray(0)
    private var rowBuffer = ByteArray(0)
    private sealed interface Input {
        data class Pointer(val x: Int, val y: Int, val mask: Int, val click: Boolean = false) : Input
        data class Text(val value: String) : Input
        data class Key(val value: Int) : Input
    }
    private data class Pending(val owner: Binding, val event: Input)
    private val inputs = Channel<Pending>(256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private class AuthRejected : Exception()
    private fun status(value: RfbStatus) { if (!closed) onStatus(value) }
    private fun enqueue(event: Input) { binding?.takeIf { it.ready || event is Input.Pointer && event.mask == 0 }?.let { inputs.trySend(Pending(it, event)) } }

    fun start() {
        if (closed || !started.compareAndSet(false, true)) return
        scope.launch {
            for (pending in inputs) {
                val owner = pending.owner
                if (binding !== owner || (!owner.ready && !(pending.event is Input.Pointer && pending.event.mask == 0))) continue
                val event = pending.event
                val stream = owner.stream
                runCatching { synchronized(outputLock) {
                    when (event) {
                        is Input.Pointer -> {
                            pointer(stream, event.x, event.y, event.mask)
                            if (event.click) pointer(stream, event.x, event.y, 0)
                        }
                        is Input.Text -> event.value.codePoints().forEach { key(stream, if (it <= 255) it else it or 0x01000000) }
                        is Input.Key -> key(stream, event.value)
                    }
                    stream.flush()
                } }.onFailure { runCatching { owner.socket.close() } }
            }
        }
        scope.launch {
            for (attempt in 0..59) {
                if (closed) break
                try { connect(); break }
                catch (_: AuthRejected) { status(RfbStatus.AUTH_REJECTED); break }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    status(if (attempt < 59) RfbStatus.RETRYING else RfbStatus.UNAVAILABLE)
                } finally { binding = null; runCatching { socket?.close() }; socket = null }
                if (!closed) delay(1000)
            }
        }
    }
    fun click(x: Int, y: Int) { enqueue(Input.Pointer(x, y, 1, true)) }
    fun pointer(x: Int, y: Int, mask: Int) { enqueue(Input.Pointer(x, y, mask)) }
    fun text(value: String) { if (value.length <= 2048) enqueue(Input.Text(value)) }
    fun enter() { enqueue(Input.Key(0xff0d)) }
    fun backspace() { enqueue(Input.Key(0xff08)) }
    fun close() {
        synchronized(lifecycleLock) { closed = true; binding = null; runCatching { socket?.close() }; socket = null }
        inputs.close(); scope.cancel()
    }

    private fun connect() {
        val connection = Socket()
        synchronized(lifecycleLock) {
            if (closed) { connection.close(); throw CancellationException("Closed") }
            socket = connection
        }
        connection.tcpNoDelay = true; connection.soTimeout = 3000
        connection.connect(InetSocketAddress("127.0.0.1", port), 3000)
        val input = DataInputStream(connection.getInputStream().buffered(128 * 1024))
        val stream = DataOutputStream(connection.getOutputStream().buffered(16 * 1024))
        val banner = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
        check(banner.startsWith("RFB 003."))
        val minor = banner.substring(8, 11).toInt()
        val version = if (minor >= 8) 8 else if (minor >= 7) 7 else 3
        stream.write("RFB 003.${version.toString().padStart(3, '0')}\n".toByteArray(Charsets.US_ASCII)); stream.flush()
        if (version == 3) check(input.readInt() == 2) else {
            val count = input.readUnsignedByte(); check(count in 1..64)
            val offered = ByteArray(count).also(input::readFully)
            check(offered.any { it.toInt() == 2 })
            stream.writeByte(2); stream.flush()
        }
        val passwordText = password()
        check(passwordText.length == 8 && passwordText.all { it.code in 1..127 })
        val secret = passwordText.toByteArray(Charsets.US_ASCII)
        check(secret.size == 8)
        val key = ByteArray(8) { index -> Integer.reverse(secret[index].toInt() and 255).ushr(24).toByte() }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        val challenge = ByteArray(16).also(input::readFully)
        stream.write(cipher.doFinal(challenge)); stream.flush()
        secret.fill(0); key.fill(0)
        if (input.readInt() != 0) throw AuthRejected()
        stream.writeByte(1); stream.flush() // Shared session; opening a view never stops QQ.
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.skipFully(16)
        val nameLength = input.readInt(); check(nameLength in 0..65536); input.skipFully(nameLength)
        // Prefer a fixed 32-bit little-endian true-colour pixel format.
        stream.writeByte(0); stream.write(ByteArray(3))
        stream.write(byteArrayOf(32, 24, 0, 1))
        repeat(3) { stream.writeShort(255) }
        stream.write(byteArrayOf(16, 8, 0, 0, 0, 0))
        stream.writeByte(2); stream.writeByte(0); stream.writeShort(3)
        stream.writeInt(0); stream.writeInt(-223); stream.writeInt(-224)
        stream.flush()
        val owner = Binding(connection, stream)
        synchronized(lifecycleLock) {
            if (closed) throw CancellationException("Closed")
            binding = owner
        }
        status(RfbStatus.CONNECTED)
        request(stream, false)
        val frameDeadline = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS
        while (!closed) {
            val type = try { input.readUnsignedByte() } catch (_: SocketTimeoutException) {
                if (!owner.ready && System.currentTimeMillis() >= frameDeadline) {
                    status(RfbStatus.NO_FRAME)
                    break
                }
                request(stream, true); continue
            }
            when (type) {
                0 -> {
                    input.readUnsignedByte(); val rectangles = input.readUnsignedShort()
                    check(rectangles <= 16384)
                    var changed = false
                    var resized = false
                    for (index in 0 until rectangles) {
                        val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
                        when (input.readInt()) {
                            -224 -> break
                            -223 -> {
                                resize(w, h); changed = false; resized = true; owner.ready = false
                                status(RfbStatus.CONNECTED)
                            }
                            0 -> {
                                check(w > 0 && h > 0 && x + w <= width && y + h <= height)
                                if (rowBuffer.size < w * 4) rowBuffer = ByteArray(w * 4)
                                val row = rowBuffer
                                for (dy in 0 until h) {
                                    input.readFully(row, 0, w * 4)
                                    for (dx in 0 until w) {
                                        val offset = dx * 4
                                        pixels[(y + dy) * width + x + dx] = (0xff000000.toInt() or
                                            ((row[offset + 2].toInt() and 255) shl 16) or
                                            ((row[offset + 1].toInt() and 255) shl 8) or (row[offset].toInt() and 255))
                                    }
                                }
                                changed = true
                            }
                            else -> error("Unexpected loopback framebuffer encoding")
                        }
                    }
                    if (changed && !closed) { owner.ready = true; onFrame(pixels, width, height) }
                    // Static login screens do not need video-rate framebuffer requests.
                    Thread.sleep(250)
                    request(stream, !resized)
                }
                2 -> Unit // Bell
                3 -> { input.skipFully(3); val size = input.readInt(); check(size in 0..1048576); input.skipFully(size) }
                else -> error("Unexpected loopback framebuffer message")
            }
        }
    }
    private fun resize(w: Int, h: Int) {
        check(w in 1..4096 && h in 1..4096 && w.toLong() * h <= 4194304)
        width = w; height = h; pixels = IntArray(w * h)
    }
    private fun request(stream: DataOutputStream, incremental: Boolean) = synchronized(outputLock) {
        stream.writeByte(3); stream.writeByte(if (incremental) 1 else 0)
        stream.writeShort(0); stream.writeShort(0); stream.writeShort(width); stream.writeShort(height); stream.flush()
    }
    private fun pointer(stream: DataOutputStream, x: Int, y: Int, mask: Int) {
        stream.writeByte(5); stream.writeByte(mask)
        stream.writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0))); stream.writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }
    private fun key(stream: DataOutputStream, value: Int) {
        for (down in listOf(1, 0)) { stream.writeByte(4); stream.writeByte(down); stream.writeShort(0); stream.writeInt(value) }
    }
    private fun DataInputStream.skipFully(size: Int) {
        var left = size
        while (left > 0) { val n = skipBytes(left); if (n == 0) { readByte(); left-- } else left -= n }
    }
}
