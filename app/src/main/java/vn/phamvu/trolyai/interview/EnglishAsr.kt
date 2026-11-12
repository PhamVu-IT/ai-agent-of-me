package vn.phamvu.trolyai.interview

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Nghe tiếng Anh liên tục, offline, bằng sherpa-onnx (cùng thư viện với nhận giọng chủ máy).
 * Khác nhận dạng giọng nói của Google: không kêu "bíp" mỗi lần bắt đầu nghe, không tự dừng sau mỗi câu.
 *
 * Mô hình: trong GitHub releases của k2-fsa/sherpa-onnx, mục "asr-models", tải
 * sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2, giải nén rồi chép cả thư mục vào
 * /sdcard/Android/data/vn.phamvu.trolyai/files/asr-en/
 * Trong thư mục cần có tokens.txt và các file encoder / decoder / joiner (.onnx). Tên file không cần đổi.
 */
class EnglishAsr(ctx: Context) {
    val dir = File(ctx.getExternalFilesDir(null), "asr-en")

    /** Tìm file .onnx có chứa [part] trong tên, ưu tiên bản int8 (nhẹ và nhanh hơn). */
    private fun find(part: String): File? {
        val files = dir.listFiles { f -> f.name.endsWith(".onnx") && f.name.contains(part) } ?: return null
        return files.firstOrNull { it.name.contains(".int8.") } ?: files.firstOrNull()
    }

    fun modelReady() = File(dir, "tokens.txt").exists() &&
        find("encoder") != null && find("decoder") != null && find("joiner") != null

    fun create(): OnlineRecognizer {
        require(modelReady()) { "Chưa có mô hình nghe tiếng Anh trong ${dir.absolutePath}" }
        return OnlineRecognizer(config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = find("encoder")!!.absolutePath,
                    decoder = find("decoder")!!.absolutePath,
                    joiner = find("joiner")!!.absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
            ),
            enableEndpoint = true,
            // Coi là hết câu khi im lặng 1 giây sau khi có người nói, hoặc câu dài quá 20 giây
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.0f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
        ))
    }

    companion object { const val SAMPLE_RATE = 16_000 }
}
