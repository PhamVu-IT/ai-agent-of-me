package vn.phamvu.trolyai.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Xác nhận đúng chủ máy bằng vân tay, khuôn mặt, hoặc mã PIN/hình vẽ của máy. */
fun interface Authenticator {
    suspend fun confirm(reason: String): Boolean
}

class BiometricAuthenticator(private val activity: FragmentActivity) : Authenticator {
    private val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Máy chưa cài khoá màn hình thì không có gì để kiểm tra. */
    fun isAvailable(): Boolean =
        BiometricManager.from(activity).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun confirm(reason: String): Boolean = withContext(Dispatchers.Main) {
        if (!isAvailable()) return@withContext false
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                    // onAuthenticationFailed: sai vân tay một lần, hộp thoại vẫn cho thử lại
                })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Xác nhận là bạn")
                .setSubtitle(reason)
                .setAllowedAuthenticators(allowed)
                .build())
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
