package vn.phamvu.trolyai.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.coroutineContext

/** Giao diện chung để sau này đổi sang llama.cpp hoặc gọi API online mà không sửa phần còn lại. */
interface LlmEngine {
    suspend fun generate(prompt: String): String

    /** Như [generate] nhưng báo dần phần chữ đã sinh qua [onPartial] (toàn bộ chữ tính tới lúc đó). */
    suspend fun generateStream(prompt: String, onPartial: (String) -> Unit): String =
        generate(prompt).also(onPartial)

    /**
     * Một lượt chat. Mặc định ghép thành một prompt dài như trước; [OfflineLlm] với mô hình .litertlm thì giữ
     * cuộc trò chuyện trong mô hình qua các lượt (giống Google AI Edge Gallery), chỉ gửi lời nhắn mới nên
     * chữ đầu tiên hiện ra nhanh hơn nhiều.
     */
    suspend fun chat(turn: ChatTurn, onPartial: (String) -> Unit, onThinking: (String) -> Unit = {}): String =
        generateStream(turn.toGemmaPrompt(), onPartial)

    /** Dừng câu trả lời đang sinh (nút ■). Phần chữ đã sinh vẫn được trả về. */
    fun stop() {}

    /** Số liệu của lượt chat gần nhất, cho màn 📊 Thống kê; null nếu chưa có. */
    val lastStats: GenStats? get() = null

    /** Mô hình đang nạp có xem ảnh và nghe âm thanh được không. */
    val supportsMedia: Boolean get() = false
}

/**
 * Cài đặt sinh chữ, như phần "Model config" của Google AI Edge Gallery. Đổi [backend] thì nạp lại mô hình;
 * đổi các số khác thì bắt đầu cuộc trò chuyện mới. MediaPipe (.task) chưa dùng được temperature / top-k / top-p.
 */
data class GenSettings(
    /** Càng cao càng sáng tạo, càng thấp càng chắc chắn. Gallery mặc định 1,0. */
    val temperature: Float = 1.0f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    /** Chế độ "suy nghĩ" của Gemma 4: mô hình nghĩ trước rồi mới trả lời (chậm hơn nhưng kỹ hơn). */
    val thinking: Boolean = false,
    /** "auto" (thử GPU rồi CPU), "gpu" hoặc "cpu". */
    val backend: String = "auto",
)

/**
 * Số liệu một lượt sinh chữ, giống thanh số liệu của Google AI Edge Gallery.
 * Thư viện gọi lại mỗi lần có một token mới, nên số lần gọi lại xấp xỉ số token đã sinh.
 */
data class GenStats(
    /** Từ lúc mô hình bắt đầu đọc câu hỏi tới chữ đầu tiên (gồm thời gian đọc prompt). */
    val ttftMs: Long,
    /** Từ lúc mô hình bắt đầu tới khi xong. */
    val totalMs: Long,
    /** Số token đã sinh (ước lượng bằng số lần thư viện gọi lại). */
    val tokens: Int,
    /** Số ký tự mô hình phải đọc ở lượt này (chỉ lời nhắn mới nếu dùng lại cuộc trò chuyện). */
    val promptChars: Int,
    /** Lượt này mô hình phải bắt đầu cuộc trò chuyện mới (đọc lại cả phần system) hay dùng lại cuộc cũ. */
    val freshChat: Boolean,
    /** Ước lượng số ký tự đang nằm trong bộ nhớ ngữ cảnh của mô hình. */
    val contextChars: Int,
) {
    /** Tốc độ sinh chữ (token/giây), không tính lúc đọc prompt. */
    val decodeTps: Double get() {
        val ms = totalMs - ttftMs
        return if (tokens > 1 && ms > 0) (tokens - 1) * 1000.0 / ms else 0.0
    }
}

/**
 * Một lượt chat chia làm ba phần để mô hình giữ được cuộc trò chuyện:
 * - [system]: vai trò, quy tắc, hồ sơ, cách trả lời JSON. Ít khi đổi; đổi thì bắt đầu cuộc trò chuyện mới.
 * - [history]: vài câu gần đây (true = người dùng nói), chỉ dùng khi bắt đầu cuộc trò chuyện mới.
 * - [user]: lời nhắn mới kèm dữ liệu riêng của lượt này (giờ, điều nhớ liên quan, sổ chi…).
 */
