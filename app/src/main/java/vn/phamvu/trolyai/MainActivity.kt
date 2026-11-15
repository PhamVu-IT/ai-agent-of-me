package vn.phamvu.trolyai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.data.InterviewProfile
import vn.phamvu.trolyai.interview.InterviewCoach
import vn.phamvu.trolyai.interview.InterviewService
import vn.phamvu.trolyai.models.AsrModelSection
import vn.phamvu.trolyai.models.ModelPickers
import vn.phamvu.trolyai.models.ModelsScreen
import vn.phamvu.trolyai.models.SpeakerModelSection
import vn.phamvu.trolyai.security.BiometricAuthenticator
import vn.phamvu.trolyai.security.VoiceThenBiometric
import vn.phamvu.trolyai.voice.VietnameseSpeech
import vn.phamvu.trolyai.voice.VoiceId
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val vm: ChatViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private lateinit var auth: BiometricAuthenticator
    private lateinit var voice: VoiceId

    // Khoá app: mở lại sau hơn 1 phút ở nền thì phải xác nhận lại
    private val unlocked = mutableStateOf(false)
    private var backgroundSince = 0L
    private var authenticating = false // màn hình nhập PIN làm app tạm ẩn, tránh hỏi lại hai lần

    private val screen = mutableStateOf("chat")          // "chat", "giong", "phongvan" hoặc "mohinh"
    private val voicePrompt = mutableStateOf<String?>(null) // dòng chữ khi đang nghe để nhận giọng

    // Nghe tiếng Việt ngay trong app (ưu tiên mô hình offline), không mở hộp thoại "Voice search" của Google
    private lateinit var speech: VietnameseSpeech
    /** Dòng chữ khi đang nghe nút 🎤, hoặc lời báo lỗi kèm hướng dẫn. */
    private val micPrompt = mutableStateOf<String?>(null)
    private val micError = mutableStateOf(false)
    /** Mở app (nút nguồn 2 lần, nút nhanh, widget, cử chỉ trợ lý) là tự nghe luôn, sau khi mở khoá. */
    private var listenWhenReady = false

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    // Chọn file mô hình (.task) từ bộ nhớ máy, thẻ nhớ hoặc Google Drive. File .task không có kiểu MIME
    // riêng nên phải cho chọn mọi loại file, rồi kiểm tra đuôi sau.
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingFile = false
        uri?.let(vm::importModel)
    }
    private val pickSpeaker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingFile = false
        uri?.let(vm::importSpeaker)
    }
    private val pickAsrFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickingFile = false
        if (uris.isNotEmpty()) vm.importAsrFiles(uris)
    }
    private val pickAsrFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        pickingFile = false
        tree?.let(vm::importAsrFolder)
    }
    private val pickViAsrFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickingFile = false
        if (uris.isNotEmpty()) vm.importViAsrFiles(uris)
    }
    private val pickViAsrFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        pickingFile = false
        tree?.let(vm::importViAsrFolder)
    }
    /**
     * Đang mở trình chọn file: app tạm ẩn nhưng không được khoá lại, nếu không thì khi quay về sẽ hiện màn hình
     * khoá, mất màn hình đang mở và file vừa chọn không được lưu.
     */
    private var pickingFile = false
    private val pickers = ModelPickers(
        model = { pickingFile = true; pickModel.launch(arrayOf("*/*")) },
        speaker = { pickingFile = true; pickSpeaker.launch(arrayOf("*/*")) },
        asrFiles = { pickingFile = true; pickAsrFiles.launch(arrayOf("*/*")) },
        asrFolder = { pickingFile = true; pickAsrFolder.launch(null) },
        viAsrFiles = { pickingFile = true; pickViAsrFiles.launch(arrayOf("*/*")) },
        viAsrFolder = { pickingFile = true; pickViAsrFolder.launch(null) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listenWhenReady = savedInstanceState == null
        savedInstanceState?.getString("screen")?.let { screen.value = it }
        pickingFile = savedInstanceState?.getBoolean("picking") ?: false
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPermissions.launch(perms.toTypedArray())

        tts = TextToSpeech(this) { tts?.language = Locale("vi", "VN") }
        auth = BiometricAuthenticator(this)
        voice = VoiceId(this, (application as App).db)
        speech = VietnameseSpeech(this, lifecycleScope)
        vm.executor.auth = VoiceThenBiometric(voice, auth, voicePrompt)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        !unlocked.value -> LockedScreen(noLockSet = !auth.isAvailable(), onUnlock = ::unlock)
                        screen.value == "giong" -> VoiceEnrollScreen(voice, vm, pickers, onDone = { screen.value = "chat" })
                        screen.value == "phongvan" -> InterviewScreen(vm, pickers, onBack = { screen.value = "chat" })
                        screen.value == "mohinh" -> ModelsScreen(vm, pickers, onBack = { screen.value = "chat" })
                        else -> ChatScreen(vm,
                            onMic = { listen(manual = true) },
                            onSpeak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "r") },
                            // Phải xác nhận vân tay trước, để người khác không đăng ký giọng của họ thay bạn
                            onVoiceSetup = { lifecycleScope.launch { if (auth.confirm("Đăng ký giọng chủ máy")) screen.value = "giong" } },
                            onBankSetup = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            onInterview = { screen.value = "phongvan" },
                            onPickModel = pickers.model,
                            onModels = { screen.value = "mohinh" })
                    }
                    micPrompt.value?.let { msg ->
                        Dialog(onDismissRequest = ::stopListening) {
                            Card {
                                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(msg, style = if (micError.value) MaterialTheme.typography.bodyMedium
                                        else MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = ::stopListening) { Text(if (micError.value) "Đóng" else "Huỷ") }
                                        if (micError.value) Button(onClick = { stopListening(); screen.value = "mohinh" }) {
                                            Text("🧠 Chọn mô hình")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    voicePrompt.value?.let { msg ->
                        Dialog(onDismissRequest = {}) {
                            Card { Text(msg, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listenWhenReady = true // app đang chạy ngầm mà được gọi lại
    }

    override fun onStart() {
        super.onStart()
        if (backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > 60_000) unlocked.value = false
        vm.refreshModel() // có thể vừa adb push mô hình vào máy
        vm.refreshSideModels()
        if (!unlocked.value) unlock() else listenIfAsked()
    }

    private fun listenIfAsked() {
        if (!listenWhenReady || !unlocked.value || authenticating) return
        listenWhenReady = false
        listen(manual = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.value)
        outState.putBoolean("picking", pickingFile)
    }

    private fun unlock() {
        // Máy chưa cài khoá màn hình: không có gì để kiểm tra, cho vào và nhắc cài đặt
        if (!auth.isAvailable()) { unlocked.value = true; listenIfAsked(); return }
        if (authenticating) return
        authenticating = true
        lifecycleScope.launch {
            unlocked.value = auth.confirm("Mở trợ lý AI")
            backgroundSince = 0L
            authenticating = false
            listenIfAsked()
        }
    }

    /**
     * Nghe một câu tiếng Việt rồi gửi cho trợ lý. [manual] là bấm nút 🎤; tự nghe lúc mở app ([manual] = false)
     * thì không hiện lỗi nếu máy chưa nghe được, để mở app không bị bật hộp thoại.
     */
    private fun listen(manual: Boolean) {
        if (!manual && (!speech.hasPermission() || !speech.isAvailable())) return
        if (screen.value != "chat") return
        tts?.stop() // không để trợ lý tự nghe tiếng mình đang đọc
        micError.value = false
        micPrompt.value = "🎤 Đang chuẩn bị…"
        speech.start(object : VietnameseSpeech.Callback {
            override fun onListening(text: String) { micError.value = false; micPrompt.value = text }
            override fun onResult(text: String) { micPrompt.value = null; vm.send(text) }
            override fun onEnd(message: String?) {
                if (message == null || !manual) { micPrompt.value = null; return }
                micError.value = true
                micPrompt.value = message
            }
        })
    }

    private fun stopListening() {
        speech.stop()
        micPrompt.value = null
        micError.value = false
    }

    override fun onStop() {
        super.onStop()
        if (micPrompt.value != null && !micError.value) stopListening() // không nghe lén khi app ở nền
        if (!authenticating && !pickingFile) backgroundSince = System.currentTimeMillis()
    }

    override fun onDestroy() { speech.stop(); speech.offline.release(); tts?.shutdown(); super.onDestroy() }
}

@Composable
fun LockedScreen(noLockSet: Boolean, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔒 Trợ lý đang khoá", style = MaterialTheme.typography.titleLarge)
        if (noLockSet) Text("Hãy cài vân tay hoặc mã PIN cho điện thoại để bảo vệ trợ lý.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("Mở khoá") }
    }
}

/** Đăng ký giọng: đọc 5 câu, mỗi câu thu 4 giây. */
@Composable
fun VoiceEnrollScreen(voice: VoiceId, vm: ChatViewModel, pickers: ModelPickers, onDone: () -> Unit) {
    val sentences = listOf(
        "Hôm nay trời đẹp, tôi muốn đi dạo một vòng.",
        "Nhắc tôi đóng tiền nhà vào ngày mùng năm hằng tháng.",
        "Tôi là chủ của chiếc điện thoại này.",
        "Một hai ba bốn năm sáu bảy tám chín mười.",
        "Trợ lý ơi, tháng này tôi đã nộp tiết kiệm chưa?",
    )
    val clips = remember { mutableStateListOf<FloatArray>() }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Đăng ký giọng của bạn", style = MaterialTheme.typography.titleLarge)
        when {
            !vm.speakerReady.value -> {
                Text("Cần mô hình nhận giọng trước đã.")
                SpeakerModelSection(vm, pickers)
            }
            !voice.micAllowed() -> Text("App chưa được phép dùng micro. Hãy cấp quyền micro trong Cài đặt.")
            clips.size < sentences.size -> {
                Text("Câu ${clips.size + 1}/${sentences.size}. Bấm nút rồi đọc to, rõ ràng, ở chỗ yên tĩnh:")
                Text("\"${sentences[clips.size]}\"", style = MaterialTheme.typography.titleMedium)
                Button(enabled = !busy, onClick = {
                    busy = true; status = "🎙️ Đang nghe…"
                    scope.launch { clips.add(voice.record(4)); status = ""; busy = false }
                }) { Text("Bấm để đọc") }
            }
            else -> {
                Button(enabled = !busy, onClick = {
                    busy = true; status = "Đang tạo dấu vân giọng…"
                    scope.launch {
                        status = try { voice.enroll(clips); "✅ Xong! Từ giờ lệnh nhạy cảm sẽ thử nhận giọng trước." }
                        catch (e: Throwable) { "Lỗi: ${e.message ?: e.javaClass.simpleName}" }
                        busy = false
                    }
                }) { Text("Lưu giọng của tôi") }
            }
        }
        if (status.isNotEmpty()) Text(status)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clips.clear(); status = "" }) { Text("Làm lại") }
            Button(onClick = onDone) { Text("Quay lại") }
        }
    }
}

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
) {
    var input by remember { mutableStateOf("") }
    val progress = vm.importProgress.value
    // Chép file vài GB mất vài phút: giữ màn hình sáng để máy không ngủ giữa chừng
    val view = LocalView.current
    DisposableEffect(progress != null) {
        view.keepScreenOn = progress != null
        onDispose { view.keepScreenOn = false }
    }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.size - 1)
    }
    // Mỗi câu trả lời chỉ đọc một lần: đọc xong thì đánh dấu, tin cũ nạp lại khi mở app không được đọc
    val speak = vm.toSpeak.value
    LaunchedEffect(speak?.id) {
        speak?.let { onSpeak(it.text); vm.markSpoken(it) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onVoiceSetup, label = { Text("🎙️ Giọng của tôi") })
            AssistChip(onClick = onBankSetup, label = { Text("🏦 Đọc biến động số dư") })
            AssistChip(onClick = onInterview, label = { Text("🎧 Phỏng vấn") })
            AssistChip(onClick = onModels, label = { Text("🧠 Mô hình") })
        }
        if (!vm.modelReady.value || progress != null || vm.importNote.value.isNotEmpty()) {
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
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.messages) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(m.text, Modifier.padding(10.dp))
                    }
                    // Chấm câu trả lời để trợ lý học: 👎 rồi nói chỗ sai, trợ lý ghi thành quy tắc
                    if (!m.fromUser && m.id > 0) Row {
                        TextButton(onClick = { vm.rate(m, 1) }) { Text(if (m.rating > 0) "👍✓" else "👍") }
                        TextButton(onClick = { vm.rate(m, -1) }) { Text(if (m.rating < 0) "👎✓" else "👎") }
                    }
                }
            }
        }
        if (vm.thinking.value) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Nhắn cho trợ lý…") })
            TextButton(onClick = onMic) { Text("🎤") }
            Button(onClick = { vm.send(input); input = "" }) { Text("Gửi") }
        }
    }
}

