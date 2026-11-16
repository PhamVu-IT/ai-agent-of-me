package vn.phamvu.trolyai.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.ChatViewModel
import vn.phamvu.trolyai.ModelCard
import vn.phamvu.trolyai.agent.ToolOutput
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.media.ChatMedia
import vn.phamvu.trolyai.media.MessageMedia
import kotlin.math.PI
import kotlin.math.sin

/**
 * Màn hình chat theo kiểu Google AI Edge Gallery: thanh tiêu đề gọn, bong bóng tin nhắn có "đuôi", ba chấm
 * "đang gõ…" khi chờ chữ đầu tiên, câu trả lời hiện dần từng chữ, nút gửi biến thành nút dừng ■ khi trợ lý đang viết,
 * giữ lâu để chép. Số liệu tốc độ nằm ở màn 📊 Thống kê riêng.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onMic: () -> Unit,
    onSpeak: (String) -> Unit,
    onVoiceSetup: () -> Unit,
    onBankSetup: () -> Unit,
    onInterview: () -> Unit,
    onPickModel: () -> Unit,
    onModels: () -> Unit,
    onStats: () -> Unit,
    onSkills: () -> Unit,
    onSettings: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val progress = vm.importProgress.value
    // Chép file vài GB mất vài phút: giữ màn hình sáng để máy không ngủ giữa chừng
    val view = LocalView.current
    DisposableEffect(progress != null) {
        view.keepScreenOn = progress != null
        onDispose { view.keepScreenOn = false }
    }
    // Mỗi câu trả lời chỉ đọc một lần: đọc xong thì đánh dấu, tin cũ nạp lại khi mở app không được đọc
    val speak = vm.toSpeak.value
    LaunchedEffect(speak?.id) {
        speak?.let { onSpeak(it.text); vm.markSpoken(it) }
    }

    // Danh sách xếp ngược: phần tử 0 nằm sát ô nhập. Câu đang viết dở là phần tử 0 nên khi nó dài ra,
    // màn hình tự bám theo dòng cuối như Gallery, không phải cuộn tay.
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val thinking = vm.thinking.value
    LaunchedEffect(vm.messages.size, thinking) {
        if (vm.messages.isNotEmpty()) list.animateScrollToItem(0)
    }
    val reversed = vm.messages.asReversed()
    val name = vm.assistantName.value

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().imePadding()) {
        ChatTopBar(
            name = name,
            subtitle = when {
                !vm.modelReady.value -> "Chưa có mô hình AI"
                vm.status.value.isNotEmpty() && !thinking -> vm.status.value
                vm.backendLabel.value.isNotEmpty() -> "Sẵn sàng · ${vm.backendLabel.value}"
                else -> "Đang chuẩn bị mô hình…"
            },
            ready = vm.modelReady.value && vm.backendLabel.value.isNotEmpty(),
            onVoiceSetup = onVoiceSetup,
            onBankSetup = onBankSetup,
            onInterview = onInterview,
            onModels = onModels,
            onStats = onStats,
            onSkills = onSkills,
            onSettings = onSettings,
        )
        if (!vm.modelReady.value || progress != null || vm.importNote.value.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 12.dp)) {
                ModelCard(
                    ready = vm.modelReady.value,
                    progress = progress,
                    loading = vm.importLoading.value,
                    note = vm.importNote.value,
                    onPick = onPickModel,
                    onCancel = vm::cancelImport,
                    onDismiss = { vm.importNote.value = "" },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                if (thinking) item(key = "dang-viet") {
                    StreamingMessage(
                        name = name,
                        showSender = vm.messages.lastOrNull()?.fromUser != false,
                        text = vm.partial.value,
                        thought = vm.thought.value,
                        status = vm.status.value,
                    )
                }
                items(reversed.size, key = { reversed[it].id }) { i ->
                    val m = reversed[i]
                    // Phần tử kế tiếp trong danh sách ngược là tin nhắn ngay trước tin này
                    val prev = reversed.getOrNull(i + 1)
                    if (m.fromUser) UserMessage(m, vm.media[m.id], onPlay = vm::play)
                    else AgentMessage(
                        m = m,
                        name = name,
                        showSender = prev == null || prev.fromUser,
                        tools = vm.toolOutputs[m.id].orEmpty(),
                        thought = vm.thoughts[m.id],
                        onRate = { r -> vm.rate(m, r) },
                        onSpeak = { onSpeak(m.text) },
                    )
                }
            }
            // Đang cuộn lên đọc tin cũ: nút nhỏ để về cuối cuộc trò chuyện
            val away by remember { derivedStateOf { list.firstVisibleItemIndex > 1 } }
            AnimatedVisibility(away, Modifier.align(Alignment.BottomCenter).padding(8.dp), enter = fadeIn(), exit = fadeOut()) {
                SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) { Text("↓") }
            }
        }
        // Gợi ý câu hỏi khi mới làm quen, như các mẫu câu của Gallery
        if (vm.messages.size <= 2 && input.isEmpty() && !thinking) Suggestions { vm.send(it) }
        if (vm.mediaNote.value.isNotEmpty()) Text(vm.mediaNote.value, Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        // Ảnh / ghi âm đã đính kèm, chờ gửi
        val audio = vm.attachedAudio.value
        if (vm.attachedImages.isNotEmpty() || audio != null) AttachmentRow(
            images = vm.attachedImages,
            audio = audio,
            onRemoveImage = vm::removeImage,
            onRemoveAudio = vm::removeAudio,
            onPlay = vm::play,
        )
        if (vm.recording.value) RecordingPanel(
            ms = vm.recordMs.longValue,
            level = vm.recordLevel.floatValue,
            onCancel = { vm.finishRecording(cancel = true) },
            onDone = { vm.finishRecording(cancel = false) },
        ) else ChatInput(
            value = input,
            onValueChange = { input = it },
            thinking = thinking,
            hasAttachment = vm.attachedImages.isNotEmpty() || audio != null,
            onMic = onMic,
            onPickImage = onPickImage,
            onTakePhoto = onTakePhoto,
            onRecord = vm::startRecording,
            onSend = { vm.send(input); input = "" },
            onStop = vm::stop,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    name: String,
    subtitle: String,
    ready: Boolean,
    onVoiceSetup: () -> Unit,
    onBankSetup: () -> Unit,
    onInterview: () -> Unit,
    onModels: () -> Unit,
    onStats: () -> Unit,
    onSkills: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (ready) Color(0xFF34A853) else MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Số liệu tốc độ để riêng một màn, không chen vào màn chat
            TextButton(onClick = onStats) { Text("📊") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("📊 Thống kê") }, onClick = { menu = false; onStats() })
                    DropdownMenuItem(text = { Text("🧩 Skill & MCP") }, onClick = { menu = false; onSkills() })
                    DropdownMenuItem(text = { Text("🧠 Mô hình") }, onClick = { menu = false; onModels() })
                    DropdownMenuItem(text = { Text("⚙️ Cài đặt") }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text("🎙️ Giọng của tôi") }, onClick = { menu = false; onVoiceSetup() })
                    DropdownMenuItem(text = { Text("🏦 Đọc biến động số dư") }, onClick = { menu = false; onBankSetup() })
                    DropdownMenuItem(text = { Text("🎧 Phỏng vấn") }, onClick = { menu = false; onInterview() })
                }
            }
        }
    }
}

/** Hình tròn có chữ cái đầu của tên trợ lý, thay cho ảnh đại diện. */
@Composable
private fun Avatar(name: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "✨",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = (sizeDp * 0.45f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SenderLabel(name: String) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, 22)
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(m: ChatMessage, media: MessageMedia?, onPlay: (ByteArray) -> Unit) {
    val copy = rememberCopy()
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Ảnh đã gửi (chỉ còn trong lần mở app này), xếp ngang như Gallery
        if (media != null && media.images.isNotEmpty()) Row(
            Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            media.images.forEach { bmp ->
                Image(bmp.asImageBitmap(), null, Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
            }
        }
        media?.audioPcm?.let { pcm -> AudioChip(pcm, onPlay = { onPlay(pcm) }) }
        Surface(
            shape = MessageBubbleShape(radius = 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { copy(m.text) }),
        ) {
            Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ToolResults(tools: List<ToolOutput>) {
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tools.map { it.label }.distinct().forEach { label ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        tools.forEach { t ->
            t.imageBase64?.let { b64 ->
                val bmp = remember(b64) { decodeBase64Image(b64) }
                if (bmp != null) Image(bmp.asImageBitmap(), null,
                    Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            }
            t.webviewUrl?.let { url -> SkillWebView(url, t.webviewIframe, t.aspectRatio) }
        }
    }
}

private fun decodeBase64Image(b64: String): Bitmap? = runCatching {
    val bytes = android.util.Base64.decode(b64.substringAfter("base64,"), android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/** Khung web nhỏ trong bong bóng chat (bản đồ, bảng của skill). [iframe] = true thì bọc trong iframe như Gallery. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SkillWebView(url: String, iframe: Boolean, aspectRatio: Float) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                webViewClient = android.webkit.WebViewClient()
                if (iframe) loadDataWithBaseURL("https://localhost/", "<html><body style='margin:0'>" +
                    "<iframe src='${url.replace("'", "%27")}' style='border:0;width:100%;height:100vh' allowfullscreen></iframe>" +
                    "</body></html>", "text/html", "utf-8", null)
                else loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio.coerceIn(0.5f, 3f)).clip(RoundedCornerShape(12.dp)),
    )
}

/** Đoạn ghi âm: 🎙️ độ dài, bấm ▶ để nghe lại. */
@Composable
private fun AudioChip(pcm: ByteArray, onPlay: () -> Unit, onRemove: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallAction("▶", onPlay)
            Text("🎙️ ${clock(ChatMedia.durationMs(pcm))}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (onRemove != null) SmallAction("✕", onRemove)
        }
    }
}

/** Hàng ảnh và ghi âm đính kèm trên ô nhập, mỗi thứ có nút ✕ để bỏ. */
@Composable
private fun AttachmentRow(
    images: List<Bitmap>,
    audio: ByteArray?,
    onRemoveImage: (Int) -> Unit,
    onRemoveAudio: () -> Unit,
    onPlay: (ByteArray) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        images.forEachIndexed { i, bmp ->
            Box {
                Image(bmp.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)).clickable { onRemoveImage(i) },
                    contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 12.sp) }
            }
        }
        if (audio != null) AudioChip(audio, onPlay = { onPlay(audio) }, onRemove = onRemoveAudio)
    }
}

/** Đang thu âm để gửi cho mô hình: chấm đỏ, thời gian, thanh âm lượng, nút Huỷ / Xong. */
@Composable
private fun RecordingPanel(ms: Long, level: Float, onCancel: () -> Unit, onDone: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = rememberInfiniteTransition(label = "rec")
            val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
            Box(Modifier.size(12.dp).alpha(a).clip(CircleShape).background(Color(0xFFE53935)))
            Spacer(Modifier.width(10.dp))
            Text("${clock(ms)} / ${clock(ChatMedia.MAX_AUDIO_SEC * 1000L)}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
            LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Huỷ") }
            Button(onClick = onDone) { Text("Xong") }
        }
    }
}

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentMessage(
    m: ChatMessage,
    name: String,
    showSender: Boolean,
    tools: List<ToolOutput>,
    thought: String?,
    onRate: (Int) -> Unit,
    onSpeak: () -> Unit,
) {
    val copy = rememberCopy()
    Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        if (showSender) SenderLabel(name)
        // Phần suy nghĩ (nếu bật chế độ suy nghĩ): thu gọn, bấm để mở xem
        if (!thought.isNullOrBlank()) ThoughtPanel(thought, live = false)
        // Skill / công cụ đã dùng: nhãn nhỏ, ảnh (ví dụ mã QR) và khung web (ví dụ bản đồ), như Gallery
        if (tools.isNotEmpty()) ToolResults(tools)
        AgentBubble(Modifier.combinedClickable(onClick = {}, onLongClick = { copy(m.text) })) {
            Text(richText(m.text), style = MaterialTheme.typography.bodyLarge)
        }
        // Chấm câu trả lời để trợ lý học: 👎 rồi nói chỗ sai, trợ lý ghi thành quy tắc
        if (m.id > 0) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallAction(if (m.rating > 0) "👍✓" else "👍") { onRate(1) }
            SmallAction(if (m.rating < 0) "👎✓" else "👎") { onRate(-1) }
            SmallAction("📋") { copy(m.text) }
            SmallAction("🔊", onSpeak)
        }
    }
}