data class ChatTurn(
    val system: String,
    val history: List<Pair<Boolean, String>>,
    val user: String,
    /** Ảnh gửi kèm (JPEG), chỉ mô hình .litertlm có phần xem ảnh mới dùng được. */
    val images: List<ByteArray> = emptyList(),
    /** Đoạn ghi âm gửi kèm (WAV 16 kHz mono), chỉ mô hình .litertlm có phần nghe mới dùng được. */
    val audios: List<ByteArray> = emptyList(),
) {
    val hasMedia: Boolean get() = images.isNotEmpty() || audios.isNotEmpty()

    /** Ước lượng chỗ ảnh và âm thanh chiếm trong bộ nhớ ngữ cảnh, tính ra ký tự cho dễ cộng với chữ. */
    val mediaChars: Int get() = images.size * IMAGE_CHARS + audios.sumOf { (it.size / 32_000) * 16 + 100 }

    companion object {
        /** Một ảnh khoảng 256–280 token với Gemma 3n / Gemma 4, quy ra khoảng 700 ký tự. */
        const val IMAGE_CHARS = 700
        const val NO_MEDIA = "Mô hình đang dùng chưa xem ảnh hay nghe âm thanh được. Hãy chọn ở \"🧠 Mô hình\" một file " +
            ".litertlm có hỗ trợ ảnh và âm thanh, ví dụ gemma-3n-E2B-it-int4.litertlm hoặc gemma-4-E2B-it.litertlm."
    }

    /** Ghép tất cả thành một prompt khuôn Gemma, cho MediaPipe (.task) và các việc không cần nhớ hội thoại. */
    fun toGemmaPrompt(): String {
        val hist = history.joinToString("\n") { (fromUser, text) -> (if (fromUser) "Họ: " else "Bạn: ") + text }
        return buildString {
            append("<start_of_turn>user\n").append(system)
            if (hist.isNotBlank()) append("\n\nHội thoại gần đây:\n").append(hist)
            append("\n\n").append(user).append("<end_of_turn>\n<start_of_turn>model\n")
        }
    }
}

/** Hai loại file mô hình app chạy được. Đuôi file quyết định dùng thư viện nào. */
enum class ModelFormat(val ext: String, val label: String) {
    /** File .task (hoặc .bin cũ): chạy bằng MediaPipe LLM Inference. */
    TASK("task", "MediaPipe"),
    /** File .litertlm (ví dụ Gemma 4 E2B): chạy bằng LiteRT-LM, nhanh hơn trên GPU đời mới. */
    LITERTLM("litertlm", "LiteRT-LM");

    companion object {
        /** Đoán định dạng theo tên file người dùng chọn; null nếu không phải file mô hình. */
        fun fromName(name: String): ModelFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".litertlm") -> LITERTLM
                lower.endsWith(".task") || lower.endsWith(".bin") -> TASK
                else -> null
            }
        }
    }
}

/**
 * Chạy mô hình hoàn toàn offline. Dùng chung một bản cho cả app (App.llm) để không nạp mô hình hai lần vào RAM.
 * Mỗi lúc chỉ có một mô hình: /sdcard/Android/data/vn.phamvu.trolyai/files/model.task (MediaPipe)
 * hoặc .../files/model.litertlm (LiteRT-LM). Chọn file mới trong "🧠 Mô hình" thì file cũ bị thay.
 * Cũng có thể adb push vào đúng một trong hai chỗ trên.
 */
class OfflineLlm(private val context: Context) : LlmEngine {
    // @Volatile vì nút dừng đọc nó từ luồng giao diện trong lúc luồng khác đang sinh chữ
    @Volatile private var runner: Runner? = null
    /** Cài đặt hiện tại; đặt qua [applySettings]. */
    @Volatile var settings: GenSettings = GenSettings()
        private set

    /** Đổi cài đặt. Đổi GPU / CPU thì bỏ mô hình đang nạp, câu sau sẽ nạp lại theo cài đặt mới. */
    suspend fun applySettings(s: GenSettings) {
        val reload = s.backend != settings.backend
        settings = s
        if (reload && runner != null) lock.withLock {
            runner?.close()
            runner = null
            backend = ""
        }
    }

