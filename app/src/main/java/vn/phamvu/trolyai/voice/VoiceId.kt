package vn.phamvu.trolyai.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.VoiceProfile
import java.io.File
import kotlin.math.sqrt

/**
 * Nhận diện giọng chủ máy, chạy offline bằng sherpa-onnx.
 * Mô hình "speaker embedding" biến vài giây giọng nói thành một dãy số (dấu vân giọng).
 * Hai đoạn cùng một người nói sẽ cho hai dãy số gần nhau (độ giống gần 1).
 *
 * Mô hình: tải file .onnx trong mục "speaker-recongition-models" ở GitHub releases của k2-fsa/sherpa-onnx
 * (gợi ý: 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx), chép vào
 * /sdcard/Android/data/vn.phamvu.trolyai/files/speaker.onnx
 */
class VoiceId(private val ctx: Context, private val db: AppDb) {
    private val modelFile get() = File(ctx.getExternalFilesDir(null), "speaker.onnx")
    private var extractor: SpeakerEmbeddingExtractor? = null

    /** Ngưỡng giống nhau. Cao hơn thì khó bị giả hơn nhưng dễ từ chối nhầm chính bạn. */
    var threshold = 0.6f

    fun modelReady() = modelFile.exists()
    fun micAllowed() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    suspend fun enrolled() = db.voice().get() != null

    /** Thu âm [seconds] giây, 16 kHz, một kênh. */
    @SuppressLint("MissingPermission")
    suspend fun record(seconds: Int): FloatArray = withContext(Dispatchers.IO) {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        val pcm = ShortArray(rate * seconds)
        rec.startRecording()
        var read = 0
        while (read < pcm.size) {
            val n = rec.read(pcm, read, minOf(1600, pcm.size - read))
            if (n <= 0) break
            read += n
        }
        rec.stop(); rec.release()
        FloatArray(read) { pcm[it] / 32768f }
    }

    private suspend fun embed(samples: FloatArray): FloatArray = withContext(Dispatchers.Default) {
        val ex = extractor ?: SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = modelFile.absolutePath, numThreads = 2)
        ).also { extractor = it }
        val stream = ex.createStream()
        stream.acceptWaveform(samples, 16_000)
        stream.inputFinished()
        val v = ex.compute(stream)
        stream.release()
        normalize(v)
    }

    /** Đăng ký: lấy trung bình dấu vân giọng của nhiều câu mẫu. */
    suspend fun enroll(clips: List<FloatArray>) {
        val vs = clips.map { embed(it) }
        val avg = FloatArray(vs[0].size) { i -> vs.sumOf { it[i].toDouble() }.toFloat() / vs.size }
        db.voice().save(VoiceProfile(embedding = normalize(avg).joinToString(",")))
    }

    /** Độ giống (0 đến 1) giữa giọng vừa thu và giọng chủ máy. */
    suspend fun score(samples: FloatArray): Float {
        val saved = db.voice().get()?.embedding?.split(",")?.map { it.toFloat() } ?: return 0f
        val v = embed(samples)
        return v.indices.sumOf { (v[it] * saved[it]).toDouble() }.toFloat()
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }
}
