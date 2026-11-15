package vn.phamvu.trolyai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nghe tiếng Việt ngay trong app bằng SpeechRecognizer, không mở hộp thoại "Voice search" của Google.
 *
 * Trước đây app gửi RecognizerIntent.ACTION_RECOGNIZE_SPEECH: Android mở màn hình của Google app để nghe,
 * nên trên máy Samsung chưa cài hoặc đã tắt Google app sẽ báo "Voice search Google not available".
 *
 * Thứ tự thử:
 *  1. Mô hình tiếng Việt sherpa-onnx đã chọn trong 🧠 Mô hình: offline hoàn toàn, không cần Google.
 *  2. Bộ nhận giọng trên máy (Android 12+, chạy offline) nếu có và hỗ trợ tiếng Việt.
 *  3. Dịch vụ nhận giọng mặc định của máy (Google, Samsung…), vẫn ưu tiên offline.
 *  4. Không có gì dùng được: báo lỗi kèm hướng dẫn, không làm phiền khi chỉ là tự nghe lúc mở app.
 */
class VietnameseSpeech(private val ctx: Context, private val scope: CoroutineScope) {
    interface Callback {
        fun onListening(text: String)
        fun onResult(text: String)
        /** Kết thúc không có kết quả. [message] null là kết thúc lặng lẽ (không nói gì, bấm huỷ). */
        fun onEnd(message: String?)
    }

    private var recognizer: SpeechRecognizer? = null
    private var job: Job? = null
    val offline = VietnameseAsr(ctx)

    fun hasPermission() =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Máy có dịch vụ nhận giọng nào dùng được không. */
    fun isAvailable(): Boolean =
        offline.modelReady() || onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(ctx)

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    fun start(cb: Callback) {
        stop()
        if (!hasPermission()) { cb.onEnd(NO_PERMISSION); return }
        when {
            offline.modelReady() -> runOffline(cb)
            onDeviceAvailable() -> run(onDevice = true, cb)
            SpeechRecognizer.isRecognitionAvailable(ctx) -> run(onDevice = false, cb)
            else -> cb.onEnd(NOT_AVAILABLE)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
    }

    /** Nghe bằng mô hình sherpa-onnx tiếng Việt. Lỗi mô hình thì thử dịch vụ của máy. */
    private fun runOffline(cb: Callback) {
        cb.onListening("⏳ Đang nạp mô hình nghe…")
        job = scope.launch {
            val result = try {
                withContext<Result<String>?>(Dispatchers.Default) {
                    offline.recognizer() // lần đầu mất vài giây, các lần sau dùng lại
                    withContext(Dispatchers.Main) { cb.onListening("🎤 Đang nghe (offline), bạn nói đi…") }
                    val audio = offline.record(onSpeechStart = {
                        scope.launch(Dispatchers.Main) { cb.onListening("🎤 Đang nghe…") }
                    }) ?: return@withContext null
                    withContext(Dispatchers.Main) { cb.onListening("⏳ Đang nhận dạng…") }
                    Result.success(offline.transcribe(audio))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { Result.failure(e) }
            job = null
            when {
                result == null -> cb.onEnd(null)
                result.isSuccess -> result.getOrThrow().let { if (it.isBlank()) cb.onEnd(null) else cb.onResult(it) }
                else -> {
                    val err = result.exceptionOrNull()?.message ?: "lỗi không rõ"
                    if (onDeviceAvailable()) run(onDevice = true, cb)
                    else if (SpeechRecognizer.isRecognitionAvailable(ctx)) run(onDevice = false, cb)
                    else cb.onEnd("Không dùng được mô hình nghe tiếng Việt: $err")
                }
            }
        }
    }

    private fun run(onDevice: Boolean, cb: Callback) {
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) {
            if (onDevice && SpeechRecognizer.isRecognitionAvailable(ctx)) { run(onDevice = false, cb); return }
            cb.onEnd(NOT_AVAILABLE); return
        }
        recognizer = r
        var heardSomething = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = cb.onListening("🎤 Đang nghe, bạn nói đi…")
            override fun onBeginningOfSpeech() { heardSomething = true }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { cb.onListening("🎤 $it") }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stop()
                if (text.isNullOrBlank()) cb.onEnd(null) else cb.onResult(text)
            }
            override fun onError(error: Int) {
                stop()
                // Bộ nhận giọng trên máy chưa có tiếng Việt: chuyển sang dịch vụ mặc định
                if (onDevice && !heardSomething && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                    SpeechRecognizer.isRecognitionAvailable(ctx)) { run(onDevice = false, cb); return }
                cb.onEnd(messageFor(error))
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try { r.startListening(intent) } catch (e: Exception) { stop(); cb.onEnd(NOT_AVAILABLE) }
    }

    private fun messageFor(error: Int): String? = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> NO_PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Không nghe được vì cần mạng: máy chưa có gói nhận giọng tiếng Việt offline.\n\n$OFFLINE_MODEL"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Micro đang bận (app khác đang dùng). Thử lại sau giây lát."
        12, 13 -> // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
            "Bộ nhận giọng của máy chưa có tiếng Việt.\n\n$OFFLINE_MODEL"
        else -> "Không nhận được giọng nói (mã lỗi $error). Bạn có thể gõ chữ thay thế."
    }

    companion object {
        const val NO_PERMISSION = "App chưa được dùng micro. Vào Cài đặt > Ứng dụng > Trợ lý AI > Quyền > Micrô và chọn Cho phép."
        private const val OFFLINE_GUIDE =
            "Tải gói tiếng Việt offline: mở Cài đặt > Quản lý chung > Danh sách bàn phím và mặc định > " +
            "Google Voice Typing (Nhập liệu bằng giọng nói Google) > Nhận dạng giọng nói ngoại tuyến > Tiếng Việt."
        private const val OFFLINE_MODEL =
            "Cách tốt nhất (không cần mạng, không cần Google): vào 🧠 Mô hình > Nghe tiếng Việt, chọn file " +
            "sherpa-onnx-zipformer-vi-int8-2025-04-20.tar.bz2 (tải ở trang releases của k2-fsa/sherpa-onnx, mục asr-models)."
        const val NOT_AVAILABLE =
            "Máy chưa có dịch vụ nhận giọng nói nào.\n\n$OFFLINE_MODEL\n\nHoặc dùng dịch vụ của máy. " +
            "Trên Samsung: mở CH Play, cài hoặc bật lại app \"Google\" (và \"Dịch vụ giọng nói của Google\"). " +
            "Nếu đã cài mà bị tắt: Cài đặt > Ứng dụng > Google > Bật.\n\n$OFFLINE_GUIDE\n\n" +
            "Trong lúc chờ, bạn vẫn gõ chữ để trò chuyện được bình thường."
    }
}
