package vn.phamvu.trolyai.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.ChatViewModel
import vn.phamvu.trolyai.TurnStat
import vn.phamvu.trolyai.agent.Tier
import vn.phamvu.trolyai.llm.LiteRtLmRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn 📊 Thống kê: các số liệu mà Google AI Edge Gallery hiện ngay dưới khung chat (tốc độ token/giây, thời gian
 * tới chữ đầu, số token, bộ nhớ ngữ cảnh, RAM), ở đây gom vào một màn riêng, chỉ mở khi cần xem.
 * Số liệu chỉ tính trong lần mở app này.
 */
@Composable
fun StatsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val model = remember(refresh) { vm.modelInfo() }
    var ram by remember { mutableStateOf<Ram?>(null) }
    LaunchedEffect(refresh) { ram = withContext(Dispatchers.Default) { readRam(ctx) } }

    val all = vm.stats.toList()
    val llmTurns = all.filter { it.tier == Tier.LLM && it.gen != null }
    val last = llmTurns.firstOrNull()

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text("📊 Thống kê", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Text("Làm mới") }
            }
        }

        item {
            Section("Mô hình") {
                Line("File", model.file.ifEmpty { "Chưa có" })
                if (model.file.isNotEmpty()) Line("Dung lượng", "${model.sizeMb} MB")
                Line("Chạy bằng", if (model.loaded) model.backend else "Chưa nạp vào bộ nhớ")
                if (model.loaded) Line("Thời gian nạp", sec(model.loadMs))
                ram?.let {
                    Line("RAM app đang dùng", "${it.appMb} MB")
                    Line("RAM máy còn trống", "${it.freeMb} / ${it.totalMb} MB")
                }
            }
        }

        item {
            Section("Câu trả lời AI gần nhất") {
                if (last == null) {
                    Text("Chưa có. Hỏi trợ lý một câu cần AI suy nghĩ rồi quay lại đây.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val g = last.gen!!
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile("Tốc độ", tps(g.decodeTps), "token/giây", Modifier.weight(1f))
                        Tile("Chữ đầu", sec(g.ttftMs), "mô hình đọc + nghĩ", Modifier.weight(1f))
                        Tile("Token", "${g.tokens}", "đã sinh", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Line("Tổng thời gian (từ lúc gửi)", sec(last.totalMs))
                    Line("Chữ đầu hiện lên (từ lúc gửi)", sec(last.firstMs))
                    Line("Mô hình phải đọc", "${g.promptChars} ký tự" +
                        if (g.freshChat) " (cuộc trò chuyện mới)" else " (chỉ lời nhắn mới)")
                    if (last.loadMs > 0) Line("Phải nạp mô hình", sec(last.loadMs))
                    if (g.contextChars > 0) {
                        // Ước lượng: tiếng Việt khoảng 2,5 ký tự một token
                        val used = (g.contextChars / 2.5).toInt()
                        val max = LiteRtLmRunner.MAX_TOKENS
                        Spacer(Modifier.height(4.dp))
                        Text("Bộ nhớ ngữ cảnh: khoảng $used / $max token",
                            style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { (used.toFloat() / max).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text("Gần đầy thì trợ lý tự bắt đầu cuộc trò chuyện mới, giữ lại 6 câu gần nhất.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (llmTurns.size > 1) item {
            Section("Trung bình ${llmTurns.size} câu AI") {
                val gens = llmTurns.mapNotNull { it.gen }
                Line("Tốc độ", tps(gens.map { it.decodeTps }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0) + " token/giây")
                Line("Chữ đầu (mô hình)", sec(gens.map { it.ttftMs }.average().toLong()))
                Line("Tổng thời gian", sec(llmTurns.map { it.totalMs }.average().toLong()))
                val reused = gens.filter { !it.freshChat }
                if (reused.isNotEmpty()) Line("Chữ đầu khi dùng lại cuộc trò chuyện", sec(reused.map { it.ttftMs }.average().toLong()))
            }
        }

        if (all.isNotEmpty()) {
            item {
                Text("Các câu gần đây", style = MaterialTheme.typography.titleMedium)
                Text("Câu đơn giản và câu tra dữ liệu trả lời ngay, không cần AI.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(all) { TurnRow(it) }
        }
    }
}

@Composable
private fun TurnRow(t: TurnStat) {
    val time = remember(t.at) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t.at)) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row {
                Text(t.question, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val detail = buildString {
                append("⏱ ${sec(t.totalMs)}")
                when (t.tier) {
                    Tier.QUICK -> append(" · trả lời nhanh, không dùng AI")
                    Tier.DATA -> append(" · đọc dữ liệu trong máy")
                    Tier.LLM -> {
                        t.gen?.let { g ->
                            append(" · chữ đầu ${sec(g.ttftMs)} · ${tps(g.decodeTps)} tok/s · ${g.tokens} token")
                            if (g.freshChat) append(" · cuộc trò chuyện mới")
                        }
                        if (t.backend.isNotEmpty()) append(" · ${t.backend}")
                        if (t.loadMs > 0) append(" · nạp mô hình ${sec(t.loadMs)}")
                    }
                }
                if (t.voiceMs > 0) append(" · 🎤 ${sec(t.voiceMs)}")
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Ô số lớn như thanh số liệu của Gallery. */
@Composable
private fun Tile(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(note, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private class Ram(val appMb: Long, val freeMb: Long, val totalMb: Long)

/** RAM app đang dùng (gồm cả mô hình nạp ngoài Java) và RAM máy còn trống. */
private fun readRam(ctx: Context): Ram {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return Ram(appMb = Debug.getPss() / 1024, freeMb = mi.availMem / 1_000_000, totalMb = mi.totalMem / 1_000_000)
}

private fun sec(ms: Long) = "%.1f s".format(ms / 1000.0).replace('.', ',')
private fun tps(v: Double) = if (v <= 0) "–" else "%.1f".format(v).replace('.', ',')
