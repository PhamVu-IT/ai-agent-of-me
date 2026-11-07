package vn.phamvu.trolyai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

    private val screen = mutableStateOf("chat")          // "chat" hoặc "giong"
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
                        else -> ChatScreen(vm,
                            onMic = ::listen,
                            onSpeak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "r") },
                            // Phải xác nhận vân tay trước, để người khác không đăng ký giọng của họ thay bạn
                            onVoiceSetup = { lifecycleScope.launch { if (auth.confirm("Đăng ký giọng chủ máy")) screen.value = "giong" } },
                            onBankSetup = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
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
) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.size - 1)
        vm.messages.lastOrNull()?.takeIf { !it.fromUser }?.let { onSpeak(it.text) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onVoiceSetup, label = { Text("🎙️ Giọng của tôi") })
            AssistChip(onClick = onBankSetup, label = { Text("🏦 Đọc biến động số dư") })
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
