package vn.phamvu.trolyai.scribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Đọc một file âm thanh bất kỳ máy giải mã được (mp3, m4a, aac, ogg, opus, wav, flac, cả phần tiếng của video)
 * thành PCM 16-bit mono 16 kHz, đúng dạng mô hình AI và bộ nhận dạng tiếng Việt cần.
 */
object AudioDecoder {
    const val SAMPLE_RATE = 16_000

    /** Tối đa 2 giờ, để file quá dài không làm hết RAM (2 giờ ≈ 230 MB PCM). */
    private const val MAX_SEC = 2 * 60 * 60

    /** Trả về mẫu âm thanh 16 kHz. [onProgress] nhận 0..1 theo thời lượng đã giải mã. */
    suspend fun decode(ctx: Context, uri: Uri, onProgress: (Float) -> Unit = {}): ShortArray = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("File này không có phần âm thanh")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > MAX_SEC * 1_000_000L) throw IOException("File dài quá 2 giờ, hãy cắt nhỏ trước")
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            val out = ShortBuilder()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                if (durationUs > 0) onProgress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(o)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val frame = ShortArray(sb.remaining())
                                sb.get(frame)
                                out.add(toMono16k(frame, channels, rate))
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            onProgress(1f)
            out.toArray().also { if (it.isEmpty()) throw IOException("Không đọc được âm thanh trong file") }
        } finally {
            ex.release()
        }
    }

    /**
     * Gộp các kênh thành một rồi đổi tần số mẫu về 16 kHz (nội suy tuyến tính). Mỗi khúc giải mã được đổi riêng,
     * chỗ nối giữa hai khúc có thể lệch một mẫu, không nghe ra và không ảnh hưởng nhận dạng.
     */
    private fun toMono16k(pcm: ShortArray, channels: Int, rate: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        val mono = if (ch == 1) pcm else ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until ch) sum += pcm[f * ch + c]
            (sum / ch).toShort()
        }
        if (rate == SAMPLE_RATE || rate <= 0) return mono
        val outLen = (mono.size.toLong() * SAMPLE_RATE / rate).toInt()
        val step = rate.toDouble() / SAMPLE_RATE
        return ShortArray(outLen) { i ->
            val pos = i * step
            val a = pos.toInt().coerceAtMost(mono.size - 1)
            val b = (a + 1).coerceAtMost(mono.size - 1)
            val t = pos - a
            (mono[a] * (1 - t) + mono[b] * t).toInt().toShort()
        }
    }

    /** Mảng short tự lớn dần, đỡ tốn RAM hơn List<Short>. */
    private class ShortBuilder {
        private var data = ShortArray(SAMPLE_RATE * 60)
        private var size = 0
        fun add(s: ShortArray) {
            if (size + s.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + s.size))
            s.copyInto(data, size)
            size += s.size
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }
}
