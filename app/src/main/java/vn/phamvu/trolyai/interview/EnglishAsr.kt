package vn.phamvu.trolyai.interview

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import vn.phamvu.trolyai.models.ModelFiles
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

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
class EnglishAsr(private val ctx: Context) {
    val dir = File(ctx.getExternalFilesDir(null), "asr-en")

    /** Tìm file .onnx có chứa [part] trong tên, ưu tiên bản int8 (nhẹ và nhanh hơn). */
    private fun find(part: String): File? {
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

    /** Chép vào thư mục tạm cạnh asr-en, chọn file, rồi chuyển sang asr-en. Hỏng giữa chừng thì mô hình cũ vẫn còn. */
    private suspend fun withStaging(fill: suspend (File) -> Unit): String = withContext(Dispatchers.IO) {
        val staging = File(dir.parentFile, "asr-en.tmp")
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

    /** Chuyển file từ thư mục tạm sang asr-en, thay file cũ cùng phần. */
    private fun install(staging: File): String {
        val chosen = pick(staging.listFiles().orEmpty().map { it.name to it })
        require(chosen.isNotEmpty()) {
            "Không thấy file nào của mô hình. Cần tokens.txt và các file encoder / decoder / joiner (.onnx), " +
                "hoặc file sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2."
        }
        dir.mkdirs()
        for ((name, f) in chosen) {
            val r = role(name)
            dir.listFiles { old -> role(old.name) == r }?.forEach { it.delete() }
            val target = File(dir, name)
            if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
        }
        val miss = missing()
        return if (miss.isEmpty()) "✅ Đã cài mô hình nghe tiếng Anh."
        else "Đã chép ${chosen.size} file, còn thiếu: ${miss.joinToString(", ")}. Chọn thêm các file đó nhé."
    }

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

    companion object {
        const val SAMPLE_RATE = 16_000
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