/** Thẻ chọn / đổi file mô hình AI, hiện tiến độ khi đang chép. */
@Composable
fun ModelCard(
    ready: Boolean,
    progress: Float?,
    loading: Boolean,
    note: String,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                loading -> {
                    Text("Đang nạp mô hình vào bộ nhớ, chờ chút nhé…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                progress != null -> {
                    Text("Đang chép mô hình vào app: ${(progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("File lớn nên mất vài phút. Để app mở, đừng tắt màn hình.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCancel) { Text("Huỷ") }
                }
                else -> {
                    if (note.isNotEmpty()) Text(note)
                    if (!ready) {
                        Text("Chưa có mô hình AI", style = MaterialTheme.typography.titleMedium)
                        Text("Chọn file mô hình đuôi .task bạn đã tải về máy (ví dụ gemma-3n-E4B-it-int4.task trong " +
                            "thư mục Download). App sẽ chép file vào bộ nhớ riêng của nó, sau đó bạn có thể xoá bản trong Download.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPick) { Text(if (ready) "Chọn mô hình khác" else "Chọn file mô hình") }
                        if (ready) TextButton(onClick = onDismiss) { Text("Đóng") }
                    }
                }
            }
        }
    }
}

/**
 * Cài đặt chế độ ẩn hỗ trợ phỏng vấn tiếng Anh: hồ sơ của bạn, kiểm tra mô hình và quyền, bật / tắt,
 * và ô luyện tập gõ câu hỏi để thử gợi ý.
 */
