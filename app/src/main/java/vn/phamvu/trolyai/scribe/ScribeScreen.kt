package vn.phamvu.trolyai.scribe

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Màn 🎧 Chép lời (Audio Scribe của Gallery): chọn file hay thu âm, chọn chép lời hoặc dịch, rồi xem bản chép
 * hiện dần theo từng đoạn có mốc thời gian. Chép hoặc chia sẻ cả bản.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScribeScreen(vm: ScribeViewModel, onPickFile: () -> Unit, onBack: () -> Unit) {
    BackHandler { if (vm.busy.value || vm.recording.value) vm.cancel() else onBack() }
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val busy = vm.busy.value
    // Chép bản ghi dài mất vài phút: giữ màn hình sáng để máy không ngủ giữa chừng
    val view = LocalView.current
    val working = busy || vm.recording.value
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.cancel(); onBack() }) { Text("←") }
                Text("🎧 Chép lời", style = MaterialTheme.typography.titleLarge)
            }
            Text("Chép lời hoặc dịch bản ghi âm dài (cuộc họp, bài giảng, tin nhắn thoại). Chạy hoàn toàn trong máy, " +
                "âm thanh không gửi đi đâu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Nguồn âm thanh ----
        item {
            if (vm.recording.value) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔴 Đang thu âm ${ScribeViewModel.clock(vm.recordMs.longValue)}",
                            style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { vm.recordLevel.floatValue }, Modifier.fillMaxWidth())
                        Text("Tối đa ${ScribeViewModel.MAX_RECORD_SEC / 60} phút. Giữ màn hình mở trong lúc thu.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = vm::stopRecording) { Text("■ Dừng thu") }
                    }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile, enabled = !busy) { Text("📂 Chọn file") }
                OutlinedButton(onClick = vm::startRecording, enabled = !busy) { Text("🎙️ Thu âm") }
            }
            if (vm.hasAudio && !vm.recording.value) Text(
                "${vm.fileName.value} · ${ScribeViewModel.clock(vm.durationMs.longValue)}",
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }

        // ---- Cách làm ----
        if (vm.hasAudio && !vm.recording.value) item {
            Text("Muốn làm gì", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.languages.forEach { (key, label) ->
                    FilterChip(selected = vm.target.value == key, onClick = { vm.target.value = key },
                        label = { Text(label) }, enabled = !busy)
                }
            }
            val canAi = vm.aiCanListen.value
            Text("Ai nghe", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val engines = ScribeViewModel.Engine.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                engines.forEachIndexed { i, e ->
                    SegmentedButton(
                        selected = vm.engine.value == e,
                        onClick = { vm.engine.value = e },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(i, engines.size),
                    ) { Text(e.label) }
                }
            }
            Text(
                when (vm.engine.value) {
                    ScribeViewModel.Engine.AI -> when (canAi) {
                        null -> "Đang kiểm tra mô hình AI…"
                        true -> "Mô hình AI nghe trực tiếp, hiểu nhiều thứ tiếng và dịch được. Chậm hơn."
                        false -> "Mô hình AI đang dùng không nghe được âm thanh. Cần Gemma 3n / Gemma 4 dạng .litertlm."
                    }
                    ScribeViewModel.Engine.ASR -> if (vm.asrReady)
                        "Nhanh, chỉ nghe tiếng Việt. Muốn dịch thì AI dịch lại phần chữ."
                    else "Chưa có mô hình nghe tiếng Việt. Cài ở ⋮ › 🧠 Mô hình."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) OutlinedButton(onClick = vm::cancel) { Text("■ Dừng") }
                else Button(onClick = vm::start) { Text(if (vm.target.value.isEmpty()) "Bắt đầu chép lời" else "Bắt đầu dịch") }
            }
        }

        if (busy) item {
            LinearProgressIndicator(progress = { vm.progress.floatValue }, Modifier.fillMaxWidth())
            if (vm.status.value.isNotEmpty()) Text(vm.status.value, Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium)
        }
        if (vm.error.value.isNotEmpty()) item {
            Text(vm.error.value, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        // ---- Bản chép ----
        if (vm.segments.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bản chép", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    clip.setText(AnnotatedString(vm.fullText(withTime = false)))
                    Toast.makeText(ctx, "Đã chép", Toast.LENGTH_SHORT).show()
                }) { Text("📋 Chép") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, vm.fileName.value)
                        .putExtra(Intent.EXTRA_TEXT, vm.fullText())
                    ctx.startActivity(Intent.createChooser(send, "Chia sẻ bản chép"))
                }) { Text("↗ Chia sẻ") }
            }
        }
        items(vm.segments) { s ->
            Row {
                Text(ScribeViewModel.clock(s.startMs), Modifier.width(56.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        if (vm.live.value.isNotEmpty()) item {
            Text(vm.live.value, Modifier.padding(start = 56.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
