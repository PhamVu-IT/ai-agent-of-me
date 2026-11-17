package vn.phamvu.trolyai.skills

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Một Agent Skill theo đúng định dạng của Google AI Edge Gallery: thư mục có SKILL.md (phần đầu --- name / description ---,
 * sau đó là hướng dẫn cho mô hình), có thể kèm scripts/index.html chạy trong WebView ẩn.
 * Xem https://github.com/google-ai-edge/gallery/tree/main/skills
 */
data class Skill(
    val name: String,
    val description: String,
    /** Phần hướng dẫn sau phần đầu, chỉ đưa cho mô hình khi nó chọn dùng skill này. */
    val instructions: String,
    /** Nơi chứa skill: "file:///android_asset/skills/x/", "file:///data/.../skills/x/" hoặc "https://…/x/". Có dấu / ở cuối. */
    val baseUrl: String,
    val builtIn: Boolean,
    val requireSecret: Boolean,
    val secretDescription: String,
    val homepage: String,
) {
    fun scriptUrl(script: String?): String = baseUrl + "scripts/" + (script?.trim()?.ifEmpty { null } ?: "index.html")

    companion object {
        /** Đọc nội dung SKILL.md. Trả null nếu thiếu phần đầu hoặc thiếu name. */
        fun parse(md: String, baseUrl: String, builtIn: Boolean): Skill? {
            val text = md.replace("\r\n", "\n").trimStart()
            if (!text.startsWith("---")) return null
            val end = text.indexOf("\n---", 3)
            if (end < 0) return null
            val meta = mutableMapOf<String, String>()
            text.substring(3, end).lines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) {
                    val v = line.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                    if (v.isNotEmpty()) meta[line.substring(0, i).trim()] = v
                }
            }
            val name = meta["name"] ?: return null
            return Skill(
                name = name,
                description = meta["description"] ?: "",
                instructions = text.substring(end + 4).trim(),
                baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
                builtIn = builtIn,
                requireSecret = meta["require-secret"] == "true",
                secretDescription = meta["require-secret-description"] ?: "",
                homepage = meta["homepage"] ?: "",
            )
        }
    }
}

/**
 * Danh sách skill: skill có sẵn trong app (assets/skills, lấy từ Gallery) và skill bạn thêm từ đường link hay thư mục
 * trong máy (lưu ở bộ nhớ riêng của app). Bật / tắt và khoá bí mật của từng skill lưu trong SharedPreferences.
 */
class SkillStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("skills", Context.MODE_PRIVATE)
    private val userDir = File(ctx.filesDir, "skills")

    fun all(): List<Skill> {
        val builtIn = ctx.assets.list("skills").orEmpty().mapNotNull { dir ->
            runCatching {
                val md = ctx.assets.open("skills/$dir/SKILL.md").bufferedReader().use { it.readText() }
                Skill.parse(md, "file:///android_asset/skills/$dir/", builtIn = true)
            }.getOrNull()
        }
        val user = userDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val md = File(dir, "SKILL.md").takeIf { it.exists() }?.readText() ?: return@mapNotNull null
            // Skill thêm từ đường link: script vẫn chạy từ trang web gốc (Gallery cũng làm vậy)
            val source = File(dir, "source_url").takeIf { it.exists() }?.readText()?.trim()
            Skill.parse(md, source ?: "file://${dir.absolutePath}/", builtIn = false)
        }
        return (builtIn + user).distinctBy { it.name }.sortedBy { it.name }
    }

    /** Skill mới thêm mặc định bật; tắt thì mô hình không thấy skill đó. */
    fun isEnabled(name: String) = prefs.getBoolean("on:$name", true)
    fun setEnabled(name: String, on: Boolean) = prefs.edit().putBoolean("on:$name", on).apply()
    fun enabled(): List<Skill> = all().filter { isEnabled(it.name) }
    fun find(name: String): Skill? = all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun secret(name: String): String = prefs.getString("secret:$name", "") ?: ""
    fun setSecret(name: String, value: String) = prefs.edit().putString("secret:$name", value).apply()

    fun delete(skill: Skill) {
        if (skill.builtIn) return
        File(userDir, safe(skill.name)).deleteRecursively()
        prefs.edit().remove("on:${skill.name}").remove("secret:${skill.name}").apply()
    }

    /**
     * Thêm skill từ đường link trỏ tới thư mục skill (giống "Load skill from URL" của Gallery), ví dụ
     * https://ten.github.io/skills/my-skill. Chỉ tải SKILL.md; script chạy thẳng từ đường link đó.
     */
    suspend fun addFromUrl(url: String): Skill = withContext(Dispatchers.IO) {
        val base = url.trim().removeSuffix("SKILL.md").let { if (it.endsWith("/")) it else "$it/" }
        require(base.startsWith("https://") || base.startsWith("http://")) { "Đường link phải bắt đầu bằng https://" }
        val conn = URL(base + "SKILL.md").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        val md = try {
            if (conn.responseCode !in 200..299) throw IOException("Không tải được SKILL.md (mã ${conn.responseCode})")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val skill = Skill.parse(md, base, builtIn = false)
            ?: throw IOException("SKILL.md không đúng định dạng (cần phần đầu --- name: … description: … ---)")
        val dir = File(userDir, safe(skill.name)).apply { deleteRecursively(); mkdirs() }
        File(dir, "SKILL.md").writeText(md)
        File(dir, "source_url").writeText(base)
        skill
    }

    /** Chép cả thư mục skill bạn chọn trong máy (có SKILL.md) vào bộ nhớ riêng của app, như "Import local skill". */
    suspend fun importFolder(tree: Uri): Skill = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val tmp = File(userDir, ".import").apply { deleteRecursively(); mkdirs() }
        fun copy(docId: String, into: File, depth: Int) {
            if (depth > 4) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.contains("/") || name == "..") continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        copy(id, File(into, name).apply { mkdirs() }, depth + 1)
                    } else {
                        cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { ins ->
                            File(into, name).outputStream().use { ins.copyTo(it) }
                        }
                    }
                }
            }
        }
        copy(rootId, tmp, 0)
        val md = File(tmp, "SKILL.md").takeIf { it.exists() }?.readText()
            ?: run { tmp.deleteRecursively(); throw IOException("Thư mục đã chọn không có file SKILL.md") }
        val parsed = Skill.parse(md, "", builtIn = false)
            ?: run { tmp.deleteRecursively(); throw IOException("SKILL.md không đúng định dạng") }
        val dir = File(userDir, safe(parsed.name))
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("Không lưu được skill")
        parsed.copy(baseUrl = "file://${dir.absolutePath}/")
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
}
