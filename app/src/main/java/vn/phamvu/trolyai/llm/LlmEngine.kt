package vn.phamvu.trolyai.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Giao diện chung để sau này đổi sang llama.cpp hoặc gọi API online mà không sửa phần còn lại. */
interface LlmEngine {
    suspend fun generate(prompt: String): String
}

/**
 * Chạy mô hình hoàn toàn offline bằng MediaPipe LLM Inference.
 * Chép file mô hình (.task) vào: /sdcard/Android/data/vn.phamvu.trolyai/files/model.task
 */
class MediaPipeEngine(private val context: Context) : LlmEngine {
    private var llm: LlmInference? = null
    private val lock = Mutex() // chat và việc học ban đêm dùng chung một mô hình, lần lượt từng việc

    val modelFile: File get() = File(context.getExternalFilesDir(null), "model.task")

    private fun ensureLoaded(): LlmInference {
        llm?.let { return it }
        require(modelFile.exists()) { "Chưa có mô hình. Hãy chép file .task vào ${modelFile.absolutePath}" }
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
}
