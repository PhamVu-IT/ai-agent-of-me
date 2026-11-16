package vn.phamvu.trolyai.settings

import android.content.Context
import vn.phamvu.trolyai.llm.GenSettings

/** Cài đặt giọng đọc (TTS) cho câu trả lời. */
data class SpeechSettings(
    /** Tự đọc to mỗi câu trả lời mới (mỗi câu chỉ đọc một lần). */
    val autoSpeak: Boolean = true,
    /** Tốc độ đọc, 1.0 là bình thường. */
    val rate: Float = 1.0f,
    /** Cao độ giọng, 1.0 là bình thường. */
    val pitch: Float = 1.0f,
    /** Tên giọng tiếng Việt của máy; rỗng là giọng mặc định. */
    val voice: String = "",
)

/**
 * Cài đặt của app, như phần "Configs" của Google AI Edge Gallery: các thông số sinh chữ của mô hình
 * (temperature, top-k, top-p, chế độ suy nghĩ, GPU / CPU) và giọng đọc. Lưu trong SharedPreferences "settings".
 */
class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun gen() = GenSettings(
        temperature = p.getFloat("temperature", DEFAULT_GEN.temperature),
        topK = p.getInt("topK", DEFAULT_GEN.topK),
        topP = p.getFloat("topP", DEFAULT_GEN.topP),
        thinking = p.getBoolean("thinking", DEFAULT_GEN.thinking),
        backend = p.getString("backend", DEFAULT_GEN.backend) ?: DEFAULT_GEN.backend,
    )

    fun saveGen(s: GenSettings) = p.edit()
        .putFloat("temperature", s.temperature).putInt("topK", s.topK).putFloat("topP", s.topP)
        .putBoolean("thinking", s.thinking).putString("backend", s.backend).apply()

    fun speech() = SpeechSettings(
        autoSpeak = p.getBoolean("autoSpeak", true),
        rate = p.getFloat("ttsRate", 1.0f),
        pitch = p.getFloat("ttsPitch", 1.0f),
        voice = p.getString("ttsVoice", "") ?: "",
    )

    fun saveSpeech(s: SpeechSettings) = p.edit()
        .putBoolean("autoSpeak", s.autoSpeak).putFloat("ttsRate", s.rate).putFloat("ttsPitch", s.pitch)
        .putString("ttsVoice", s.voice).apply()

    companion object {
        val DEFAULT_GEN = GenSettings()
    }
}
