package vn.phamvu.trolyai.models

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn 📥 Tải mô hình (như danh sách mô hình của Google AI Edge Gallery) và 📏 Đo tốc độ (như Benchmark của Gallery).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelStoreScreen(vm: ModelStoreViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val dl = vm.download.value
    var showToken by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text("📥 Tải mô hình", style = MaterialTheme.typography.titleLarge)
            }
            Text("Tải thẳng từ Hugging Face, chạy ở nền (có thể tắt màn hình). Mất mạng thì tải tiếp từ chỗ dở. " +
                "Tải xong app tự thay mô hình đang dùng. Máy bạn có khoảng ${vm.deviceRamGb} GB RAM.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.note.value.isNotEmpty()) Text(vm.note.value, Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium)
        }

        items(CatalogModel.ALL, key = { it.name }) { m ->
            val active = dl?.name == m.name
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (vm.installed.value == m.name) AssistChip(onClick = {}, label = { Text("✓ Đang dùng") })
                    }
                    Text("${m.sizeMb} MB · cần ${m.minRamGb} GB RAM" +
                        if (m.minRamGb > vm.deviceRamGb) " ⚠️ máy bạn có thể không đủ" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (m.minRamGb > vm.deviceRamGb) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (m.image) Tag("📷 Ảnh")
                        if (m.audio) Tag("🎙️ Âm thanh")
                        if (m.thinking) Tag("💭 Suy nghĩ")
                        if (m.gated) Tag("🔑 Cần đăng nhập HF")
                    }
                    if (m.note.isNotEmpty()) Text(m.note, style = MaterialTheme.typography.bodySmall)
                    if (active && dl != null) {
                        if (dl.installing) {
                            Text("Đang cài và nạp mô hình…", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { dl.fraction }, Modifier.fillMaxWidth())
                            Text("${(dl.fraction * 100).toInt()}% · ${dl.done / 1_000_000}/${dl.total / 1_000_000} MB",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = vm::cancel, enabled = !dl.installing) { Text("■ Dừng") }
                    } else Row {
                        Button(onClick = { vm.start(m) }, enabled = dl == null) {
                            Text(if (vm.installed.value == m.name) "Tải lại" else "Tải về")
                        }
                        TextButton(onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.pageUrl)))
                        }) { Text("Trang mô hình") }
                    }
                }
            }
        }

        item {
            TextButton(onClick = { showToken = !showToken }) {
                Text(if (vm.token.value.isEmpty()) "🔑 Mã truy cập Hugging Face (cho mô hình cần đăng nhập)"
                else "🔑 Đã lưu mã truy cập Hugging Face")
            }
            if (showToken) {
                var t by remember { mutableStateOf(vm.token.value) }
                Text("Mô hình có 🔑: đăng nhập huggingface.co, mở \"Trang mô hình\" và bấm đồng ý điều khoản, rồi tạo mã ở " +
                    "Settings › Access Tokens (loại Read) và dán vào đây. Mã chỉ lưu trong máy, chỉ gửi tới huggingface.co.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(t, { t = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("hf_…") }, visualTransformation = PasswordVisualTransformation())
                    TextButton(onClick = { vm.saveToken(t); showToken = false }) { Text("Lưu") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- Đo tốc độ ----
        item {
            Text("📏 Đo tốc độ", style = MaterialTheme.typography.titleLarge)
            Text("Cho mô hình đang dùng tóm tắt một đoạn văn cố định 3 lần, đo thời gian đọc đề và tốc độ viết. " +
                "Đổi GPU / CPU ở ⚙️ Cài đặt rồi đo lại để so.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.runBenchmark() }, enabled = !vm.benchBusy.value && dl == null) { Text("Bắt đầu đo") }
                if (vm.benchResults.isNotEmpty()) TextButton(onClick = vm::clearBench) { Text("Xoá kết quả") }
            }
            if (vm.benchBusy.value) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (vm.benchStatus.value.isNotEmpty()) Text(vm.benchStatus.value, style = MaterialTheme.typography.bodyMedium)
        }
        items(vm.benchResults) { r -> BenchCard(r) }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BenchCard(r: BenchResult) {
    val time = remember(r.at) { SimpleDateFormat("HH:mm dd/MM", Locale("vi")).format(Date(r.at)) }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${r.model} · ${r.backend}", style = MaterialTheme.typography.titleSmall)
            Text(time + " · ${r.runs} lần", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Viết", "%.1f".format(r.decodeTps), "token/giây")
                Metric("Chữ đầu tiên", "%.1f".format(r.ttftMs / 1000f), "giây")
                Metric("Đọc đề", "%.0f".format(r.prefillCps), "ký tự/giây")
                if (r.loadMs > 0) Metric("Nạp", "%.1f".format(r.loadMs / 1000f), "giây")
            }
            Text("Tốc độ viết thấp nhất %.1f, cao nhất %.1f token/giây".format(r.decodeMin, r.decodeMax),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
