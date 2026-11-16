package vn.phamvu.trolyai.teacher

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.media.ChatMedia
import java.util.Locale

/** Ngôn ngữ muốn học: tên để hiện, tên để dặn AI, và giọng đọc (Locale) cho TTS. */
enum class StudyLang(val label: String, val prompt: String, val locale: Locale, val romanize: String) {
    EN("🇬🇧 Anh", "tiếng Anh", Locale.US, "phiên âm IPA"),
    ZH("🇨🇳 Trung", "tiếng Trung (giản thể)", Locale.SIMPLIFIED_CHINESE, "pinyin có dấu thanh"),
    JA("🇯🇵 Nhật", "tiếng Nhật", Locale.JAPANESE, "romaji"),
    KO("🇰🇷 Hàn", "tiếng Hàn", Locale.KOREAN, "phiên âm Latin"),
    FR("🇫🇷 Pháp", "tiếng Pháp", Locale.FRENCH, "phiên âm IPA"),
}

/** Một từ học được từ ảnh. */
data class VocabItem(
    val word: String,
    val pronunciation: String,
    val meaning: String,
    val example: String,
    val exampleVi: String,
    val lang: StudyLang,
) {
    fun toJson(): JSONObject = JSONObject().put("w", word).put("p", pronunciation).put("m", meaning)
        .put("e", example).put("ev", exampleVi).put("l", lang.name)

    companion object {
        fun from(o: JSONObject) = VocabItem(o.optString("w"), o.optString("p"), o.optString("m"), o.optString("e"),
            o.optString("ev"), runCatching { StudyLang.valueOf(o.optString("l")) }.getOrDefault(StudyLang.EN))
    }
}

/**
 * Màn 📸 Học ngoại ngữ qua camera: chụp (hay chọn) ảnh đồ vật quanh mình, AI xem ảnh rồi dạy tên các thứ trong ảnh
 * bằng ngôn ngữ bạn chọn, kèm phiên âm, nghĩa tiếng Việt và câu ví dụ; bấm 🔊 để nghe đọc. Lưu từ vào sổ để ôn bằng
 * thẻ đố. Mọi thứ chạy offline bằng mô hình nhìn được ảnh (Gemma 3n / Gemma 4 .litertlm).
 */
class LanguageTeacherViewModel(app: Application) : AndroidViewModel(app) {
    private val llm = (app as App).llm
    private val prefs = app.getSharedPreferences("teacher", Context.MODE_PRIVATE)

    val lang = mutableStateOf(runCatching { StudyLang.valueOf(prefs.getString("lang", "EN")!!) }.getOrDefault(StudyLang.EN))
    val photo = mutableStateOf<Bitmap?>(null)
    val busy = mutableStateOf(false)
    val error = mutableStateOf("")
    val items = mutableStateListOf<VocabItem>()
    val saved = mutableStateListOf<VocabItem>()
    private var jpeg: ByteArray? = null
    private var job: Job? = null

    init { loadSaved() }

    fun setLang(l: StudyLang) {
        lang.value = l
        prefs.edit().putString("lang", l.name).apply()
        if (jpeg != null && !busy.value) teach() // đổi ngôn ngữ thì dạy lại ảnh đang xem
    }

    /** Ảnh vừa chụp hay vừa chọn: đọc rồi dạy luôn. */
    fun load(uri: Uri) {
        job?.cancel()
        error.value = ""
        viewModelScope.launch {
            try {
                val bmp = ChatMedia.loadImage(getApplication(), uri)
                photo.value = bmp
                jpeg = withContext(Dispatchers.Default) { ChatMedia.toJpeg(bmp) }
                teach()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = "Không đọc được ảnh: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun teach() {
        val img = jpeg ?: return
        val l = lang.value
        job?.cancel()
        busy.value = true
        error.value = ""
        items.clear()
        job = viewModelScope.launch {
            try {
                if (!llm.canListen()) throw IllegalStateException(
                    "Mô hình AI đang dùng không xem được ảnh. Chọn Gemma 3n / Gemma 4 dạng .litertlm ở 🧠 Mô hình.")
                val parsed = parse(llm.look(img, prompt(l)), l)
                if (parsed.isEmpty()) error.value = "AI chưa nhận ra đồ vật nào. Thử chụp gần hơn, đủ sáng hơn nhé."
                items.addAll(parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
            }
        }
    }

    fun isSaved(v: VocabItem) = saved.any { same(it, v) }

    fun toggleSave(v: VocabItem) {
        val i = saved.indexOfFirst { same(it, v) }
        if (i >= 0) saved.removeAt(i) else saved.add(0, v)
        prefs.edit().putString("saved", JSONArray().apply { saved.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private fun same(a: VocabItem, b: VocabItem) = a.lang == b.lang && a.word.equals(b.word, ignoreCase = true)

    private fun loadSaved() {
        val a = runCatching { JSONArray(prefs.getString("saved", "[]")) }.getOrNull() ?: return
        for (i in 0 until a.length()) saved += VocabItem.from(a.getJSONObject(i))
    }

    // ---- Ôn tập bằng thẻ đố ----
    val quiz = mutableStateOf<VocabItem?>(null)
    val quizReveal = mutableStateOf(false)
    val quizNote = mutableStateOf("")

    fun nextQuiz() {
        val pool = saved.filter { it.lang == lang.value }.ifEmpty { saved.toList() }
        quiz.value = pool.filter { it != quiz.value }.randomOrNull() ?: pool.randomOrNull()
        quizReveal.value = false
        quizNote.value = ""
    }

    /** Kiểm tra câu trả lời đã gõ; so không phân biệt hoa thường và dấu, bỏ "the" / "a" ở đầu. */
    fun checkAnswer(answer: String) {
        val q = quiz.value ?: return
        fun clean(s: String) = Finance.normalize(s).replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
            .removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
        val ok = clean(answer).isNotEmpty() && clean(answer) == clean(q.word)
        quizNote.value = if (ok) "🎉 Đúng rồi!" else "Chưa đúng, thử lại hoặc bấm Xem đáp án."
        if (ok) quizReveal.value = true
    }

    fun stopQuiz() { quiz.value = null }

    companion object {
        private fun prompt(l: StudyLang) = """
            Bạn là giáo viên ${l.prompt} cho người Việt. Nhìn ảnh và chọn tối đa 5 đồ vật, con vật hay thứ nổi bật nhất trong ảnh.
            Với mỗi thứ, viết đúng MỘT dòng theo mẫu sau, các phần cách nhau bằng dấu |
            từ bằng ${l.prompt} | ${l.romanize} | nghĩa tiếng Việt | một câu ví dụ ngắn, dễ bằng ${l.prompt} | dịch câu ví dụ sang tiếng Việt
            Chỉ viết các dòng đó, không đánh số, không viết gì khác.
        """.trimIndent()

        /** Đọc các dòng "từ | phát âm | nghĩa | ví dụ | dịch"; bỏ dòng sai mẫu. */
        internal fun parse(out: String, l: StudyLang): List<VocabItem> = out.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").replace("**", "") }
            .filter { line -> line.count { it == '|' } >= 2 }
            .mapNotNull { line ->
                val p = line.split('|').map { it.trim() }
                val word = p.getOrElse(0) { "" }
                if (word.isEmpty() || word.length > 60) null
                else VocabItem(word, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" },
                    p.getOrElse(4) { "" }, l)
            }
            .distinctBy { it.word.lowercase() }
            .take(5)
    }
}
