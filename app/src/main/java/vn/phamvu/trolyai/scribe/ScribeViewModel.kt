package vn.phamvu.trolyai.scribe

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.media.ChatMedia
import vn.phamvu.trolyai.voice.VietnameseAsr
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Một đoạn trong bản chép: bắt đầu từ giây thứ mấy, và lời đã chép / dịch. */
data class ScribeSegment(val startMs: Long, val text: String)

/**
 * Màn 🎧 Chép lời, như "Audio Scribe" của Google AI Edge Gallery: chọn file ghi âm (hoặc thu âm dài),
 * app cắt thành từng đoạn khoảng 25 giây (cắt ở chỗ im nhất cho khỏi đứt chữ) rồi cho AI nghe từng đoạn
 * để chép lời hoặc dịch. Mô hình không nghe được âm thanh thì dùng bộ nhận dạng tiếng Việt offline.
 * Tất cả chạy trong máy, không gửi âm thanh đi đâu.
 */
class ScribeViewModel(app: Application) : AndroidViewModel(app) {
    private val llm = (app as App).llm
    private val asr = VietnameseAsr(app)

    enum class Engine(val label: String) { AI("AI nghe"), ASR("Nhận dạng tiếng Việt") }

    /** Ngôn ngữ đích khi dịch; rỗng là chỉ chép lời, giữ nguyên ngôn ngữ gốc. */
    val languages = listOf("" to "Chép lời", "tiếng Việt" to "Dịch sang Việt", "tiếng Anh" to "Dịch sang Anh",
        "tiếng Trung" to "Dịch sang Trung", "tiếng Nhật" to "Dịch sang Nhật", "tiếng Hàn" to "Dịch sang Hàn")

    val fileName = mutableStateOf("")
    val durationMs = mutableLongStateOf(0L)
    val target = mutableStateOf("")
    val engine = mutableStateOf(Engine.AI)
    /** Mô hình AI đang nạp có nghe được âm thanh không; null khi chưa biết. */
    val aiCanListen = mutableStateOf<Boolean?>(null)
    val asrReady get() = asr.modelReady()

    val busy = mutableStateOf(false)
    /** Đang làm tới đâu (0..1), và dòng chữ mô tả, ví dụ "Đoạn 3/12". */
    val progress = mutableFloatStateOf(0f)
    val status = mutableStateOf("")
    val segments = mutableStateListOf<ScribeSegment>()
    /** Chữ đang hiện dần của đoạn đang làm. */
    val live = mutableStateOf("")
    val error = mutableStateOf("")

    val recording = mutableStateOf(false)
    val recordMs = mutableLongStateOf(0L)
    val recordLevel = mutableFloatStateOf(0f)
    @Volatile private var stopRecording = false

    private var samples: ShortArray? = null
    private var job: Job? = null

    init {
        // Biết sớm mô hình có nghe được không, để chọn sẵn cách chép phù hợp
        viewModelScope.launch {
            val ok = runCatching { llm.hasModel() && llm.canListen() }.getOrDefault(false)
            aiCanListen.value = ok
            if (!ok && asr.modelReady()) engine.value = Engine.ASR
        }
    }

    val hasAudio get() = durationMs.longValue > 0

