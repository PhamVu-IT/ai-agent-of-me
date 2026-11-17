package vn.phamvu.trolyai.models

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import vn.phamvu.trolyai.App

/** Tiến độ tải hiện tại. */
data class DownloadState(val name: String, val done: Long, val total: Long, val installing: Boolean) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Kết quả một lần đo tốc độ, như màn Benchmark của Gallery. */
data class BenchResult(
    val at: Long,
    val model: String,
    val backend: String,
    val loadMs: Long,
    val runs: Int,
    /** Thời gian tới chữ đầu tiên (đọc xong prompt), trung bình (ms). */
    val ttftMs: Long,
    /** Tốc độ đọc prompt, ký tự / giây. */
    val prefillCps: Float,
    /** Tốc độ viết, token / giây (trung bình, thấp nhất, cao nhất). */
    val decodeTps: Float,
    val decodeMin: Float,
    val decodeMax: Float,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("model", model).put("backend", backend)
        .put("loadMs", loadMs).put("runs", runs).put("ttftMs", ttftMs).put("prefillCps", prefillCps.toDouble())
        .put("decodeTps", decodeTps.toDouble()).put("decodeMin", decodeMin.toDouble()).put("decodeMax", decodeMax.toDouble())

    companion object {
        fun from(o: JSONObject) = BenchResult(
            o.optLong("at"), o.optString("model"), o.optString("backend"), o.optLong("loadMs"), o.optInt("runs"),
            o.optLong("ttftMs"), o.optDouble("prefillCps").toFloat(), o.optDouble("decodeTps").toFloat(),
            o.optDouble("decodeMin").toFloat(), o.optDouble("decodeMax").toFloat(),
        )
    }
}

/**
 * Màn 📥 Tải mô hình và đo tốc độ: danh sách mô hình của Gallery tải thẳng từ Hugging Face (chạy ở nền bằng
 * [ModelDownloadWorker]), và nút đo tốc độ mô hình đang dùng.
 */
class ModelStoreViewModel(app: Application) : AndroidViewModel(app) {
    private val llm = (app as App).llm
    private val prefs = app.getSharedPreferences(ModelDownloadWorker.PREFS, Context.MODE_PRIVATE)
    private val work = WorkManager.getInstance(app)

    /** RAM của máy (GB, làm tròn), để báo mô hình nào nặng quá. */
    val deviceRamGb: Int = run {
        val mi = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        ((mi.totalMem + 500_000_000) / 1_000_000_000).toInt()
    }

    val download = mutableStateOf<DownloadState?>(null)
    val note = mutableStateOf("")
    val installed = mutableStateOf(installedName())
    val token = mutableStateOf(prefs.getString(ModelDownloadWorker.KEY_TOKEN, "") ?: "")

