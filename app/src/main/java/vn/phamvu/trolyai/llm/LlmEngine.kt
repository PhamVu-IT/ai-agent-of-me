package vn.phamvu.trolyai.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Giao diện chung để sau này đổi sang llama.cpp hoặc gọi API online mà không sửa phần còn lại. */
interface LlmEngine {
    suspend fun generate(prompt: String): String

    /** Như [generate] nhưng báo dần phần chữ đã sinh qua [onPartial] (toàn bộ chữ tính tới lúc đó). */
    suspend fun generateStream(prompt: String, onPartial: (String) -> Unit): String =
        generate(prompt).also(onPartial)
}

/**
 * Chạy mô hình hoàn toàn offline bằng MediaPipe LLM Inference.
 * Dùng chung một bản cho cả app (App.llm) để không nạp mô hình hai lần vào RAM.
 * File mô hình nằm ở: /sdcard/Android/data/vn.phamvu.trolyai/files/model.task
 * Có hai cách đưa vào: chọn file ngay trong app (importModel) hoặc adb push vào đúng chỗ trên.
 */
class MediaPipeEngine(private val context: Context) : LlmEngine {
    private var llm: LlmInference? = null
    // Một mô hình chỉ chạy một câu một lúc; chat, học ban đêm và phỏng vấn dùng chung nên phải xếp hàng
    private val lock = Mutex()

    val modelFile: File get() = File(context.getExternalFilesDir(null), "model.task")

    fun hasModel(): Boolean = modelFile.exists() && modelFile.length() > 0

    private fun ensureLoaded(): LlmInference {
        llm?.let { return it }
        require(hasModel()) { "Chưa có mô hình. Bấm \"🧠 Mô hình\" để chọn file .task trong máy, hoặc chép vào ${modelFile.absolutePath}" }
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(4096) // tổng chữ vào + ra; đủ chỗ cho hồ sơ, quy tắc và trí nhớ
            // Chạy trên GPU (Adreno của Snapdragon): nhanh hơn và mát máy hơn CPU
            .setPreferredBackend(LlmInference.Backend.GPU)
            .build()
        return LlmInference.createFromOptions(context, opts).also { llm = it }
    }

    override suspend fun generate(prompt: String): String = lock.withLock {
        withContext(Dispatchers.Default) { ensureLoaded().generateResponse(prompt) }
    }

    /**
     * Sinh chữ từng khúc để màn hình hiện dần câu trả lời, không phải đợi xong cả câu.
     * Mô hình tự gọi lại với từng khúc mới; ta cộng dồn rồi báo cả đoạn qua [onPartial].
     */
    override suspend fun generateStream(prompt: String, onPartial: (String) -> Unit): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            val future = ensureLoaded().generateResponseAsync(prompt) { partial, _ ->
                if (!partial.isNullOrEmpty()) {
                    synchronized(sb) { sb.append(partial) }
                    onPartial(synchronized(sb) { sb.toString() })
                }
            }
            val full = future.get()
            full?.takeIf { it.isNotBlank() } ?: synchronized(sb) { sb.toString() }
        }
    }

    /** Nạp sẵn mô hình vào RAM khi mở app, để câu hỏi đầu tiên không phải đợi nạp vài chục giây. */
    suspend fun warmUp() {
        if (!hasModel() || llm != null) return
        lock.withLock { withContext(Dispatchers.Default) { ensureLoaded() } }
    }

    fun isLoaded(): Boolean = llm != null

    /**
     * Chép file mô hình người dùng chọn (qua trình chọn file của Android) vào chỗ của app rồi nạp thử.
     * File vài GB nên chép từng khúc và báo tiến độ 0..1 qua [onProgress]. Chép ra file tạm trước,
     * xong mới thay file cũ, nên nếu hỏng giữa chừng thì mô hình cũ vẫn dùng được.
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
        val lower = name.lowercase()
        require(name.isEmpty() || lower.endsWith(".task") || lower.endsWith(".bin")) {
            if (lower.endsWith(".litertlm")) "File .litertlm chưa dùng được với bản MediaPipe trong app. Hãy tải bản .task của mô hình."
            else "\"$name\" không phải file mô hình. Hãy chọn file đuôi .task (ví dụ gemma-3n-E4B-it-int4.task)."
        }
        val dir = modelFile.parentFile ?: throw IOException("Không tìm thấy bộ nhớ của app")
        dir.mkdirs()
        val tmp = File(dir, "model.task.tmp")
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
        // Đợi câu đang trả lời (nếu có) xong rồi mới thay mô hình
        lock.withLock {
            llm?.close()
            llm = null
            modelFile.delete()
            if (!tmp.renameTo(modelFile)) { tmp.delete(); throw IOException("Không thay được file mô hình") }
            withContext(Dispatchers.Default) { ensureLoaded() }
        }
    }
}
