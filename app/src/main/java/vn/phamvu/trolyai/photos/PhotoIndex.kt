package vn.phamvu.trolyai.photos

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.phamvu.trolyai.finance.Finance
import java.io.File

/** Một ảnh trong thư viện, kèm lời mô tả AI đã viết (rỗng nếu chưa đọc tới). */
data class PhotoEntry(val id: Long, val takenAt: Long, val caption: String) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

/** Một ảnh tìm được, kèm điểm khớp (càng cao càng giống câu tìm). */
data class PhotoHit(val entry: PhotoEntry, val score: Float)

/**
 * Chỉ mục ảnh để tìm bằng chữ, hoàn toàn offline: mô hình AI (Gemma 3n / Gemma 4 nhìn được ảnh) xem từng ảnh một lần,
 * viết mô tả ngắn và từ khoá tiếng Việt + tiếng Anh; lúc tìm thì so câu tìm với các mô tả đó.
 * Mô tả chỉ lưu trong bộ nhớ riêng của app (filesDir/photos/index.json), không gửi đi đâu.
 */
class PhotoIndex(private val ctx: Context) {
    private val file = File(ctx.filesDir, "photos/index.json")
    private val entries = LinkedHashMap<Long, PhotoEntry>()
    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val a = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            entries[o.getLong("id")] = PhotoEntry(o.getLong("id"), o.optLong("t"), o.optString("c"))
        }
    }

    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        entries.values.forEach { a.put(JSONObject().put("id", it.id).put("t", it.takenAt).put("c", it.caption)) }
        val tmp = File(file.parentFile, "index.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun put(e: PhotoEntry) { ensureLoaded(); entries[e.id] = e }

    @Synchronized
    fun captioned(): Int { ensureLoaded(); return entries.values.count { it.caption.isNotBlank() } }

    @Synchronized
    fun clear() { entries.clear(); file.delete() }

    /** Ảnh trong máy, mới nhất trước: id và ngày chụp. Ảnh đã xoá khỏi máy thì bỏ khỏi chỉ mục. */
    suspend fun scan(): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        ensureLoaded()
        val out = mutableListOf<Pair<Long, Long>>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                out += c.getLong(0) to taken
            }
        }
        val alive = out.map { it.first }.toHashSet()
        synchronized(this@PhotoIndex) { entries.keys.retainAll(alive) }
        out
    }

    /** Ảnh chưa có mô tả, theo thứ tự [all] (mới nhất trước). */
    @Synchronized
    fun pending(all: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        ensureLoaded()
        return all.filter { entries[it.first]?.caption.isNullOrBlank() }
    }

    /**
     * Tìm ảnh khớp [query]. So từng chữ (bỏ dấu, để "meo" vẫn ra "mèo") với mô tả; chữ hiếm được tính điểm cao hơn
     * chữ có trong rất nhiều ảnh, cụm hai chữ liền nhau khớp được cộng thêm. Có "năm 2023" hay "tháng 5" thì lọc theo ngày.
     */
    @Synchronized
    fun search(query: String, limit: Int = 60): List<PhotoHit> {
        ensureLoaded()
        val q = words(query)
        val (year, month) = dateFilter(q)
        val terms = q.filter { it !in STOP && it.toIntOrNull() == null }
        val docs = entries.values.filter { it.caption.isNotBlank() }.filter { e ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = e.takenAt }
            (year == null || cal.get(java.util.Calendar.YEAR) == year) &&
                (month == null || cal.get(java.util.Calendar.MONTH) + 1 == month)
        }
        if (terms.isEmpty()) return docs.sortedByDescending { it.takenAt }.take(limit).map { PhotoHit(it, 0f) }
        val docWords = docs.associateWith { words(it.caption) }
        val df = terms.associateWith { t -> docWords.values.count { t in it }.coerceAtLeast(1) }
        val n = docs.size.coerceAtLeast(1)
        val bigrams = terms.zipWithNext { a, b -> "$a $b" }
        return docs.mapNotNull { e ->
            val w = docWords[e]!!
            val text = w.joinToString(" ")
            var score = 0f
            for (t in terms) if (t in w) score += kotlin.math.ln(1f + n.toFloat() / df[t]!!)
            for (b in bigrams) if (text.contains(b)) score += 1.5f
            if (score > 0f) PhotoHit(e, score) else null
        }.sortedWith(compareByDescending<PhotoHit> { it.score }.thenByDescending { it.entry.takenAt }).take(limit)
    }

    private fun dateFilter(q: List<String>): Pair<Int?, Int?> {
        var year: Int? = null
        var month: Int? = null
        q.forEachIndexed { i, w ->
            val n = w.toIntOrNull() ?: return@forEachIndexed
            when {
                n in 1990..2100 -> year = n
                q.getOrNull(i - 1) == "thang" && n in 1..12 -> month = n
            }
        }
        return year to month
    }

    companion object {
        private fun words(s: String) = Finance.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() }

        /** Chữ không mang nghĩa khi tìm ảnh. */
        private val STOP = setOf("anh", "hinh", "tim", "cho", "toi", "minh", "co", "cac", "nhung", "mot", "cai", "con",
            "la", "va", "o", "tai", "trong", "cua", "voi", "nam", "thang", "ngay", "photo", "photos", "picture", "of", "the",
            "a", "an", "in", "with", "show", "me", "find", "nao", "gi", "di", "nhe", "xem")
    }
}
