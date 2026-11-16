package vn.phamvu.trolyai.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import vn.phamvu.trolyai.ChatViewModel
import vn.phamvu.trolyai.ModelCard

/**
 * Mở trình chọn file của Android. Các trình chọn được đăng ký ở MainActivity (không phải trong từng màn hình),
 * để kết quả vẫn về đúng chỗ dù app bị khoá lại hay màn hình đã đổi trong lúc bạn đang chọn file.
 */
class ModelPickers(
    val model: () -> Unit,
    val speaker: () -> Unit,
    val asrFiles: () -> Unit,
    val asrFolder: () -> Unit,
    val viAsrFiles: () -> Unit,
    val viAsrFolder: () -> Unit,
)

/** Một chỗ xem và chọn các mô hình: AI (.task hoặc .litertlm), nhận giọng chủ máy, nghe tiếng Việt, nghe tiếng Anh. */
@Composable
fun ModelsScreen(vm: ChatViewModel, pickers: ModelPickers, onBack: () -> Unit) {
    val busy = vm.importProgress.value != null || vm.speakerBusy.value || vm.asrBusy.value ||
        vm.viAsrBusy.value
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🧠 Mô hình trong máy", style = MaterialTheme.typography.titleLarge)
        Text("Tải file về điện thoại (thường vào thư mục Download) rồi chọn ở đây. App chép file vào bộ nhớ " +
            "riêng của nó, chép xong có thể xoá bản trong Download.", style = MaterialTheme.typography.bodySmall)

        Text((if (vm.modelReady.value) "✅" else "❌") + " Mô hình AI trò chuyện (.task hoặc .litertlm)",
            style = MaterialTheme.typography.titleMedium)
        Text(if (vm.modelReady.value) "Đang dùng: ${vm.modelLabel()}. Chọn file khác để đổi; file cũ sẽ bị thay."
            else "File .task chạy bằng MediaPipe, file .litertlm (ví dụ Gemma 4 E2B) chạy bằng LiteRT-LM.",
            style = MaterialTheme.typography.bodySmall)
        ModelCard(
            ready = vm.modelReady.value,
            progress = vm.importProgress.value,
            loading = vm.importLoading.value,
            note = vm.importNote.value,
            onPick = pickers.model,
            onCancel = vm::cancelImport,
            onDismiss = { vm.importNote.value = "" },
        )

        HorizontalDivider()
        SpeakerModelSection(vm, pickers)
        HorizontalDivider()
        ViAsrModelSection(vm, pickers)
        HorizontalDivider()
        AsrModelSection(vm, pickers)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("Quay lại") }
    }
}

/** Mô hình nhận giọng chủ máy: chọn một file .onnx, app lưu thành speaker.onnx. */
@Composable
fun SpeakerModelSection(vm: ChatViewModel, pickers: ModelPickers) {
    val ready = vm.speakerReady.value
    val busy = vm.speakerBusy.value
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (ready) "✅" else "❌") + " Nhận giọng chủ máy (.onnx)", style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "Đã có (${vm.speakerSizeMb()} MB)." else
            "Tải file 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx ở trang releases của " +
            "k2-fsa/sherpa-onnx, mục speaker-recongition-models, rồi chọn nó. Tên file là gì cũng được.",
            style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = pickers.speaker) {
            Text(if (ready) "Chọn file khác" else "Chọn file .onnx")
        }
        if (vm.speakerNote.value.isNotEmpty()) Text(vm.speakerNote.value)
    }
}

/**
 * Mô hình nghe tiếng Anh (chế độ phỏng vấn). Chọn được file .tar.bz2 tải về (không cần giải nén),
 * các file lẻ, hoặc thư mục đã giải nén.
 */
@Composable
fun AsrModelSection(vm: ChatViewModel, pickers: ModelPickers) = SherpaAsrSection(
    title = "Nghe tiếng Anh (phỏng vấn)",
    archive = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
    missing = vm.asrMissing.value, busy = vm.asrBusy.value, note = vm.asrNote.value,
    onFiles = pickers.asrFiles, onFolder = pickers.asrFolder,
)

/** Mô hình nghe tiếng Việt cho nút 🎤: chạy offline, không cần Google hay mạng. */
@Composable
fun ViAsrModelSection(vm: ChatViewModel, pickers: ModelPickers) = SherpaAsrSection(
    title = "Nghe tiếng Việt offline (nút 🎤)",
    archive = "sherpa-onnx-zipformer-vi-int8-2025-04-20.tar.bz2",
    missing = vm.viAsrMissing.value, busy = vm.viAsrBusy.value, note = vm.viAsrNote.value,
    onFiles = pickers.viAsrFiles, onFolder = pickers.viAsrFolder,
    extra = "Có mô hình này thì nói được cả khi không có mạng, không cần app Google. " +
        "Bản nhỏ hơn: sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2.",
)

@Composable
private fun SherpaAsrSection(
    title: String, archive: String, missing: List<String>, busy: Boolean, note: String,
    onFiles: () -> Unit, onFolder: () -> Unit, extra: String = "",
) {
    val ready = missing.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (ready) "✅" else "❌") + " " + title, style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "Đã có đủ tokens.txt, encoder, decoder, joiner." else
            (if (extra.isNotEmpty()) "$extra\n" else "") +
            "Tải $archive ở trang releases của k2-fsa/sherpa-onnx, " +
            "mục asr-models. Bấm \"Chọn file\" rồi chọn thẳng file .tar.bz2 đó, không cần giải nén. Hoặc chọn các file " +
            "tokens.txt, encoder, decoder, joiner (giữ ngón tay để chọn nhiều file), hoặc chọn thư mục đã giải nén." +
            (if (missing.size < 4) "\nCòn thiếu: ${missing.joinToString(", ")}." else ""),
            style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = onFiles) { Text(if (ready) "Chọn file khác" else "Chọn file") }
            OutlinedButton(enabled = !busy, onClick = onFolder) { Text("Chọn thư mục") }
        }
        if (note.isNotEmpty()) Text(note)
    }
}