@Composable
fun InterviewScreen(vm: ChatViewModel, pickers: ModelPickers, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var practice by remember { mutableStateOf("") }
    var practiceOut by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Đọc lại mỗi lần bấm "Kiểm tra lại", vì người dùng có thể vừa sang Cài đặt cấp quyền
    var check by remember { mutableIntStateOf(0) }
    val llmReady = remember(check) { app.llm.modelFile.exists() }
    val asrReady = vm.asrMissing.value.isEmpty()
    val overlayOk = remember(check) { Settings.canDrawOverlays(ctx) }
    val micOk = remember(check) { ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val running = InterviewService.running.value

    LaunchedEffect(Unit) { profile = app.db.interview().get()?.text ?: "" }
    suspend fun saveProfile() = app.db.interview().save(InterviewProfile(text = profile.trim()))

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🎧 Hỗ trợ phỏng vấn tiếng Anh", style = MaterialTheme.typography.titleLarge)
        Text("Máy nghe câu hỏi của người phỏng vấn, hiện gợi ý trả lời trong một ô nhỏ màu tối nổi trên màn hình. " +
            "Chạy hoàn toàn offline. Chạm ô để thu thành chấm nhỏ (chấm xanh = có gợi ý mới), kéo để di chuyển, " +
            "giữ 1 giây để tắt.")

        OutlinedTextField(profile, { profile = it }, Modifier.fillMaxWidth(), minLines = 5,
            label = { Text("Hồ sơ của bạn") },
            placeholder = { Text("Vị trí ứng tuyển, công ty, số năm kinh nghiệm, dự án nổi bật, kỹ năng, điểm mạnh, " +
                "lý do muốn vào công ty… Viết tiếng Anh càng tốt. Gợi ý chỉ dựa trên những gì bạn ghi ở đây.") })
        Button(onClick = { scope.launch { saveProfile(); note = "Đã lưu hồ sơ (mã hoá trong máy)." } }) { Text("Lưu hồ sơ") }

        Text("Kiểm tra", style = MaterialTheme.typography.titleMedium)
        Text((if (llmReady) "✅" else "❌") + " Mô hình AI (model.task)" +
            if (llmReady) "" else ": chọn ở nút 🧠 Mô hình trên màn hình chat")
        Text((if (asrReady) "✅" else "❌") + " Mô hình nghe tiếng Anh")
        if (!asrReady || vm.asrNote.value.isNotEmpty()) AsrModelSection(vm, pickers)
        Text((if (micOk) "✅" else "❌") + " Quyền dùng micro")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (overlayOk) "✅" else "❌") + " Quyền hiện trên ứng dụng khác", Modifier.weight(1f))
            if (!overlayOk) TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Cấp quyền") }
        }
        OutlinedButton(onClick = { check++; vm.refreshSideModels() }) { Text("Kiểm tra lại") }

        if (running) {
            Button(onClick = { InterviewService.stop(ctx) }, Modifier.fillMaxWidth()) { Text("Tắt chế độ ẩn") }
        } else {
            Button(enabled = llmReady && asrReady && overlayOk && micOk, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { saveProfile(); InterviewService.start(ctx) }
            }) { Text("Bật chế độ ẩn") }
        }
        Text("Mẹo: phỏng vấn trên máy tính bằng loa ngoài, đặt điện thoại gần loa. Đăng ký \"Giọng của tôi\" để máy " +
            "bỏ qua câu bạn nói. Khi điện thoại đang có cuộc gọi, Android không cho app khác nghe micro.",
            style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Luyện tập", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(practice, { practice = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("Gõ một câu hỏi, ví dụ: Tell me about yourself") })
        Button(enabled = !busy && llmReady && practice.isNotBlank(), onClick = {
            busy = true; practiceOut = "Đang nghĩ…"
            scope.launch {
                saveProfile()
                practiceOut = try { InterviewCoach(app.llm, app.db).suggest(practice.trim()) }
                catch (e: Throwable) { "Lỗi: ${e.message ?: e.javaClass.simpleName}" }
                busy = false
            }
        }) { Text("Gợi ý trả lời") }
        if (practiceOut.isNotEmpty()) Card { Text(practiceOut, Modifier.padding(12.dp)) }

        if (note.isNotEmpty()) Text(note)
        Button(onClick = onBack) { Text("Quay lại") }
    }
}
