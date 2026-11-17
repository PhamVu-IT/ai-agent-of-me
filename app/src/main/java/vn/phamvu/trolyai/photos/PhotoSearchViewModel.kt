package vn.phamvu.trolyai.photos

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.App

/** Màn 🖼️ Tìm ảnh: đọc thư viện ảnh (AI viết mô tả từng ảnh) rồi tìm bằng chữ, tất cả trong máy. */
class PhotoSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val llm = (app as App).llm
    private val index = PhotoIndex(app)
    private val prefs = app.getSharedPreferences("photos", Context.MODE_PRIVATE)

    val permitted = mutableStateOf(PhotoIndexer.hasPermission(app))
    val total = mutableIntStateOf(0)
    val captioned = mutableIntStateOf(0)
    val indexing = mutableStateOf(false)
    val note = mutableStateOf("")
    val query = mutableStateOf("")
    val results = mutableStateListOf<PhotoHit>()
    val searched = mutableStateOf(false)
    /** Tự đọc ảnh mới lúc máy đang sạc và không dùng. */
    val autoIndex = mutableStateOf(prefs.getBoolean("auto", false))
    private var job: Job? = null

    init { refresh() }

    fun onPermission(granted: Boolean) {
        permitted.value = granted
        if (granted) refresh()
    }

    fun refresh() {
        if (!permitted.value) return
        viewModelScope.launch {
            val all = runCatching { index.scan() }.getOrDefault(emptyList())
            total.intValue = all.size
            captioned.intValue = all.size - index.pending(all).size
        }
    }

    fun startIndex() {
        if (indexing.value || !permitted.value) return
        indexing.value = true
        note.value = "Đang nạp mô hình…"
        job = viewModelScope.launch {
            try {
                val n = PhotoIndexer.run(getApplication(), index, llm) { c, t ->
                    captioned.intValue = c
                    total.intValue = t
                    note.value = ""
                }
                note.value = if (n == 0) "Mọi ảnh đều đã đọc xong." else "✅ Đã đọc thêm $n ảnh."
            } catch (e: CancellationException) {
                note.value = "Đã dừng. Bấm lần nữa để đọc tiếp."
                throw e
            } catch (e: Throwable) {
                note.value = "⚠️ ${e.message ?: e.javaClass.simpleName}"
            } finally {
                indexing.value = false
                if (query.value.isNotBlank()) search()
            }
        }
    }

    fun stopIndex() {
        job?.cancel()
        indexing.value = false
    }

    fun setAuto(on: Boolean) {
        autoIndex.value = on
        prefs.edit().putBoolean("auto", on).apply()
        PhotoIndexWorker.schedule(getApplication(), on)
    }

    fun search() {
        val q = query.value
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) { index.search(q) }
            results.clear()
            results.addAll(hits)
            searched.value = true
        }
    }

    /** Xoá hết mô tả đã lưu (ví dụ khi đổi sang mô hình tốt hơn và muốn đọc lại). */
    fun clearIndex() {
        stopIndex()
        index.clear()
        results.clear()
        searched.value = false
        refresh()
    }
}
