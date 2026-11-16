package vn.phamvu.trolyai.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.coroutineContext

/** Giao diện chung để sau này đổi sang llama.cpp hoặc gọi API online mà không sửa phần còn lại. */
interface LlmEngine {
    suspend fun generate(prompt: String): String

    /** Như [generate] nhưng báo dần phần chữ đã sinh qua [onPartial] (toàn bộ chữ tính tới lúc đó). */
    suspend fun generateStream(prompt: String, onPartial: (String) -> Unit): String =
        generate(prompt).also(onPartial)
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
    private var runner: Runner? = null
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
            ModelFormat.TASK -> MediaPipeRunner(context, modelFile)
            ModelFormat.LITERTLM -> LiteRtLmRunner(context, modelFile)
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
    suspend fun importModel(uri: Uri, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
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
        // Đợi câu đang trả lời (nếu có) xong rồi mới thay mô hình. Xoá cả hai loại file cũ để
        // chỉ còn một mô hình (mỗi file vài GB) và không nhầm file nào đang dùng.
        lock.withLock {
            runner?.close()
            runner = null
            backend = ""
            ModelFormat.entries.forEach { fileFor(it).delete() }
            if (!tmp.renameTo(fileFor(newFormat))) { tmp.delete(); throw IOException("Không thay được file mô hình") }
            withContext(Dispatchers.Default) { ensureLoaded() }
        }
    }
}

/** Một mô hình đã nạp vào RAM, chạy bằng một thư viện cụ thể. */
internal interface Runner : AutoCloseable {
    /** "GPU" hoặc "CPU (GPU lỗi: ...)". */
    val backend: String

    /** Sinh câu trả lời; nếu có [onPartial] thì báo dần cả đoạn đã sinh. Chạy chặn luồng, gọi từ Dispatchers.IO/Default. */
    fun generate(prompt: String, onPartial: ((String) -> Unit)?): String
}

/**
 * Thử GPU trước (Adreno của Snapdragon: nhanh hơn và mát máy hơn CPU).
 * Máy hay mô hình không chạy được GPU thì lùi về CPU thay vì báo lỗi.
 */
private inline fun <T> gpuThenCpu(gpu: () -> T, cpu: () -> T): Pair<T, String> =
    try {
        gpu() to "GPU"
    } catch (e: Throwable) {
        if (e is OutOfMemoryError) throw e
        cpu() to "CPU (GPU lỗi: ${e.message?.take(60)})"
    }

/** Mô hình .task chạy bằng MediaPipe LLM Inference. */
internal class MediaPipeRunner(context: Context, file: File) : Runner {
    private val llm: LlmInference
    override val backend: String

    init {
        fun build(b: LlmInference.Backend) = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(4096) // tổng chữ vào + ra; đủ chỗ cho hồ sơ, quy tắc và trí nhớ
            .setPreferredBackend(b)
            .build()
        val (engine, b) = gpuThenCpu(
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
 * Mỗi câu hỏi dùng một Conversation mới, giống MediaPipe: không nhớ câu trước (lịch sử đã nằm trong prompt).
 */
internal class LiteRtLmRunner(context: Context, file: File) : Runner {
    private val engine: Engine
    override val backend: String

    init {
        fun load(b: Backend) = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = b,
                maxNumTokens = 4096, // giống bản MediaPipe
                // Lưu bộ đệm nhân GPU đã dịch, lần mở app sau nạp nhanh hơn
                cacheDir = context.cacheDir.path,
            )
        ).also { e ->
            try { e.initialize() } catch (t: Throwable) { runCatching { e.close() }; throw t }
        }
        val (e, b) = gpuThenCpu({ load(Backend.GPU()) }, { load(Backend.CPU()) })
        engine = e
        backend = b
    }

    override fun generate(prompt: String, onPartial: ((String) -> Unit)?): String {
        val text = stripGemmaTurns(prompt)
        engine.createConversation().use { conversation ->
            val sb = StringBuilder()
            val done = CountDownLatch(1)
            var error: Throwable? = null
            conversation.sendMessageAsync(text, object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Mỗi lần nhận một khúc chữ mới
                    val chunk = message.toString()
                    if (chunk.isEmpty()) return
                    val all = synchronized(sb) { sb.append(chunk).toString() }
                    onPartial?.invoke(all)
                }

                override fun onDone() = done.countDown()

                override fun onError(throwable: Throwable) {
                    error = throwable
                    done.countDown()
                }
            })
            done.await()
            error?.let { throw it }
            return synchronized(sb) { sb.toString() }
        }
    }

    override fun close() = engine.close()
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
