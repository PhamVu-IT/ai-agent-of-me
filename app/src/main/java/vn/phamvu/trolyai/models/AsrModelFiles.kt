package vn.phamvu.trolyai.models

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * File mô hình nhận dạng giọng nói của sherpa-onnx (tokens.txt + encoder / decoder / joiner .onnx) trong một thư mục
 * riêng của app. Dùng chung cho mô hình nghe tiếng Anh (asr-en) và tiếng Việt (asr-vi).
 *
 * @param folder tên thư mục trong /sdcard/Android/data/vn.phamvu.trolyai/files/
 * @param label tên mô hình để báo cho người dùng, ví dụ "nghe tiếng Anh"
 * @param archive tên file nén gợi ý tải về
 */
open class AsrModelFiles(
    protected val ctx: Context,
    folder: String,
    private val label: String,
    private val archive: String,
) {
    val dir = File(ctx.getExternalFilesDir(null), folder)

    /** Tìm file .onnx có chứa [part] trong tên, ưu tiên bản int8 (nhẹ và nhanh hơn). */
    protected fun find(part: String): File? {
        val files = dir.listFiles { f -> f.name.endsWith(".onnx") && f.name.contains(part) } ?: return null
        return files.firstOrNull { it.name.contains(".int8.") } ?: files.firstOrNull()
    }

    fun modelReady() = missing().isEmpty()

    /** Những phần còn thiếu, ví dụ ["tokens.txt", "joiner"]. Rỗng là đủ. */
    fun missing(): List<String> = buildList {
        if (!File(dir, "tokens.txt").exists()) add("tokens.txt")
        for (part in PARTS) if (find(part) == null) add(part)
    }

    /**
     * Chép mô hình từ những file người dùng chọn: file nén .tar.bz2 / .zip tải từ sherpa-onnx,
     * hoặc các file lẻ (tokens.txt, encoder / decoder / joiner .onnx). Trả về câu báo kết quả.
     */
    suspend fun importFiles(uris: List<Uri>): String = withStaging { staging ->
        val plain = mutableListOf<Pair<String, Uri>>()
        for (uri in uris) {
            val name = ModelFiles.name(ctx, uri)
            val lower = name.lowercase()
            when {
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ->
                    open(uri).use { extractTar(TarArchiveInputStream(BZip2CompressorInputStream(it.buffered())), staging) }
                lower.endsWith(".tar") -> open(uri).use { extractTar(TarArchiveInputStream(it.buffered()), staging) }
                lower.endsWith(".zip") -> open(uri).use { extractZip(ZipInputStream(it.buffered()), staging) }
                role(name) != null -> plain += name to uri
            }
        }
        for ((name, uri) in pick(plain)) ModelFiles.copy(ctx, uri, File(staging, name))
    }

    /** Chép mô hình từ một thư mục đã giải nén (chọn bằng trình chọn thư mục của Android). */
    suspend fun importFolder(tree: Uri): String = withStaging { staging ->
        for ((name, uri) in pick(listTree(tree))) ModelFiles.copy(ctx, uri, File(staging, name))
    }

    /** Chép vào thư mục tạm cạnh [dir], chọn file, rồi chuyển sang [dir]. Hỏng giữa chừng thì mô hình cũ vẫn còn. */
    private suspend fun withStaging(fill: suspend (File) -> Unit): String = withContext(Dispatchers.IO) {
        val staging = File(dir.parentFile, dir.name + ".tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            fill(staging)
            install(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun open(uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Không mở được file đã chọn")

    private suspend fun extractTar(tar: TarArchiveInputStream, staging: File) = tar.use {
        while (true) {
            coroutineContext.ensureActive()
            val e = tar.nextEntry ?: break
            val base = e.name.substringAfterLast('/')
            if (!e.isDirectory && role(base) != null) ModelFiles.write(tar, File(staging, base))
        }
    }

    private suspend fun extractZip(zip: ZipInputStream, staging: File) = zip.use {
        while (true) {
            coroutineContext.ensureActive()
            val e = zip.nextEntry ?: break
            val base = e.name.substringAfterLast('/')
            if (!e.isDirectory && role(base) != null) ModelFiles.write(zip, File(staging, base))
        }
    }

    /** Liệt kê file trong thư mục đã chọn và vài tầng thư mục con (phòng khi chọn thư mục cha). */
    private fun listTree(tree: Uri): List<Pair<String, Uri>> {
        val out = mutableListOf<Pair<String, Uri>>()
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(id, depth + 1)
                    } else if (role(name) != null) {
                        out += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    /** Mỗi phần chỉ giữ một file, ưu tiên bản int8 (nhẹ, nhanh), để đỡ tốn bộ nhớ. */
    private fun <T> pick(files: List<Pair<String, T>>): List<Pair<String, T>> =
        files.groupBy { role(it.first) }.mapNotNull { (r, fs) ->
            if (r == null) null else fs.firstOrNull { ".int8." in it.first } ?: fs.first()
        }

    /** Chuyển file từ thư mục tạm sang [dir], thay file cũ cùng phần. */
    private fun install(staging: File): String {
        val chosen = pick(staging.listFiles().orEmpty().map { it.name to it })
        require(chosen.isNotEmpty()) {
            "Không thấy file nào của mô hình. Cần tokens.txt và các file encoder / decoder / joiner (.onnx), " +
                "hoặc file $archive."
        }
        dir.mkdirs()
        for ((name, f) in chosen) {
            val r = role(name)
            dir.listFiles { old -> role(old.name) == r }?.forEach { it.delete() }
            val target = File(dir, name)
            if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
        }
        val miss = missing()
        return if (miss.isEmpty()) "✅ Đã cài mô hình $label."
        else "Đã chép ${chosen.size} file, còn thiếu: ${miss.joinToString(", ")}. Chọn thêm các file đó nhé."
    }

    companion object {
        private val PARTS = listOf("encoder", "decoder", "joiner")

        /** File này là phần nào của mô hình: "tokens", "encoder", "decoder", "joiner", hoặc null nếu không cần. */
        private fun role(name: String): String? {
            val n = name.lowercase()
            if (n == "tokens.txt") return "tokens"
            if (!n.endsWith(".onnx")) return null
            return PARTS.firstOrNull { it in n }
        }
    }
}