/** Câu trả lời đang viết: ba chấm nhấp nháy khi chưa có chữ, sau đó chữ hiện dần. */
@Composable
private fun StreamingMessage(name: String, showSender: Boolean, text: String, thought: String, status: String) {
    Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        if (showSender) SenderLabel(name)
        if (thought.isNotBlank()) ThoughtPanel(thought, live = text.isBlank())
        AgentBubble {
            if (text.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                TypingDots()
                if (status.isNotEmpty()) Text(status, Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(richText(text), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Phần "suy nghĩ" của mô hình, như Gallery: lúc đang nghĩ thì mở sẵn và hiện dần, nghĩ xong thì thu gọn
 * thành một dòng "💭 Suy nghĩ", bấm để mở ra xem.
 */
@Composable
private fun ThoughtPanel(text: String, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    val show = live || open
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.padding(bottom = 4.dp).clickable(enabled = !live) { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(if (live) "💭 Đang suy nghĩ…" else if (open) "💭 Suy nghĩ ▴" else "💭 Suy nghĩ ▾",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (show) Text(
                // Đang nghĩ thì chỉ hiện đoạn cuối cho gọn
                if (live && text.length > 600) "…" + text.takeLast(600) else text,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentBubble(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MessageBubbleShape(radius = 18.dp, hardCornerAtLeftOrRight = true),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

/** Ba chấm lần lượt sáng lên, như "đang gõ…" của các ứng dụng nhắn tin. */
@Composable
fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)) {
        repeat(3) { i ->
            // Mỗi chấm lệch pha một phần ba vòng
            val wave = ((sin(2 * PI * (phase - i / 3.0)) + 1) / 2).toFloat()
            Box(
                Modifier.size(8.dp)
                    .graphicsLayer { translationY = -4f * wave * density; alpha = 0.35f + 0.65f * wave }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 4.dp), fontSize = 14.sp)
}

@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    val items = listOf(
        "Hôm nay là thứ mấy?",
        "Tháng này mình còn khoản nào chưa trả?",
        "Nhắc mình 8 giờ sáng mai uống thuốc",
        "Kể mình nghe một chuyện vui",
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { s -> SuggestionChip(onClick = { onPick(s) }, label = { Text(s) }) }
    }
}

/**
 * Ô nhập kiểu Gallery: khung bo tròn chứa nút 🎤 và chỗ gõ, bên phải là nút tròn gửi.
 * Khi trợ lý đang viết, nút gửi thành nút dừng ■.
 */
@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    thinking: Boolean,
    hasAttachment: Boolean,
    onMic: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecord: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Nút ＋ như Gallery: gửi ảnh hoặc ghi âm cho mô hình xem / nghe
                Box(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !thinking) { menu = true },
                        contentAlignment = Alignment.Center) {
                        Text("＋", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("📷 Chụp ảnh") }, onClick = { menu = false; onTakePhoto() })
                        DropdownMenuItem(text = { Text("🖼️ Chọn ảnh") }, onClick = { menu = false; onPickImage() })
                        DropdownMenuItem(text = { Text("🎙️ Ghi âm gửi cho AI nghe") }, onClick = { menu = false; onRecord() })
                    }
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (hasAttachment) "Hỏi gì về ảnh / ghi âm…" else "Nhắn cho trợ lý…") },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                // 🎤 là nói để gõ chữ (nghe tiếng Việt offline), khác với ghi âm gửi cho mô hình
                Box(Modifier.padding(4.dp).size(44.dp).clip(CircleShape).clickable(onClick = onMic),
                    contentAlignment = Alignment.Center) { Text("🎤", fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.width(8.dp))
        val canSend = value.isNotBlank() || hasAttachment
        val bg = if (thinking || canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
        val fg = if (thinking || canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            Modifier.padding(bottom = 4.dp).size(48.dp).clip(CircleShape).background(bg)
                .clickable(enabled = thinking || canSend) { if (thinking) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            if (thinking) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(fg))
            else Text("➤", color = fg, fontSize = 20.sp, modifier = Modifier.alpha(if (canSend) 1f else 0.6f))
        }
    }
}

/** Giữ lâu hoặc bấm 📋 để chép chữ, như Gallery. */
@Composable
private fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    return remember(clipboard, ctx) {
        { text: String ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(ctx, "Đã chép", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Hiện vài kiểu Markdown đơn giản mô hình hay viết: **đậm**, `mã`, và dòng bắt đầu bằng "- " / "* " thành dấu chấm.
 * Không cần thêm thư viện.
 */
internal fun richText(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { li, raw ->
        if (li > 0) append('\n')
        var line = raw
        val bullet = Regex("^(\\s*)[-*] ").find(line)
        if (bullet != null) {
            append(bullet.groupValues[1])
            append("• ")
            line = line.substring(bullet.range.last + 1)
        }
        line = line.removePrefix("### ").removePrefix("## ").removePrefix("# ")
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) && line.indexOf("**", i + 2) > i + 2 -> {
                    val end = line.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line, i + 2, end) }
                    i = end + 2
                }
                line[i] == '`' && line.indexOf('`', i + 1) > i + 1 -> {
                    val end = line.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(line, i + 1, end) }
                    i = end + 1
                }
                else -> { append(line[i]); i++ }
            }
        }
    }
}
