package vn.phamvu.trolyai.voice

import android.content.Context
import android.os.Build
import java.util.zip.ZipFile

/**
 * Thư viện C++ của sherpa-onnx (libsherpa-onnx-jni.so, libonnxruntime.so) nằm trong file sherpa-onnx.aar.
 * Nếu APK thiếu chúng thì sherpa-onnx ném UnsatisfiedLinkError ("library ... not found").
 * Hàm này đổi lỗi đó thành câu dễ hiểu, kèm danh sách thư viện thực sự có trong APK để dễ tìm nguyên nhân.
 */
object SherpaLibs {
    inline fun <T> run(ctx: Context, block: () -> T): T = try {
        block()
    } catch (e: LinkageError) { // UnsatisfiedLinkError, hoặc NoClassDefFoundError ở lần gọi sau
        throw IllegalStateException(explain(ctx, e), e)
    }

    fun explain(ctx: Context, e: Throwable): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val libs = try {
            ZipFile(ctx.applicationInfo.sourceDir).use { z ->
                z.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/$abi/") }
                    .map { it.substringAfterLast('/') }.toList()
            }
        } catch (_: Exception) { emptyList() }
        val has = if (libs.isEmpty()) "không có thư viện nào cho $abi" else libs.joinToString(", ")
        return "Thiếu thư viện sherpa-onnx trong app (${e.message ?: e.javaClass.simpleName}). " +
            "APK đang có ($abi): $has. Cần libsherpa-onnx-jni.so và libonnxruntime.so: " +
            "đặt đúng file sherpa-onnx-<phiên bản>.aar (bản Android, không phải .jar) vào app/libs/ rồi build lại."
    }
}