    // Một mô hình chỉ chạy một câu một lúc; chat, học ban đêm và phỏng vấn dùng chung nên phải xếp hàng
    private val lock = Mutex()

    private val dir: File? get() = context.getExternalFilesDir(null)
    private fun fileFor(f: ModelFormat) = File(dir, "model.${f.ext}")

    /** File mô hình đang dùng. Nếu lỡ có cả hai thì lấy file mới chép vào sau cùng. */
    val modelFile: File
        get() = ModelFormat.entries.map(::fileFor).filter { it.length() > 0 }.maxByOrNull { it.lastModified() }
            ?: fileFor(ModelFormat.TASK)

    val format: ModelFormat get() = if (modelFile.name.endsWith(".litertlm")) ModelFormat.LITERTLM else ModelFormat.TASK

    fun hasModel(): Boolean = modelFile.exists() && modelFile.length() > 0

    /** Mô hình đang chạy bằng thư viện nào và trên GPU hay CPU, để hiện cho người dùng biết vì sao chậm. */
    @Volatile var backend: String = ""
        private set
    /** Thời gian nạp mô hình lần gần nhất (ms). */
    @Volatile var loadMs: Long = 0
        private set
    /** Dung lượng file mô hình (MB). */
    fun modelSizeMb(): Long = if (hasModel()) modelFile.length() / 1_000_000 else 0

    private fun ensureLoaded(): Runner {
        runner?.let { return it }
        require(hasModel()) {
            "Chưa có mô hình. Bấm \"🧠 Mô hình\" để chọn file .task hoặc .litertlm trong máy, " +
                "hoặc chép vào ${fileFor(ModelFormat.TASK).absolutePath}"
        }
        val t0 = System.currentTimeMillis()
        val r = when (format) {
            ModelFormat.TASK -> MediaPipeRunner(context, modelFile, settings.backend)
            ModelFormat.LITERTLM -> LiteRtLmRunner(context, modelFile, settings.backend) { settings }
        }
        loadMs = System.currentTimeMillis() - t0
        backend = "${format.label} ${r.backend}"
        return r.also { runner = it }
    }

    override suspend fun generate(prompt: String): String = lock.withLock {
        withContext(Dispatchers.Default) { ensureLoaded().generate(prompt, null) }
    }

    /**
     * Sinh chữ từng khúc để màn hình hiện dần câu trả lời, không phải đợi xong cả câu.
     * [onPartial] nhận cả đoạn đã sinh tới lúc đó.
     */
    override suspend fun generateStream(prompt: String, onPartial: (String) -> Unit): String = lock.withLock {
        withContext(Dispatchers.IO) { ensureLoaded().generate(prompt, onPartial) }
    }

