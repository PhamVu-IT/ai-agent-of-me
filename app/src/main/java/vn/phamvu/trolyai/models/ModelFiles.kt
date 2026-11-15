package vn.phamvu.trolyai.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Chép file người dùng chọn (trình chọn file của Android) vào bộ nhớ riêng của app.
 * Từ Android 11, trình quản lý file không vào được thư mục Android/data nữa, nên app tự chép thay cho adb.
 */
object ModelFiles {
    /** Tên file hiển thị (ví dụ "speaker.onnx"), rỗng nếu không đọc được. */
    fun name(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: ""

    /** Chép [uri] vào [dest]: ghi ra file tạm trước, xong mới thay, nên hỏng giữa chừng thì file cũ vẫn còn. */
    suspend fun copy(ctx: Context, uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Không mở được file đã chọn")
        input.use { write(it, dest) }
    }

    suspend fun write(input: InputStream, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
            if (tmp.length() == 0L) throw IOException("File rỗng")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Không lưu được ${dest.name}")
        } finally {
            tmp.delete()
        }
    }
}