    /** Mở file ghi âm bạn chọn và giải mã sẵn. */
    fun open(uri: Uri) {
        if (busy.value || recording.value) return
        job = viewModelScope.launch {
            busy.value = true
            error.value = ""
            status.value = "Đang đọc file…"
            try {
                val app = getApplication<Application>()
                fileName.value = runCatching {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: "Bản ghi âm"
                val pcm = AudioDecoder.decode(app, uri) { progress.floatValue = it }
                setAudio(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = "Không đọc được file: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy.value = false
                status.value = ""
                progress.floatValue = 0f
            }
        }
    }

    /** Thu âm dài (tối đa 30 phút) ngay trong màn này, ví dụ ghi một buổi họp. */
    fun startRecording() {
        if (busy.value || recording.value) return
        stopRecording = false
        recording.value = true
        error.value = ""
        recordMs.longValue = 0
        job = viewModelScope.launch {
            try {
                val bytes = ChatMedia.record({ stopRecording }, { ms, level ->
                    recordMs.longValue = ms
                    recordLevel.floatValue = level
                }, maxSec = MAX_RECORD_SEC)
                val pcm = ShortArray(bytes.size / 2).also {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                }
                fileName.value = "Ghi âm lúc " + java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale("vi"))
                    .format(java.util.Date())
                setAudio(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = e.message ?: "Không thu âm được"
            } finally {
                recording.value = false
            }
        }
    }

    fun stopRecording() { stopRecording = true }

    private fun setAudio(pcm: ShortArray) {
        samples = pcm
        durationMs.longValue = pcm.size * 1000L / AudioDecoder.SAMPLE_RATE
        segments.clear()
        live.value = ""
    }

    /** Chép lời / dịch cả bản ghi, từng đoạn một; kết quả hiện dần. */
    fun start() {
        val pcm = samples ?: return
        if (busy.value) return
        val useAi = engine.value == Engine.AI
        val lang = target.value
        job = viewModelScope.launch {
            busy.value = true
            error.value = ""
            segments.clear()
            live.value = ""
            try {
                if (useAi && !llm.canListen()) throw IllegalStateException(
                    "Mô hình AI đang dùng không nghe được âm thanh. Chọn mô hình Gemma 3n / Gemma 4 dạng .litertlm, " +
                        "hoặc dùng \"Nhận dạng tiếng Việt\".")
                if (!useAi && !asr.modelReady()) throw IllegalStateException(
                    "Chưa có mô hình nhận dạng tiếng Việt. Cài ở ⋮ › 🧠 Mô hình.")
                val parts = splitPoints(pcm)
                for ((i, range) in parts.withIndex()) {
                    status.value = "Đoạn ${i + 1}/${parts.size}"
                    progress.floatValue = i.toFloat() / parts.size
                    val chunk = pcm.copyOfRange(range.first, range.last + 1)
                    val text = if (useAi) aiListen(chunk, lang) else asrListen(chunk, lang)
                    live.value = ""
                    if (text.isNotBlank()) segments += ScribeSegment(range.first * 1000L / AudioDecoder.SAMPLE_RATE, text.trim())
                }
                progress.floatValue = 1f
                if (segments.isEmpty()) error.value = "Không nghe thấy lời nói nào trong bản ghi."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
                status.value = ""
                live.value = ""
            }
        }
    }

    /** Dừng giữa chừng; các đoạn đã xong vẫn giữ lại. Đoạn đang làm dở chạy nốt ở nền rồi bỏ. */
    fun cancel() {
        stopRecording = true
        job?.cancel()
        busy.value = false
        status.value = ""
        live.value = ""
    }

    /** Cả bản chép thành chữ, mỗi đoạn kèm mốc thời gian, để chép hay chia sẻ. */
    fun fullText(withTime: Boolean = true): String = segments.joinToString("\n\n") {
        if (withTime) "[${clock(it.startMs)}] ${it.text}" else it.text
    }

    private suspend fun aiListen(chunk: ShortArray, lang: String): String {
        val prompt = if (lang.isEmpty())
            "Chép lại chính xác từng lời nói trong đoạn âm thanh này, đúng ngôn ngữ người nói dùng. " +
                "Chỉ ghi lời nói, không thêm lời giải thích. Không có lời nói thì không ghi gì."
        else
            "Dịch lời nói trong đoạn âm thanh này sang $lang. Chỉ ghi bản dịch, không thêm lời giải thích. " +
                "Không có lời nói thì không ghi gì."
        return llm.listen(ChatMedia.wav(toBytes(chunk)), prompt) { live.value = it }
    }

    /** Bộ nhận dạng offline chỉ nghe tiếng Việt; muốn dịch thì nhờ AI dịch phần chữ đã nhận dạng. */
    private suspend fun asrListen(chunk: ShortArray, lang: String): String {
        val text = withContext(Dispatchers.Default) {
            asr.transcribe(FloatArray(chunk.size) { chunk[it] / 32768f })
        }
        if (lang.isEmpty() || lang == "tiếng Việt" || text.isBlank()) return text
        live.value = text
        return llm.generate("Dịch đoạn sau sang $lang. Chỉ ghi bản dịch, không thêm lời giải thích:\n\n$text")
    }

    override fun onCleared() {
        stopRecording = true
        asr.release()
        super.onCleared()
    }

    companion object {
        const val MAX_RECORD_SEC = 30 * 60
        /** Gallery giới hạn 30 giây âm thanh mỗi lượt; cắt quanh 25 giây cho dư chỗ. */
        private const val TARGET_SEC = 25
        private const val MIN_SEC = 18
        private const val MAX_SEC = 30

        /**
         * Cắt bản ghi thành các đoạn 18–30 giây. Trong khoảng đó chọn chỗ 0,1 giây im nhất để cắt, cho khỏi
         * cắt ngang một chữ. Đoạn cuối quá ngắn (dưới 1 giây) thì gộp vào đoạn trước.
         */
        internal fun splitPoints(pcm: ShortArray, rate: Int = AudioDecoder.SAMPLE_RATE): List<IntRange> {
            val out = mutableListOf<IntRange>()
            val win = rate / 10
            var start = 0
            while (start < pcm.size) {
                if (pcm.size - start <= MAX_SEC * rate) { out += start until pcm.size; break }
                var best = start + TARGET_SEC * rate
                var bestEnergy = Long.MAX_VALUE
                var w = start + MIN_SEC * rate
                while (w + win <= start + MAX_SEC * rate) {
                    var e = 0L
                    for (i in w until w + win) e += kotlin.math.abs(pcm[i].toInt())
                    if (e < bestEnergy) { bestEnergy = e; best = w + win / 2 }
                    w += win
                }
                out += start until best
                start = best
            }
            if (out.size > 1 && out.last().count() < rate) {
                val last = out.removeAt(out.size - 1)
                out[out.size - 1] = out.last().first..last.last
            }
            return out
        }

        private fun toBytes(s: ShortArray): ByteArray =
            ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(s) }.array()

        fun clock(ms: Long): String {
            val sec = ms / 1000
            return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            else "%02d:%02d".format(sec / 60, sec % 60)
        }
    }
}