    /** Lượt chat: với .litertlm thì dùng lại cuộc trò chuyện đang giữ trong mô hình (xem [LiteRtLmRunner.chat]). */
    override suspend fun chat(turn: ChatTurn, onPartial: (String) -> Unit, onThinking: (String) -> Unit): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val r = ensureLoaded()
            // Đo từ lúc có khoá (không tính lúc xếp hàng chờ việc khác), đếm số lần thư viện báo chữ mới
            val t0 = System.currentTimeMillis()
            var first = 0L
            var chunks = 0
            val out = r.chat(turn, { p ->
                if (chunks++ == 0) first = System.currentTimeMillis() - t0
                onPartial(p)
            }, onThinking)
            val fresh = r.lastChatFresh
            lastStats = GenStats(
                ttftMs = first,
                totalMs = System.currentTimeMillis() - t0,
                tokens = chunks,
                promptChars = if (fresh) turn.toGemmaPrompt().length else turn.user.length,
                freshChat = fresh,
                contextChars = r.contextChars,
            )
            out
        }
    }

    @Volatile override var lastStats: GenStats? = null
        private set

    override val supportsMedia: Boolean get() = (runner as? LiteRtLmRunner)?.media == true

    /** Không chờ khoá: phải dừng được ngay cả khi luồng khác đang giữ khoá để sinh chữ. */
    override fun stop() { runner?.stop() }

    /**
     * Cho màn 🎧 Chép lời: nạp mô hình (nếu chưa) rồi cho biết mô hình có nghe được âm thanh không.
     * Ném lỗi nếu chưa có mô hình hoặc nạp hỏng.
     */
    suspend fun canListen(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) { ensureLoaded() }
        supportsMedia
    }

    /** Nghe một đoạn âm thanh WAV và làm theo [prompt] (chép lời, dịch…), không đụng tới cuộc chat. */
    suspend fun listen(wav: ByteArray, prompt: String, onPartial: ((String) -> Unit)? = null): String = lock.withLock {
        withContext(Dispatchers.IO) { ensureLoaded().media(emptyList(), listOf(wav), prompt, onPartial) }
    }

    /** Xem một ảnh JPEG và làm theo [prompt] (ví dụ mô tả ảnh để tìm kiếm), không đụng tới cuộc chat. */
    suspend fun look(jpeg: ByteArray, prompt: String): String = lock.withLock {
        withContext(Dispatchers.IO) { ensureLoaded().media(listOf(jpeg), emptyList(), prompt, null) }
    }

    /** Nạp sẵn mô hình vào RAM khi mở app, để câu hỏi đầu tiên không phải đợi nạp vài chục giây. */
    suspend fun warmUp() {
        if (!hasModel() || runner != null) return
        lock.withLock { withContext(Dispatchers.Default) { ensureLoaded() } }
    }

    fun isLoaded(): Boolean = runner != null

    /**
     * Chép file mô hình người dùng chọn (qua trình chọn file của Android) vào chỗ của app rồi nạp thử.
     * Nhận cả .task (MediaPipe) và .litertlm (LiteRT-LM). File vài GB nên chép từng khúc và báo tiến độ 0..1
     * qua [onProgress]. Chép ra file tạm trước, xong mới thay file cũ, nên nếu hỏng giữa chừng thì mô hình cũ
     * vẫn dùng được.
     */
    suspend fun importModel(uri: Uri, onProgress: (Float) -> Unit): Unit = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = ""
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: ""
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        // Không đọc được tên file (hiếm) thì coi là .task như trước
        val newFormat = if (name.isEmpty()) ModelFormat.TASK else ModelFormat.fromName(name)
        requireNotNull(newFormat) {
            "\"$name\" không phải file mô hình. Hãy chọn file đuôi .task (ví dụ gemma-3n-E4B-it-int4.task) " +
                "hoặc .litertlm (ví dụ gemma-4-E2B-it.litertlm)."
        }
        val dir = dir ?: throw IOException("Không tìm thấy bộ nhớ của app")
        dir.mkdirs()
        val tmp = File(dir, "model.import.tmp")
        tmp.delete()
        // Cần chỗ cho file mới; file cũ chỉ bị xoá sau khi chép xong
        if (size > 0 && dir.usableSpace < size + 200L * 1024 * 1024) {
            throw IOException("Không đủ bộ nhớ trống: cần khoảng ${size / 1_000_000} MB, máy còn ${dir.usableSpace / 1_000_000} MB.")
        }
        try {
            val input = cr.openInputStream(uri) ?: throw IOException("Không mở được file đã chọn")
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastReport = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (size > 0) {
                            val pct = (done * 1000 / size).toInt()
                            if (pct != lastReport) { lastReport = pct; onProgress(done.toFloat() / size) }
                        }
                    }
                    out.fd.sync()
                }
            }
            if (tmp.length() == 0L) throw IOException("File đã chọn rỗng")
            if (size > 0 && tmp.length() != size) throw IOException("Chép thiếu dữ liệu (${tmp.length()}/$size byte). Thử lại nhé.")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        onProgress(1f)
        installModel(tmp, newFormat)
    }

    /** Chỗ để file đang tải về từ Hugging Face (tải tiếp được nếu bị ngắt), cùng thư mục với mô hình. */
    fun downloadFile(): File {
        val dir = dir ?: throw IOException("Không tìm thấy bộ nhớ của app")
        dir.mkdirs()
        return File(dir, "model.download.tmp")
    }

    /** Bộ nhớ trống ở chỗ lưu mô hình (byte). */
    fun freeSpace(): Long = dir?.usableSpace ?: 0L

    /**
     * Thay mô hình đang dùng bằng file [tmp] (đã chép hoặc tải xong) rồi nạp luôn.
     * Đợi câu đang trả lời (nếu có) xong rồi mới thay. Xoá cả hai loại file cũ để chỉ còn một mô hình
     * (mỗi file vài GB) và không nhầm file nào đang dùng.
     */
    suspend fun installModel(tmp: File, newFormat: ModelFormat): Unit = lock.withLock {
        runner?.close()
        runner = null
        backend = ""
        ModelFormat.entries.forEach { fileFor(it).delete() }
        if (!tmp.renameTo(fileFor(newFormat))) { tmp.delete(); throw IOException("Không thay được file mô hình") }
        withContext(Dispatchers.Default) { ensureLoaded() }
    }
}

