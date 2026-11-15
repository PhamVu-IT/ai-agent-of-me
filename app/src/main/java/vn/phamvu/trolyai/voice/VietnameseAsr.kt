package vn.phamvu.trolyai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import vn.phamvu.trolyai.models.AsrModelFiles
import java.io.File
import kotlin.math.sqrt

/**
 * Nghe tiếng Việt hoàn toàn offline bằng sherpa-onnx, không cần Google hay mạng.
 *
 * Mô hình: trong GitHub releases của k2-fsa/sherpa-onnx, mục "asr-models", tải
 * sherpa-onnx-zipformer-vi-int8-2025-04-20.tar.bz2 (hoặc bản sherpa-onnx-zipformer-vi-2025-04-20 /
 * sherpa-onnx-zipformer-vi-30M-int8-2026-02-09), rồi chọn ngay trong app ở mục 🧠 Mô hình, không cần giải nén.
 * App chép tokens.txt và encoder / decoder / joiner (.onnx) vào
 * /sdcard/Android/data/vn.phamvu.trolyai/files/asr-vi/
 *
 * Mô hình này không nghe liên tục (non-streaming): app thu âm đến khi bạn ngừng nói khoảng 1 giây,
 * rồi nhận dạng cả câu một lần.
 */
class VietnameseAsr(ctx: Context) : AsrModelFiles(ctx, "asr-vi", "nghe tiếng Việt",
    "sherpa-onnx-zipformer-vi-int8-2025-04-20.tar.bz2") {

    private var cached: OfflineRecognizer? = null
    private var cachedKey = ""

    /** Mô hình nạp một lần rồi giữ lại, để lần nói sau không phải chờ nạp. Chọn mô hình khác thì nạp lại. */
    @Synchronized
    fun recognizer(): OfflineRecognizer {
        require(modelReady()) { "Chưa có mô hình nghe tiếng Việt trong ${dir.absolutePath}" }
        val key = dir.listFiles().orEmpty().sortedBy { it.name }.joinToString { "${it.name}:${it.lastModified()}" }
        cached?.let { if (key == cachedKey) return it else release() }
        val r = SherpaLibs.run(ctx) { OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = find("encoder")!!.absolutePath,
                    decoder = find("decoder")!!.absolutePath,
                    joiner = find("joiner")!!.absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
            ),
        )) }
        cached = r
        cachedKey = key
        return r
    }

    /** Bỏ mô hình đang nạp (khi vừa chọn mô hình khác). */
    @Synchronized
    fun release() { cached?.release(); cached = null }

    /**
     * Thu âm một câu: chờ bạn bắt đầu nói (tối đa [waitMs]), dừng khi im lặng khoảng 1 giây hoặc sau [maxMs].
     * Trả về âm thanh 16 kHz, hoặc null nếu không nghe thấy ai nói. Chạy trên luồng nền.
     */
    @SuppressLint("MissingPermission") // người gọi đã kiểm tra quyền micro
    suspend fun record(onSpeechStart: () -> Unit, waitMs: Int = 7_000, maxMs: Int = 15_000): FloatArray? {
        val rate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("Không mở được micro") }
        val frame = ShortArray(rate / 20) // 50 ms
        val chunks = ArrayList<FloatArray>()
        var noise = 0f          // mức ồn nền, đo trong lúc chưa nói
        var frames = 0
        var started = false
        var silentMs = 0
        var elapsedMs = 0
        try {
            rec.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val chunk = FloatArray(n) { frame[it] / 32768f }
                val ms = n * 1000 / rate
                elapsedMs += ms
                var sum = 0f
                for (s in chunk) sum += s * s
                val rms = sqrt(sum / n)
                frames++
                if (!started) {
                    // Bỏ 0,2 giây đầu (tiếng "bật micro"), sau đó tìm tiếng nói to hơn hẳn tiếng ồn nền
                    if (frames <= 4) { noise = if (noise == 0f) rms else (noise + rms) / 2; continue }
                    chunks += chunk
                    if (chunks.size > 6) chunks.removeAt(0) // giữ 0,3 giây trước khi nói để không mất chữ đầu
                    if (rms > maxOf(0.015f, noise * 3f)) { started = true; onSpeechStart() }
                    else {
                        noise = noise * 0.95f + rms * 0.05f
                        if (elapsedMs > waitMs) return null
                    }
                } else {
                    chunks += chunk
                    silentMs = if (rms > maxOf(0.01f, noise * 2f)) 0 else silentMs + ms
                    if (silentMs >= 1_000 || elapsedMs >= maxMs) break
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
        if (!started) return null
        val out = FloatArray(chunks.sumOf { it.size })
        var i = 0
        for (c in chunks) { c.copyInto(out, i); i += c.size }
        return out
    }

    /** Nhận dạng một câu đã thu. Chạy trên luồng nền. */
    fun transcribe(audio: FloatArray): String {
        val r = recognizer()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            r.decode(stream)
            return r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
