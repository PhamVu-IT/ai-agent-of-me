package vn.phamvu.trolyai.security

import androidx.compose.runtime.MutableState
import kotlinx.coroutines.delay
import vn.phamvu.trolyai.voice.VoiceId

/**
 * Lệnh nhạy cảm: thử nhận giọng trước (nói một câu trong 3 giây).
 * Giọng khớp thì làm luôn. Không khớp, chưa đăng ký giọng, hoặc chưa có mô hình thì hỏi vân tay như cũ.
 * [prompt] là dòng chữ hiện trên màn hình trong lúc nghe; null là không nghe.
 */
class VoiceThenBiometric(
    private val voice: VoiceId,
    private val biometric: Authenticator,
    private val prompt: MutableState<String?>,
) : Authenticator {

    override suspend fun confirm(reason: String): Boolean {
        if (voice.modelReady() && voice.micAllowed() && voice.enrolled()) {
            prompt.value = "🎙️ $reason\nHãy nói: \"Tôi là chủ máy, xác nhận\""
            delay(400)
            val score = try { voice.score(voice.record(3)) } catch (e: Exception) { 0f }
            prompt.value = null
            if (score >= voice.threshold) return true
        }
        return biometric.confirm(reason)
    }
}
