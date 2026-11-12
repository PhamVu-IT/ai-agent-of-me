package vn.phamvu.trolyai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.data.InterviewProfile
import vn.phamvu.trolyai.interview.EnglishAsr
import vn.phamvu.trolyai.interview.InterviewCoach
import vn.phamvu.trolyai.interview.InterviewService
import vn.phamvu.trolyai.security.BiometricAuthenticator
import vn.phamvu.trolyai.security.VoiceThenBiometric
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

    private val screen = mutableStateOf("chat")          // "chat", "giong" hoặc "phongvan"
    private val voicePrompt = mutableStateOf<String?>(null) // dòng chữ khi đang nghe để nhận giọng

    // Nhận giọng nói (Android hỗ trợ offline nếu đã tải gói tiếng Việt trong Google app)
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
    }
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPermissions.launch(perms.toTypedArray())

        tts = TextToSpeech(this) { tts?.language = Locale("vi", "VN") }
        auth = BiometricAuthenticator(this)
        voice = VoiceId(this, (application as App).db)
        vm.executor.auth = VoiceThenBiometric(voice, auth, voicePrompt)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        !unlocked.value -> LockedScreen(noLockSet = !auth.isAvailable(), onUnlock = ::unlock)
                        screen.value == "giong" -> VoiceEnrollScreen(voice, onDone = { screen.value = "chat" })
                        screen.value == "phongvan" -> InterviewScreen(onBack = { screen.value = "chat" })
                        else -> ChatScreen(vm,
                            onMic = ::listen,
                            onSpeak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "r") },
                            // Phải xác nhận vân tay trước, để người khác không đăng ký giọng của họ thay bạn
                            onVoiceSetup = { lifecycleScope.launch { if (auth.confirm("Đăng ký giọng chủ máy")) screen.value = "giong" } },
                            onBankSetup = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            onInterview = { screen.value = "phongvan" })
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

    override fun onStart() {
        super.onStart()
        if (backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > 60_000) unlocked.value = false
        if (!unlocked.value) unlock()
    }

    override fun onStop() {
        super.onStop()
        if (!authenticating) backgroundSince = System.currentTimeMillis()
    }

    private fun unlock() {
        // Máy chưa cài khoá màn hình: không có gì để kiểm tra, cho vào và nhắc cài đặt
        if (!auth.isAvailable()) { unlocked.value = true; return }
        if (authenticating) return
        authenticating = true
        lifecycleScope.launch {
            unlocked.value = auth.confirm("Mở trợ lý AI")
            backgroundSince = 0L
            authenticating = false
        }
    }

    private fun listen() {
        speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
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
fun VoiceEnrollScreen(voice: VoiceId, onDone: () -> Unit) {
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
            !voice.modelReady() -> Text("Chưa có mô hình nhận giọng. Hãy chép file speaker.onnx vào máy (xem README).")
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
                        catch (e: Exception) { "Lỗi: ${e.message}" }
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
) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.size - 1)
        vm.messages.lastOrNull()?.takeIf { !it.fromUser }?.let { onSpeak(it.text) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onVoiceSetup, label = { Text("🎙️ Giọng của tôi") })
            AssistChip(onClick = onBankSetup, label = { Text("🏦 Đọc biến động số dư") })
            AssistChip(onClick = onInterview, label = { Text("🎧 Phỏng vấn") })
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(m.text, Modifier.padding(10.dp))
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

/**
 * Cài đặt chế độ ẩn hỗ trợ phỏng vấn tiếng Anh: hồ sơ của bạn, kiểm tra mô hình và quyền, bật / tắt,
 * và ô luyện tập gõ câu hỏi để thử gợi ý.
 */
@Composable
fun InterviewScreen(onBack: () -> Unit) {
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
    val asrReady = remember(check) { EnglishAsr(ctx).modelReady() }
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
        Text((if (llmReady) "✅" else "❌") + " Mô hình AI (model.task)")
        Text((if (asrReady) "✅" else "❌") + " Mô hình nghe tiếng Anh (thư mục asr-en, xem README)")
        Text((if (micOk) "✅" else "❌") + " Quyền dùng micro")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (overlayOk) "✅" else "❌") + " Quyền hiện trên ứng dụng khác", Modifier.weight(1f))
            if (!overlayOk) TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Cấp quyền") }
        }
        OutlinedButton(onClick = { check++ }) { Text("Kiểm tra lại") }

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
                catch (e: Exception) { "Lỗi: ${e.message}" }
                busy = false
            }
        }) { Text("Gợi ý trả lời") }
        if (practiceOut.isNotEmpty()) Card { Text(practiceOut, Modifier.padding(12.dp)) }

        if (note.isNotEmpty()) Text(note)
        Button(onClick = onBack) { Text("Quay lại") }
    }
}