/** Một mô hình đã nạp vào RAM, chạy bằng một thư viện cụ thể. */
internal interface Runner : AutoCloseable {
    /** "GPU" hoặc "CPU (GPU lỗi: ...)". */
    val backend: String

    /** Sinh câu trả lời; nếu có [onPartial] thì báo dần cả đoạn đã sinh. Chạy chặn luồng, gọi từ Dispatchers.IO/Default. */
    fun generate(prompt: String, onPartial: ((String) -> Unit)?): String

    /** Một lượt chat; mặc định không nhớ gì giữa các lượt, lịch sử nằm cả trong prompt, không nhận ảnh / âm thanh. */
    fun chat(turn: ChatTurn, onPartial: (String) -> Unit, onThinking: (String) -> Unit = {}): String {
        require(!turn.hasMedia) { ChatTurn.NO_MEDIA }
        return generate(turn.toGemmaPrompt(), onPartial)
    }

    /**
     * Một câu riêng có kèm ảnh (JPEG) hoặc âm thanh (WAV), ví dụ chép lời hay mô tả ảnh. Không đụng tới cuộc chat.
     * Chỉ mô hình nhận ảnh / âm thanh mới làm được.
     */
    fun media(images: List<ByteArray>, audios: List<ByteArray>, prompt: String, onPartial: ((String) -> Unit)?): String =
        throw IllegalStateException(ChatTurn.NO_MEDIA)

    /** Dừng câu đang sinh, nếu thư viện cho phép. */
    fun stop() {}

    /** Lượt chat vừa rồi có phải bắt đầu cuộc trò chuyện mới không (MediaPipe: luôn đọc lại cả prompt). */
    val lastChatFresh: Boolean get() = true

    /** Ước lượng số ký tự đang nằm trong bộ nhớ ngữ cảnh (MediaPipe: không giữ gì giữa các lượt). */
    val contextChars: Int get() = 0
}

/**
 * Thử GPU trước (Adreno của Snapdragon: nhanh hơn và mát máy hơn CPU).
 * Máy hay mô hình không chạy được GPU thì lùi về CPU thay vì báo lỗi.
 */
private inline fun <T> gpuThenCpu(pref: String, gpu: () -> T, cpu: () -> T): Pair<T, String> = when (pref) {
    // Bạn chọn hẳn trong ⚙️ Cài đặt
    "cpu" -> cpu() to "CPU"
    "gpu" -> gpu() to "GPU"
    else -> try {
        gpu() to "GPU"
    } catch (e: Throwable) {
        if (e is OutOfMemoryError) throw e
        cpu() to "CPU (GPU lỗi: ${e.message?.take(60)})"
    }
}

/** Mô hình .task chạy bằng MediaPipe LLM Inference. */
internal class MediaPipeRunner(context: Context, file: File, backendPref: String) : Runner {
    private val llm: LlmInference
    override val backend: String

    init {
        fun build(b: LlmInference.Backend) = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(4096) // tổng chữ vào + ra; đủ chỗ cho hồ sơ, quy tắc và trí nhớ
            .setPreferredBackend(b)
            .build()
        val (engine, b) = gpuThenCpu(backendPref,
            { LlmInference.createFromOptions(context, build(LlmInference.Backend.GPU)) },
            { LlmInference.createFromOptions(context, build(LlmInference.Backend.CPU)) },
        )
        llm = engine
        backend = b
    }

