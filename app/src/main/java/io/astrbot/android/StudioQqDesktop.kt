// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.positionChange
import io.astrbot.android.core.RfbViewport
import io.astrbot.android.runtime.QqKnownAccounts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.astrbot.android.runtime.DesktopRepository
import kotlin.math.roundToInt

/** Native Android bitmap display. The QQ process and credentials stay on loopback. */
@Composable internal fun StudioQqDesktop(modifier: Modifier = Modifier, passwordReader: (() -> String)? = null, initiallyEnlarged: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var fit by remember { mutableStateOf(!initiallyEnlarged) }
    var keyboard by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val client = remember(revision) { LocalQqFrameClient(passwordReader ?: { DesktopRepository(context).password() }) }
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var pendingSnapshot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrText by remember { mutableStateOf<String?>(null) }
    var qrImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrFirstSeen by remember { mutableLongStateOf(0L) }
    var qrLastSeen by remember { mutableLongStateOf(0L) }
    var qrNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var accounts by remember { mutableStateOf(emptyList<io.astrbot.android.runtime.QqKnownAccount>()) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val image = pendingSnapshot; pendingSnapshot = null
        if (uri != null && image != null) scope.launch {
            saving = true
            try { withContext(Dispatchers.IO) { StudioQqSnapshot.write(context, uri, image) }; savedMessage = "登录画面已保存，可在手机 QQ 扫一扫中选择这张图片" }
            catch (_: Exception) { savedMessage = "保存失败，请换个保存位置重试" }
            finally { saving = false }
        }
    }
    LaunchedEffect(state.bitmap == null) { if (state.bitmap == null) { keyboard = false; text = "" } }
    DisposableEffect(lifecycle, client) {
        var closed = false
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) client.start()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                closed = true; text = ""; keyboard = false; client.close()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                if (closed) { closed = false; revision++ } else client.start()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); client.close() }
    }
    LaunchedEffect(Unit) {
        accounts = withContext(Dispatchers.IO) { QqKnownAccounts.list(context) }
    }
    LaunchedEffect(client) {
        while (true) {
            val frame = client.state.value.bitmap
            if (frame != null) {
                val found = withContext(Dispatchers.Default) { QqLoginQr.decode(frame) }
                val now = System.currentTimeMillis()
                qrNow = now
                if (found != null && QqLoginQr.isLikelyLoginPayload(found)) {
                    if (found != qrText) {
                        qrText = found
                        qrFirstSeen = now
                        val previous = qrImage
                        qrImage = withContext(Dispatchers.Default) { QqLoginQr.encode(found) }
                        previous?.recycle()
                    }
                    qrLastSeen = now
                }
            }
            kotlinx.coroutines.delay(600)
        }
    }
    LaunchedEffect(qrText) {
        if (qrText == null) return@LaunchedEffect
        while (true) {
            qrNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.message + if (!fit) " 拖动画面移动；滑块验证先切换适应屏幕。" else "", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiquidOutlinedButton(onClick = { fit = !fit }, Modifier.weight(1f)) { Text(if (fit) "放大画面" else "适应屏幕") }
            LiquidOutlinedButton(onClick = { keyboard = true }, Modifier.weight(1f), enabled = state.bitmap != null) { Text("键盘") }
            LiquidOutlinedButton(onClick = { revision++; fit = true }, Modifier.weight(1f)) { Text("刷新") }
        }
        TextButton(onClick = {
            val image = state.bitmap ?: return@TextButton
            savedMessage = null
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { StudioQqSnapshot.gallery(context, image) }; savedMessage = "已保存到相册 Pictures/WhaleTalk，可在手机 QQ 扫一扫中选择这张图片" }
                    catch (_: Exception) { savedMessage = "相册保存失败，请重试或使用系统截图" }
                    finally { saving = false }
                }
            } else {
                pendingSnapshot = image
                saveDocument.launch("WhaleTalk-QQ-login.png")
            }
        }, enabled = state.bitmap != null && !saving && pendingSnapshot == null) { Text(if (saving) "正在保存…" else "保存登录画面") }
        savedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (accounts.isNotEmpty()) {
            Text("本机曾登录的账号", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text("SnowLuma 2.4.2 没有一键登录接口，请在下方 QQ 画面中点选快速登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            accounts.forEach { account ->
                Text(account.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        qrImage?.let { image ->
            val freshness = QqLoginQr.freshness(qrNow, qrFirstSeen, qrLastSeen)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("登录二维码", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Image(image.asImageBitmap(), "QQ 登录二维码",
                    Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White).padding(12.dp),
                    contentScale = ContentScale.Fit)
                Text(when (freshness) {
                    QrFreshness.STALE -> "二维码可能已过期，请点刷新或查看下方画面"
                    QrFreshness.LOST -> "画面里暂时看不到二维码，已显示上次内容；可刷新或使用下方登录画面"
                    else -> "用手机 QQ 扫描上方二维码。识别失败时请直接使用下方登录画面。"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Text("下方是完整的 QQ 登录画面，验证码、滑块和快速登录都在这里完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NativeQqFrame(state.bitmap, fit, client, Modifier.fillMaxWidth().weight(1f).testTag("qq-native-frame"))
    }
    if (keyboard) AlertDialog(onDismissRequest = { keyboard = false; text = "" }, title = { Text("向 QQ 输入") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先在 QQ 画面点选输入框，再在这里输入。")
            StudioField(text, { text = it.take(2048) }, "QQ 账号、密码或验证码", secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { client.backspace() }) { Text("退格") }
                TextButton(onClick = { if (text.isNotEmpty()) client.text(text); client.enter(); keyboard = false; text = "" }) { Text("回车") }
            }
        }
    }, confirmButton = { TextButton(onClick = { client.text(text); text = ""; keyboard = false }, enabled = text.isNotEmpty()) { Text("输入到 QQ") } },
        dismissButton = { TextButton(onClick = { keyboard = false; text = "" }) { Text("取消") } })
}

@Composable private fun NativeQqFrame(bitmap: android.graphics.Bitmap?, fit: Boolean, client: LocalQqFrameClient, modifier: Modifier) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var pan by remember(fit, bitmap?.width, bitmap?.height) { mutableStateOf<Offset?>(null) }
    val frame by rememberUpdatedState(bitmap)
    fun geometry(): RfbViewport? {
        val image = frame ?: return null
        if (viewport.width <= 0 || viewport.height <= 0) return null
        return RfbViewport(image.width, image.height, viewport.width, viewport.height, fit, pan?.x, pan?.y)
    }
    Canvas(modifier.semantics { contentDescription = if (bitmap == null) "正在连接 QQ 画面" else "QQ 登录桌面" }.onSizeChanged { viewport = it }
        .pointerInput(fit, client, bitmap?.width, bitmap?.height) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val initial = geometry()?.remote(down.position.x, down.position.y) ?: return@awaitEachGesture
                var last = initial
                var moved = false
                var released = false
                down.consume()
                if (fit) client.pointer(initial.first, initial.second, 1)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (!change.pressed) { released = true; change.consume(); break }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        val geometry = geometry() ?: break
                        if (fit) {
                            last = geometry.remote(change.position.x, change.position.y, clamp = true)!!
                            client.pointer(last.first, last.second, 1)
                        } else if (moved) {
                            val delta = change.positionChange()
                            pan = Offset(geometry.sourceX - delta.x / geometry.scale, geometry.sourceY - delta.y / geometry.scale)
                        }
                        change.consume()
                    }
                } finally {
                    if (fit) client.pointer(last.first, last.second, 0)
                    else if (released && !moved) client.click(initial.first, initial.second)
                }
            }
        }) {
        drawRect(Color(0xFFEDF2F8))
        val image = bitmap ?: return@Canvas
        val g = geometry() ?: return@Canvas
        val scale = g.scale; val source = Offset(g.sourceX, g.sourceY); val offset = Offset(g.offsetX, g.offsetY)
        val w = minOf(image.width - source.x.toInt(), ((size.width - offset.x) / scale).roundToInt()).coerceAtLeast(1)
        val h = minOf(image.height - source.y.toInt(), ((size.height - offset.y) / scale).roundToInt()).coerceAtLeast(1)
        drawImage(image.asImageBitmap(), srcOffset = IntOffset(source.x.toInt(), source.y.toInt()), srcSize = IntSize(w, h),
            dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()), dstSize = IntSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.None)
    }
}
