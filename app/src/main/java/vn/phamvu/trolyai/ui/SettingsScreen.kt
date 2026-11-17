package vn.phamvu.trolyai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.phamvu.trolyai.ChatViewModel
import kotlin.math.roundToInt

/**
 * Màn ⚙️ Cài đặt, như phần "Configs" của Google AI Edge Gallery: thông số sinh chữ của mô hình
 * (temperature, top-k, top-p, chế độ suy nghĩ, GPU / CPU) và giọng đọc câu trả lời.
 * [voices] là tên các giọng tiếng Việt có trong máy; [onTestVoice] đọc thử một câu với cài đặt hiện tại.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, voices: List<String>, onTestVoice: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val gen = vm.genSettings.value
    val sp = vm.speechSettings.value
    val isTask = vm.modelInfo().file.endsWith(".task")

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("⚙️ Cài đặt", style = MaterialTheme.typography.titleLarge)
        }

        // ---- Mô hình ----
        Text("Mô hình AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (isTask) Text("Mô hình .task (MediaPipe) chưa dùng được temperature / top-k / top-p và chế độ suy nghĩ. " +
            "Dùng mô hình .litertlm để chỉnh các mục này.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        Text("Chạy trên", style = MaterialTheme.typography.bodyMedium)
        val backends = listOf("auto" to "Tự chọn", "gpu" to "GPU", "cpu" to "CPU")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            backends.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = gen.backend == key,
                    onClick = { if (gen.backend != key) vm.setGen(gen.copy(backend = key)) },
                    shape = SegmentedButtonDefaults.itemShape(i, backends.size),
                ) { Text(label) }
            }
        }
        Hint("Tự chọn: thử GPU trước, lỗi thì dùng CPU. Đổi mục này thì mô hình được nạp lại ở câu hỏi sau.")

        SliderRow("Temperature", "%.2f".format(gen.temperature), gen.temperature, 0f..2f, steps = 39) {
            vm.setGen(gen.copy(temperature = (it * 20).roundToInt() / 20f))
        }
        Hint("Thấp: trả lời chắc chắn, ít thay đổi. Cao: sáng tạo hơn nhưng dễ nói lan man.")
        SliderRow("Top-K", gen.topK.toString(), gen.topK.toFloat(), 1f..128f, steps = 126) {
            vm.setGen(gen.copy(topK = it.roundToInt().coerceIn(1, 128)))
        }
        SliderRow("Top-P", "%.2f".format(gen.topP), gen.topP, 0f..1f, steps = 19) {
            vm.setGen(gen.copy(topP = (it * 20).roundToInt() / 20f))
        }
        Hint("Top-K / Top-P giới hạn số chữ mô hình được cân nhắc ở mỗi bước. Để mặc định nếu không chắc.")

        SwitchRow("Chế độ suy nghĩ", gen.thinking) { vm.setGen(gen.copy(thinking = it)) }
        Hint("Mô hình nghĩ trước rồi mới trả lời: kỹ hơn nhưng chậm hơn. Phần suy nghĩ hiện trong khung 💭, " +
            "bấm để mở xem. Chỉ mô hình có hỗ trợ (như Gemma 4) mới dùng được.")
        TextButton(onClick = vm::resetGen) { Text("Về mặc định") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- Giọng đọc ----
        Text("Giọng đọc", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Tự đọc câu trả lời", sp.autoSpeak) { vm.setSpeech(sp.copy(autoSpeak = it)) }
        Hint("Tắt thì vẫn bấm 🔊 dưới mỗi câu để nghe.")
        SliderRow("Tốc độ đọc", "%.1fx".format(sp.rate), sp.rate, 0.5f..2f, steps = 14) {
            vm.setSpeech(sp.copy(rate = (it * 10).roundToInt() / 10f))
        }
        SliderRow("Cao độ", "%.1f".format(sp.pitch), sp.pitch, 0.5f..2f, steps = 14) {
            vm.setSpeech(sp.copy(pitch = (it * 10).roundToInt() / 10f))
        }
        if (voices.size > 1) {
            Text("Giọng", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            (listOf("") + voices).forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sp.voice == v, onClick = { vm.setSpeech(sp.copy(voice = v)) })
                    Text(if (v.isEmpty()) "Mặc định của máy" else voiceLabel(v, voices),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (voices.isEmpty()) {
            Hint("Máy chưa có giọng tiếng Việt. Cài trong Cài đặt Android › Chuyển văn bản thành giọng nói.")
        }
        OutlinedButton(onClick = onTestVoice, modifier = Modifier.padding(top = 4.dp)) { Text("🔊 Nghe thử") }
    }
}

/** Tên giọng của Google thường dài như "vi-vn-x-gft-local"; đánh số cho dễ đọc, kèm "(offline)" nếu có. */
private fun voiceLabel(v: String, all: List<String>): String {
    val n = all.indexOf(v) + 1
    val tag = when {
        v.contains("local") -> " (offline)"
        v.contains("network") -> " (cần mạng)"
        else -> ""
    }
    return "Giọng $n$tag"
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    // Kéo thì chỉ đổi số trên màn hình; thả tay mới lưu, để mô hình không bắt đầu lại liên tục
    var drag by remember(current) { mutableFloatStateOf(current) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(if (drag == current) value else "%.2f".format(drag), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = drag, onValueChange = { drag = it }, onValueChangeFinished = { onChange(drag) },
            valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
