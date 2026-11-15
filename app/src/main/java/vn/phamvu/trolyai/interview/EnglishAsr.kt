package vn.phamvu.trolyai.interview

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import vn.phamvu.trolyai.models.AsrModelFiles
import vn.phamvu.trolyai.voice.SherpaLibs
import java.io.File

/**
 * Nghe tiếng Anh liên tục, offline, bằng sherpa-onnx (cùng thư viện với nhận giọng chủ máy).
 * Khác nhận dạng giọng nói của Google: không kêu "bíp" mỗi lần bắt đầu nghe, không tự dừng sau mỗi câu.
 *
 * Mô hình: trong GitHub releases của k2-fsa/sherpa-onnx, mục "asr-models", tải
 * sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2, rồi chọn ngay trong app (không cần giải nén):
 * file .tar.bz2 / .zip, các file lẻ, hoặc thư mục đã giải nén. App chép những file cần vào
 * /sdcard/Android/data/vn.phamvu.trolyai/files/asr-en/
 * Trong thư mục cần có tokens.txt và các file encoder / decoder / joiner (.onnx). Tên file không cần đổi.
 */
class EnglishAsr(ctx: Context) : AsrModelFiles(ctx, "asr-en", "nghe tiếng Anh",
    "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2") {

    fun create(): OnlineRecognizer {
        require(modelReady()) { "Chưa có mô hình nghe tiếng Anh trong ${dir.absolutePath}" }
        return SherpaLibs.run(ctx) { OnlineRecognizer(config = OnlineRecognizerConfig(
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
        )) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
