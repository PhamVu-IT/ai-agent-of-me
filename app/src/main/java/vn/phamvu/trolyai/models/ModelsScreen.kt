package vn.phamvu.trolyai.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.ChatViewModel
import vn.phamvu.trolyai.ModelCard
import vn.phamvu.trolyai.interview.EnglishAsr
import vn.phamvu.trolyai.voice.VoiceId

/** Một chỗ xem và chọn cả ba mô hình: AI (model.task), nhận giọng chủ máy, nghe tiếng Anh. */
@Composable
fun ModelsScreen(vm: ChatViewModel, voice: VoiceId, onPickModel: () -> Unit, onBack: () -> Unit) {
    val progress = vm.importProgress.value
    val view = LocalView.current
    DisposableEffect(progress != null) {
        view.keepScreenOn = progress != null
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🧠 Mô hình trong máy", style = MaterialTheme.typography.titleLarge)
        Text("Tải file về điện thoại (thường vào thư mục Download) rồi chọn ở đây. App chép file vào bộ nhớ " +
            "riêng của nó, chép xong có thể xoá bản trong Download.", style = MaterialTheme.typography.bodySmall)

        Text((if (vm.modelReady.value) "✅" else "❌") + " Mô hình AI trò chuyện (.task)",
            style = MaterialTheme.typography.titleMedium)
        ModelCard(
            ready = vm.modelReady.value,
            progress = progress,
            loading = vm.importLoading.value,
            note = vm.importNote.value,
            onPick = onPickModel,
            onCancel = vm::cancelImport,
            onDismiss = { vm.importNote.value = "" },
        )

        HorizontalDivider()
        SpeakerModelSection(voice)
        HorizontalDivider()
        AsrModelSection()
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("Quay lại") }
    }
}

/** Mô hình nhận giọng chủ máy: chọn một file .onnx, app lưu thành speaker.onnx. */
@Composable
fun SpeakerModelSection(voice: VoiceId, onChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(voice.modelReady()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; note = "Đang chép…"
        scope.launch {
            note = try { voice.importModel(uri); "✅ Đã cài mô hình nhận giọng." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { "Lỗi: ${e.message}" }
            ready = voice.modelReady(); busy = false
            onChanged()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (ready) "✅" else "❌") + " Nhận giọng chủ máy (.onnx)", style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "Đã có (${voice.modelSizeMb()} MB)." else
            "Tải file 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx ở trang releases của " +
            "k2-fsa/sherpa-onnx, mục speaker-recongition-models, rồi chọn nó. Tên file là gì cũng được.",
            style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (ready) "Chọn file khác" else "Chọn file .onnx")
        }
        if (note.isNotEmpty()) Text(note)
    }
}

/**
 * Mô hình nghe tiếng Anh (chế độ phỏng vấn). Chọn được file .tar.bz2 tải về (không cần giải nén),
 * các file lẻ, hoặc thư mục đã giải nén.
 */
@Composable
fun AsrModelSection(onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val asr = remember { EnglishAsr(ctx) }
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(asr.missing()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    fun startImport(block: suspend () -> String) {
        busy = true; note = "Đang chép, file lớn có thể mất một hai phút. Để app mở nhé…"
        scope.launch {
            note = try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { "Lỗi: ${e.message}" }
            missing = asr.missing(); busy = false
            onChanged()
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) startImport { asr.importFiles(uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) startImport { asr.importFolder(tree) }
    }
    val ready = missing.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (ready) "✅" else "❌") + " Nghe tiếng Anh (phỏng vấn)", style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "Đã có đủ tokens.txt, encoder, decoder, joiner." else
            "Tải sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2 ở trang releases của k2-fsa/sherpa-onnx, " +
            "mục asr-models. Bấm \"Chọn file\" rồi chọn thẳng file .tar.bz2 đó, không cần giải nén. Hoặc chọn các file " +
            "tokens.txt, encoder, decoder, joiner (giữ ngón tay để chọn nhiều file), hoặc chọn thư mục đã giải nén." +
            (if (missing.size < 4) "\nCòn thiếu: ${missing.joinToString(", ")}." else ""),
            style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Chọn file") }
            OutlinedButton(enabled = !busy, onClick = { pickFolder.launch(null) }) { Text("Chọn thư mục") }
        }
        if (note.isNotEmpty()) Text(note)
    }
}
