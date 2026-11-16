package vn.phamvu.trolyai.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Ảnh và âm thanh gửi cho mô hình, làm theo Google AI Edge Gallery: ảnh thu nhỏ rồi nén JPEG,
 * âm thanh thu 16 kHz mono 16-bit, tối đa 30 giây, gửi dạng WAV.
 */
object ChatMedia {
    const val SAMPLE_RATE = 16_000
    const val MAX_AUDIO_SEC = 30
    const val MAX_IMAGES = 5
    /** Cạnh dài nhất của ảnh gửi cho mô hình; mô hình tự thu nhỏ tiếp, ảnh to hơn chỉ tốn RAM. */
    private const val MAX_SIDE = 1024

    /** Đọc ảnh từ [uri] (thư viện ảnh hoặc máy ảnh), xoay đúng chiều, thu nhỏ cạnh dài nhất còn [MAX_SIDE]. */
    suspend fun loadImage(ctx: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                // Ảnh phần cứng không đọc được điểm ảnh để nén lại
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalArgumentException("Không đọc được ảnh")
        }
    }

    fun toJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    /**
     * Thu âm cho tới khi [shouldStop] trả true hoặc đủ [MAX_AUDIO_SEC] giây. [onProgress] nhận số mili giây đã thu
     * và độ to 0..1 để vẽ thanh âm lượng. Trả về dữ liệu PCM 16-bit.
     */
    @SuppressLint("MissingPermission") // màn hình đã xin quyền micro khi mở app
    suspend fun record(shouldStop: () -> Boolean, onProgress: (Long, Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE / 5 * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Không mở được micro. Kiểm tra quyền micro của app.")
        }
        val out = ByteArrayOutputStream()
        val buf = ShortArray(SAMPLE_RATE / 10) // 0,1 giây
        val maxBytes = SAMPLE_RATE * 2 * MAX_AUDIO_SEC
        try {
            rec.startRecording()
            while (!shouldStop() && out.size() < maxBytes) {
                coroutineContext.ensureActive()
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    out.write(v and 0xff)
                    out.write((v shr 8) and 0xff)
                    peak = max(peak, abs(v))
                }
                onProgress(out.size() * 1000L / (SAMPLE_RATE * 2), peak / 32768f)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        out.toByteArray()
    }

    /** Thêm đầu file WAV (44 byte) vào dữ liệu PCM 16-bit mono, như genByteArrayForWav của Gallery. */
    fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val h = ByteArray(44)
        fun putInt(at: Int, v: Int) { for (i in 0..3) h[at + i] = (v shr (8 * i) and 0xff).toByte() }
        fun putShort(at: Int, v: Int) { h[at] = (v and 0xff).toByte(); h[at + 1] = (v shr 8 and 0xff).toByte() }
        "RIFF".forEachIndexed { i, c -> h[i] = c.code.toByte() }
        putInt(4, pcm.size + 36)
        "WAVEfmt ".forEachIndexed { i, c -> h[8 + i] = c.code.toByte() }
        putInt(16, 16)           // độ dài phần fmt
        putShort(20, 1)          // PCM
        putShort(22, 1)          // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2) // byte mỗi giây
        putShort(32, 2)          // byte mỗi mẫu
        putShort(34, 16)         // bit mỗi mẫu
        "data".forEachIndexed { i, c -> h[36 + i] = c.code.toByte() }
        putInt(40, pcm.size)
        return h + pcm
    }

    /** Phát lại đoạn PCM vừa thu (nút ▶). Trả về AudioTrack để dừng khi cần. */
    fun play(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): AudioTrack? {
        if (pcm.isEmpty()) return null
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        return track
    }

    fun durationMs(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): Long = pcm.size * 1000L / (sampleRate * 2)
}

/** Ảnh và đoạn ghi âm đi kèm một tin nhắn, chỉ giữ trong lần mở app này (không lưu ra bộ nhớ máy). */
class MessageMedia(val images: List<Bitmap>, val audioPcm: ByteArray?)