    override fun generate(prompt: String, onPartial: ((String) -> Unit)?): String {
        if (onPartial == null) return llm.generateResponse(prompt)
        // Mô hình tự gọi lại với từng khúc mới; ta cộng dồn rồi báo cả đoạn
        val sb = StringBuilder()
        val future = llm.generateResponseAsync(prompt) { partial, _ ->
            if (!partial.isNullOrEmpty()) {
                val all = synchronized(sb) { sb.append(partial).toString() }
                onPartial(all)
            }
        }
        val full = future.get()
        return full?.takeIf { it.isNotBlank() } ?: synchronized(sb) { sb.toString() }
    }

    override fun close() = llm.close()
}

/**
 * Mô hình .litertlm chạy bằng LiteRT-LM (ví dụ litert-community/gemma-4-E2B-it-litert-lm).
 * LiteRT-LM tự ghép khuôn hội thoại đúng cho từng mô hình (Gemma 3, Gemma 4…), nên ta bỏ các thẻ
 * <start_of_turn> mà prompt trong app đang viết sẵn cho Gemma 3, chỉ gửi phần lời của người dùng.
 * Chat thì giữ một Conversation qua các lượt (xem [chat]); các việc khác dùng Conversation riêng mỗi lần.
 */
internal class LiteRtLmRunner(
    context: Context,
    file: File,
    backendPref: String,
    private val settings: () -> GenSettings,
) : Runner {
    private val engine: Engine
    override val backend: String

    /** Mô hình nạp được cả phần xem ảnh và nghe âm thanh (ví dụ Gemma 3n, Gemma 4) hay chỉ có chữ. */
    val media: Boolean

    init {
        fun load(b: Backend, withMedia: Boolean) = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = b,
                // Như Gallery: phần xem ảnh chạy cùng loại với phần chữ, phần nghe chạy trên CPU
                visionBackend = if (withMedia) (if (b is Backend.CPU) Backend.CPU() else Backend.GPU()) else null,
                audioBackend = if (withMedia) Backend.CPU() else null,
                maxNumTokens = MAX_TOKENS, // giống bản MediaPipe
                // Lưu bộ đệm nhân GPU đã dịch, lần mở app sau nạp nhanh hơn
                cacheDir = context.cacheDir.path,
            )
        ).also { e ->
            try { e.initialize() } catch (t: Throwable) { runCatching { e.close() }; throw t }
        }
        // Thử nạp kèm phần ảnh / âm thanh; mô hình chỉ có chữ thì nạp lại không kèm. Nhớ kết quả cho từng file
        // để lần mở app sau không phải thử lại (mỗi lần thử hỏng tốn thời gian nạp).
        val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)
        val key = "media:${file.length()}:${file.lastModified()}"
        val tryMedia = prefs.getBoolean(key, true)
        fun loadBest(b: () -> Backend): Pair<Engine, Boolean> {
            if (tryMedia) {
                try { return load(b(), true) to true } catch (t: Throwable) { if (t is OutOfMemoryError) throw t }
            }
            return load(b(), false) to false
        }
        val (pair, b) = gpuThenCpu(backendPref, { loadBest { Backend.GPU() } }, { loadBest { Backend.CPU() } })
        engine = pair.first
        media = pair.second
        prefs.edit().putBoolean(key, media).apply()
        backend = if (media) "$b, xem ảnh + nghe" else b
    }

    /** Cuộc trò chuyện chat đang giữ trong mô hình (bộ nhớ KV đã tính sẵn), cùng phần system đã dùng để tạo nó. */
    @Volatile private var chatConv: Conversation? = null
    private var chatSystem: String? = null
    /** Cài đặt sinh chữ đã dùng khi tạo cuộc trò chuyện; đổi thì tạo cuộc mới. */
    private var chatSettings: GenSettings? = null
    /** Ước lượng độ dài cuộc trò chuyện (ký tự) để làm mới trước khi tràn [MAX_TOKENS]. */
    private var chatChars = 0
    /** Cuộc trò chuyện đang sinh chữ, để nút dừng huỷ được. */
    @Volatile private var active: Conversation? = null
    @Volatile private var cancelled = false
    override var lastChatFresh = true
        private set
    override val contextChars: Int get() = if (chatConv == null) 0 else (chatSystem?.length ?: 0) + chatChars

    /** Câu một lần (học ban đêm, phỏng vấn): tạo cuộc trò chuyện riêng rồi bỏ, không đụng tới cuộc chat. */
    override fun generate(prompt: String, onPartial: ((String) -> Unit)?): String =
        engine.createConversation().use { run(it, Contents.of(stripGemmaTurns(prompt)), onPartial) }

    /**
     * Chép lời / dịch âm thanh (như Audio Scribe của Gallery) hay mô tả ảnh: cuộc trò chuyện riêng, ảnh / âm thanh trước
     * rồi tới lời dặn. Chọn chữ chắc nhất ở mỗi bước (top-k 1) để kết quả sát thực tế, không "sáng tạo" thêm.
     */
    override fun media(images: List<ByteArray>, audios: List<ByteArray>, prompt: String, onPartial: ((String) -> Unit)?): String {
        require(media) { ChatTurn.NO_MEDIA }
        val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0))
        val parts = images.map { Content.ImageBytes(it) } + audios.map { Content.AudioBytes(it) } + Content.Text(prompt)
        return engine.createConversation(config).use { run(it, Contents.of(parts), onPartial) }
    }

    /**
     * Cách Google AI Edge Gallery làm cho nhanh: giữ một Conversation sống qua các lượt. Mô hình đã "đọc" phần
     * system và các câu trước rồi, nên mỗi lượt chỉ phải đọc lời nhắn mới thay vì cả prompt dài, chữ đầu ra nhanh hơn.
     * Tạo cuộc trò chuyện mới (kèm vài câu gần đây làm lịch sử) khi: chưa có, phần system đổi (quy tắc, hồ sơ, tên…),
     * sắp đầy bộ nhớ ngữ cảnh, hoặc lượt trước bị dừng / lỗi.
     */
    override fun chat(turn: ChatTurn, onPartial: (String) -> Unit, onThinking: (String) -> Unit): String {
        require(!turn.hasMedia || media) { ChatTurn.NO_MEDIA }
        val gen = settings()
        val fresh = chatConv == null || chatSystem != turn.system || chatSettings != gen ||
            turn.system.length + chatChars + turn.user.length + turn.mediaChars > CHAT_CHAR_BUDGET
        if (fresh) newChat(turn)
        lastChatFresh = fresh
        // Như Gallery: ảnh và âm thanh đặt trước chữ để mô hình đọc theo đúng thứ tự
        val contents = Contents.of(
            turn.images.map { Content.ImageBytes(it) } + turn.audios.map { Content.AudioBytes(it) } + Content.Text(turn.user)
        )
        val used = turn.user.length + turn.mediaChars
        return try {
            val out = run(chatConv!!, contents, onPartial, onThinking, gen.thinking)
            chatChars += used + out.length
            // Bị dừng giữa chừng: mô hình còn giữ nửa câu trả lời, lượt sau làm lại cho sạch
            if (cancelled) dropChat()
            out
        } catch (e: Throwable) {
            dropChat()
            if (fresh || e is OutOfMemoryError) throw e
            // Thường do cuộc trò chuyện cũ đã đầy: làm lại một lần với cuộc trò chuyện mới
            newChat(turn)
            lastChatFresh = true
            run(chatConv!!, contents, onPartial, onThinking, gen.thinking).also { chatChars += used + it.length }
        }
    }

    private fun newChat(turn: ChatTurn) {
        dropChat()
        val gen = settings()
        chatConv = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(turn.system),
                initialMessages = historyMessages(turn.history),
                samplerConfig = SamplerConfig(topK = gen.topK, topP = gen.topP.toDouble(), temperature = gen.temperature.toDouble()),
            )
        )
        chatSystem = turn.system
        chatSettings = gen
        chatChars = turn.history.sumOf { it.second.length }
    }

    private fun dropChat() {
        chatConv?.let { runCatching { it.close() } }
        chatConv = null
        chatSystem = null
        chatSettings = null
        chatChars = 0
    }

    override fun stop() {
        // Chỉ dừng câu chat; câu của chế độ phỏng vấn hay học ban đêm cứ để chạy tiếp
        val c = active ?: return
        if (c !== chatConv) return
        cancelled = true
        runCatching { c.cancelProcess() }
    }

    /** Sinh chữ trên [conversation], báo dần qua [onPartial]. Bị dừng thì trả phần đã sinh, không báo lỗi. */
    private fun run(
        conversation: Conversation,
        contents: Contents,
        onPartial: ((String) -> Unit)?,
        onThinking: (String) -> Unit = {},
        thinking: Boolean = false,
    ): String {
        val sb = StringBuilder()
        val thought = StringBuilder()
        val done = CountDownLatch(1)
        var error: Throwable? = null
        cancelled = false
        active = conversation
        try {
            conversation.sendMessageAsync(contents, object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Gemma 4 ở chế độ suy nghĩ gửi phần nghĩ qua kênh "thought" (như Gallery), hiện riêng
                    message.channels[THOUGHT_CHANNEL]?.takeIf { it.isNotEmpty() }?.let { t ->
                        onThinking(synchronized(thought) { thought.append(t).toString() })
                    }
                    // Mỗi lần nhận một khúc chữ mới
                    val chunk = message.toString()
                    if (chunk.isEmpty()) return
                    val all = synchronized(sb) { sb.append(chunk).toString() }
                    onPartial?.invoke(all)
                }

                override fun onDone() = done.countDown()

                override fun onError(throwable: Throwable) {
                    // Bấm dừng: LiteRT-LM báo CancellationException, coi như xong với phần đã có
                    if (throwable !is CancellationException) error = throwable
                    done.countDown()
                }
            }, if (thinking) mapOf<String, Any>("enable_thinking" to true) else emptyMap())
            done.await()
        } finally {
            active = null
        }
        error?.let { throw it }
        return synchronized(sb) { sb.toString() }
    }

    override fun close() {
        dropChat()
        engine.close()
    }

    companion object {
        const val MAX_TOKENS = 4096
        const val THOUGHT_CHANNEL = "thought"
        /**
         * Khoảng 4096 token. Tiếng Việt thường 2,5–3 ký tự một token; để dư chỗ cho câu trả lời
         * nên làm mới cuộc trò chuyện khi tổng chữ vượt mức này.
         */
        const val CHAT_CHAR_BUDGET = 7000

        /**
         * Lịch sử cho cuộc trò chuyện mới. Khuôn chat của Gemma đòi lượt người dùng và lượt mô hình xen kẽ,
         * bắt đầu bằng người dùng, nên bỏ các câu trợ lý nói trước và gộp các câu liền nhau cùng một người.
         */
        internal fun historyMessages(history: List<Pair<Boolean, String>>): List<Message> {
            val merged = mutableListOf<Pair<Boolean, String>>()
            for ((fromUser, text) in history.dropWhile { !it.first }) {
                val last = merged.lastOrNull()
                if (last != null && last.first == fromUser) merged[merged.size - 1] = fromUser to last.second + "\n" + text
                else merged += fromUser to text
            }
            // Câu cuối phải là của mô hình, vì lượt kế tiếp là lời nhắn mới của người dùng
            if (merged.lastOrNull()?.first == true) merged.removeAt(merged.size - 1)
            return merged.map { (fromUser, text) ->
                if (fromUser) Message.user(text)
                else Message.model(text)
            }
        }
    }
}

/**
 * Prompt trong app có dạng "<start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n" (khuôn Gemma 3).
 * Bỏ lớp vỏ đó để LiteRT-LM tự bọc bằng khuôn đúng của mô hình đang dùng.
 */
internal fun stripGemmaTurns(prompt: String): String {
    var s = prompt.trim()
    s = s.removePrefix("<start_of_turn>user").trimStart()
    val modelTurn = s.lastIndexOf("<start_of_turn>model")
    if (modelTurn >= 0) s = s.substring(0, modelTurn)
    s = s.trimEnd().removeSuffix("<end_of_turn>").trimEnd()
    return s
}
