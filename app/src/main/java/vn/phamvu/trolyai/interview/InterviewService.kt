package vn.phamvu.trolyai.interview

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.voice.VoiceId

/**
 * Chế độ ẩn hỗ trợ phỏng vấn tiếng Anh.
 * Nghe liên tục bằng micro → chữ tiếng Anh (sherpa-onnx, offline) → hết câu thì nhờ mô hình AI trên máy
 * gợi ý ý trả lời → hiện trong ô nhỏ nổi trên màn hình.
 * Nếu đã đăng ký giọng chủ máy, những câu do chính bạn nói sẽ không bị coi là câu hỏi.
 *
 * Micro chỉ nghe được âm thanh trong phòng: hợp nhất khi phỏng vấn trên máy tính (loa ngoài) hoặc trực tiếp,
 * điện thoại đặt gần. Khi điện thoại đang trong cuộc gọi, Android dành micro cho cuộc gọi nên app không nghe được.
 */
class InterviewService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var overlay: InterviewOverlay
    private lateinit var coach: InterviewCoach
    private lateinit var voice: VoiceId

    // Chỉ đọc / ghi trên luồng chính
    private val pending = mutableListOf<String>()   // các đoạn của câu hỏi đang nói dở
    private val history = mutableListOf<String>()   // vài lượt gần nhất để mô hình hiểu ngữ cảnh
    private var debounce: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        coach = InterviewCoach(app.llm, app.db)
        voice = VoiceId(this, app.db)
        overlay = InterviewOverlay(this, onStop = ::stopSelf)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running.value) return START_NOT_STICKY

        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micOk || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }

        startInForeground()
        running.value = true
        overlay.show()
        scope.launch(Dispatchers.IO) { listenLoop() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val stop = PendingIntent.getService(this, 1, Intent(this, InterviewService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, App.CHANNEL_QUIET)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Đang nghe")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .addAction(0, "Dừng", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    @SuppressLint("MissingPermission") // đã kiểm tra quyền micro trong onStartCommand
    private suspend fun listenLoop() {
        val asrFiles = EnglishAsr(this)
        if (!asrFiles.modelReady()) {
            ui { overlay.status("Chưa có mô hình nghe tiếng Anh. Xem README, mục Chế độ phỏng vấn.") }
            return
        }
        val asr = try { asrFiles.create() } catch (e: Exception) {
            ui { overlay.status("Không mở được mô hình nghe: ${e.message}") }; return
        }
        val rate = EnglishAsr.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        val stream = asr.createStream()
        val buf = ShortArray(rate / 10) // 0,1 giây
        val segment = ArrayList<FloatArray>() // âm thanh của câu đang nói, để kiểm tra có phải giọng bạn
        var shownText = ""
        try {
            rec.startRecording()
            ui { overlay.status("Đang nghe…") }
            while (scope.isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val chunk = FloatArray(n) { buf[it] / 32768f }
                stream.acceptWaveform(chunk, rate)
                if (segment.size < 300) segment += chunk // tối đa 30 giây
                while (asr.isReady(stream)) asr.decode(stream)

                val text = tidy(asr.getResult(stream).text)
                if (text != shownText && text.isNotEmpty()) { shownText = text; ui { overlay.heard(text) } }

                if (asr.isEndpoint(stream)) {
                    asr.reset(stream)
                    if (text.isNotEmpty()) {
                        val audio = join(segment)
                        ui { onUtterance(text, audio) }
                    }
                    segment.clear()
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
            stream.release(); asr.release()
        }
    }

    /** Một câu vừa nói xong. Chạy trên luồng chính. */
    private fun onUtterance(text: String, audio: FloatArray) {
        scope.launch {
            val mine = audio.size >= EnglishAsr.SAMPLE_RATE && voice.modelReady() && voice.enrolled() &&
                runCatching { voice.score(audio) >= voice.threshold }.getOrDefault(false)
            if (mine) {
                // Bạn đang trả lời: câu hỏi trước đã xong
                remember("Me: $text"); pending.clear(); debounce?.cancel()
                return@launch
            }
            if (text.split(' ').size < 3) return@launch // "okay", "great"… không cần gợi ý
            remember("Interviewer: $text")
            pending += text
            // Người hỏi hay ngừng giữa câu: đợi thêm một chút để gộp thành một câu hỏi
            debounce?.cancel()
            debounce = launch {
                delay(1200)
                val context = history.dropLast(pending.size) // các lượt trước câu hỏi này
                val q = pending.joinToString(" "); pending.clear()
                // Chạy riêng để câu hỏi tiếp theo không huỷ gợi ý đang làm dở
                scope.launch {
                    overlay.status("Đang nghĩ…")
                    val s = runCatching { coach.suggest(q, context) }.getOrElse { "Lỗi: ${it.message}" }
                    overlay.suggestion(s)
                }
            }
        }
    }

    private fun remember(line: String) {
        history += line
        if (history.size > 10) history.removeAt(0)
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** Mô hình trả chữ in hoa; đổi về chữ thường cho dễ đọc. */
    private fun tidy(s: String) = s.trim().lowercase().replaceFirstChar { it.uppercase() }
        .replace(Regex("\\bi\\b"), "I")

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var i = 0
        for (p in parts) { p.copyInto(out, i); i += p.size }
        return out
    }

    override fun onDestroy() {
        scope.cancel()
        overlay.remove()
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 7001
        private const val ACTION_STOP = "stop"

        /** Giao diện đọc để biết đang bật hay tắt. */
        val running = mutableStateOf(false)

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, InterviewService::class.java))

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, InterviewService::class.java)) }
    }
}