    private val observer = Observer<List<WorkInfo>> { list ->
        val info = list.firstOrNull { !it.state.isFinished } ?: list.lastOrNull()
        if (info == null) { download.value = null; return@Observer }
        when (info.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val p = info.progress
                download.value = DownloadState(
                    name = download.value?.name ?: prefs.getString(KEY_LAST, "") ?: "",
                    done = p.getLong(ModelDownloadWorker.KEY_DONE, download.value?.done ?: 0),
                    total = p.getLong(ModelDownloadWorker.KEY_TOTAL, download.value?.total ?: 0),
                    installing = p.getString(ModelDownloadWorker.KEY_STAGE) == "install",
                )
                if (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0) note.value = "Mất mạng, sẽ tải tiếp khi có mạng…"
            }
            WorkInfo.State.SUCCEEDED -> {
                if (download.value != null) note.value = "✅ Đã tải và cài ${info.outputData.getString(ModelDownloadWorker.KEY_NAME)}."
                download.value = null
                installed.value = installedName()
                onInstalled()
            }
            WorkInfo.State.FAILED -> {
                if (download.value != null) note.value = "⚠️ " + (info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Tải không được")
                download.value = null
            }
            WorkInfo.State.CANCELLED -> download.value = null
        }
    }
    private val live = work.getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.WORK)

    /** Màn chat cần biết khi có mô hình mới (đặt từ MainActivity). */
    var onInstalled: () -> Unit = {}

    init {
        live.observeForever(observer)
        loadBench()
    }

    override fun onCleared() {
        live.removeObserver(observer)
        super.onCleared()
    }

    /** Mô hình trong danh sách đang được dùng, nếu file hiện tại đúng là file đã tải. */
    private fun installedName(): String {
        val name = prefs.getString(ModelDownloadWorker.KEY_INSTALLED, "") ?: ""
        val m = CatalogModel.find(name) ?: return ""
        return if (llm.hasModel() && llm.modelFile.length() == m.sizeBytes) name else ""
    }

    fun start(m: CatalogModel) {
        if (download.value != null) return
        note.value = ""
        prefs.edit().putString(KEY_LAST, m.name).apply()
        download.value = DownloadState(m.name, 0, m.sizeBytes, false)
        ModelDownloadWorker.start(getApplication(), m)
    }

    fun cancel() {
        ModelDownloadWorker.cancel(getApplication())
        download.value = null
        note.value = "Đã dừng. Bấm Tải lần nữa sẽ tải tiếp từ chỗ đang dở."
    }

    fun saveToken(t: String) {
        token.value = t.trim()
        prefs.edit().putString(ModelDownloadWorker.KEY_TOKEN, t.trim()).apply()
    }

    // ---- Đo tốc độ ----
    val benchBusy = mutableStateOf(false)
    val benchStatus = mutableStateOf("")
    val benchResults = mutableStateListOf<BenchResult>()
    private var benchJob: Job? = null

    private fun loadBench() {
        val a = runCatching { JSONArray(prefs.getString(KEY_BENCH, "[]")) }.getOrNull() ?: return
        benchResults.clear()
        for (i in 0 until a.length()) benchResults += BenchResult.from(a.getJSONObject(i))
    }

    private fun saveBench() = prefs.edit()
        .putString(KEY_BENCH, JSONArray().apply { benchResults.take(20).forEach { put(it.toJson()) } }.toString()).apply()

    /**
     * Cho mô hình đọc một đoạn văn cố định rồi viết tóm tắt, [runs] lần. Đo thời gian tới chữ đầu tiên (đọc prompt)
     * và tốc độ viết (token / giây). Lần đầu chưa nạp mô hình thì đo luôn thời gian nạp.
     */
    fun runBenchmark(runs: Int = 3) {
        if (benchBusy.value) return
        benchBusy.value = true
        benchStatus.value = ""
        benchJob = viewModelScope.launch {
            try {
                if (!llm.hasModel()) throw IllegalStateException("Chưa có mô hình. Tải một mô hình ở trên trước.")
                var loadMs = 0L
                if (!llm.isLoaded()) {
                    benchStatus.value = "Đang nạp mô hình…"
                    llm.warmUp()
                    loadMs = llm.loadMs
                }
                val ttfts = mutableListOf<Long>()
                val tps = mutableListOf<Float>()
                repeat(runs) { i ->
                    benchStatus.value = "Lần ${i + 1}/$runs…"
                    val t0 = System.currentTimeMillis()
                    var first = 0L
                    var chunks = 0
                    llm.generateStream(BENCH_PROMPT) {
                        if (chunks++ == 0) first = System.currentTimeMillis() - t0
                    }
                    val total = System.currentTimeMillis() - t0
                    ttfts += first
                    if (chunks > 1 && total > first) tps += (chunks - 1) * 1000f / (total - first)
                }
                if (tps.isEmpty()) throw IllegalStateException("Mô hình không viết ra chữ nào.")
                val ttft = ttfts.average().toLong()
                val r = BenchResult(
                    at = System.currentTimeMillis(),
                    model = installed.value.ifEmpty { llm.modelFile.name },
                    backend = llm.backend,
                    loadMs = loadMs,
                    runs = runs,
                    ttftMs = ttft,
                    prefillCps = if (ttft > 0) BENCH_PROMPT.length * 1000f / ttft else 0f,
                    decodeTps = tps.average().toFloat(),
                    decodeMin = tps.min(),
                    decodeMax = tps.max(),
                )
                benchResults.add(0, r)
                saveBench()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                benchStatus.value = "⚠️ ${e.message ?: e.javaClass.simpleName}"
                return@launch
            } finally {
                benchBusy.value = false
            }
            benchStatus.value = ""
        }
    }

    fun clearBench() { benchResults.clear(); saveBench() }

    companion object {
        private const val KEY_BENCH = "bench"
        private const val KEY_LAST = "last_download"

        /** Đoạn văn cố định để các lần đo so sánh được với nhau. */
        private val BENCH_PROMPT = """
            Hãy tóm tắt đoạn văn sau trong khoảng 100 chữ, bằng tiếng Việt.

            Vịnh Hạ Long là một vịnh nhỏ thuộc phần bờ tây vịnh Bắc Bộ, ở tỉnh Quảng Ninh. Vịnh có hàng nghìn
            hòn đảo đá vôi lớn nhỏ với nhiều hình dáng khác nhau, phần lớn chưa có người ở. Nhiều đảo có hang động
            đẹp như hang Sửng Sốt, động Thiên Cung, hang Đầu Gỗ. Năm 1994, vịnh được UNESCO công nhận là di sản
            thiên nhiên thế giới nhờ giá trị cảnh quan, và năm 2000 được công nhận thêm lần nữa nhờ giá trị địa chất,
            địa mạo. Khí hậu ở đây có hai mùa rõ rệt: mùa hè nóng ẩm, mưa nhiều; mùa đông lạnh và khô. Du khách thường
            đi thuyền tham quan, chèo kayak, tắm biển ở các bãi như Ti Tốp, và ghé thăm làng chài nổi Cửa Vạn để tìm
            hiểu đời sống của ngư dân. Hải sản như mực, tu hài, sá sùng là đặc sản nổi tiếng của vùng.
        """.trimIndent()
    }
}
