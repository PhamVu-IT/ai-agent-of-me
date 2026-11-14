package vn.phamvu.trolyai.learn

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.data.Memory
import vn.phamvu.trolyai.finance.Finance
import java.util.concurrent.TimeUnit

/**
 * "Học ban đêm": mỗi ngày một lần, khi máy đang sạc và rảnh (thường là lúc bạn ngủ),
 * trợ lý đọc lại các cuộc trò chuyện mới, tự ghi thêm điều về bạn,
 * và mỗi tuần tóm gọn mọi điều thành một bản hồ sơ ngắn. Tất cả chạy trên máy.
 */
class LearnWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return try {
            learnFromChats(app)
            maybeSummarize(app)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun learnFromChats(app: App) {
        val db = app.db
        val profile = Profile(db)
        val since = profile.get(Profile.LAST_LEARN)?.toLongOrNull() ?: 0L
        val chats = db.chats().since(since)
        if (chats.size < 2) return
        val known = db.memories().all().joinToString("\n") { "- ${it.content}" }.ifEmpty { "(chưa có)" }
        val convo = chats.takeLast(80).joinToString("\n") { (if (it.fromUser) "Họ: " else "Trợ lý: ") + it.text }

        val raw = app.llm.generate("""
<start_of_turn>user
Bạn đang đọc lại các cuộc trò chuyện giữa một người và trợ lý của họ để hiểu họ hơn.
Điều đã biết:
$known

Cuộc trò chuyện mới:
$convo

Hãy rút ra những điều MỚI, đáng nhớ lâu dài về người này (gia đình, công việc, sức khoẻ, sở thích,
mục tiêu, nỗi lo, cách họ muốn được nói chuyện) và thói quen lặp lại. Không chép lại điều đã biết.
Không ghi chuyện vặt chỉ đúng trong hôm nay. Mỗi ý một câu ngắn.
Chỉ trả lời bằng JSON: {"facts": ["..."], "habits": ["..."]}<end_of_turn>
<start_of_turn>model
""".trimIndent())

        val obj = parseJson(raw) ?: return
        val existing = db.memories().all().map { Finance.normalize(it.content) }
        suspend fun save(key: String, kind: String) {
            val arr = obj.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.length < 5 || existing.any { it.contains(Finance.normalize(s)) }) continue
                db.memories().insert(Memory(content = s, kind = kind))
            }
        }
        save("facts", "fact")
        save("habits", "habit")
        profile.put(Profile.LAST_LEARN, chats.last().createdAt.plus(1).toString())
    }

    /** Mỗi 7 ngày: viết lại bản hồ sơ ngắn gọn từ toàn bộ trí nhớ. */
    private suspend fun maybeSummarize(app: App) {
        val db = app.db
        val profile = Profile(db)
        val last = profile.get(Profile.SUMMARY_AT)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 7 * Finance.DAY) return
        val all = db.memories().all()
        if (all.isEmpty()) return
        val text = app.llm.generate("""
<start_of_turn>user
Dưới đây là mọi điều đã biết về một người. Hãy viết một bản hồ sơ dưới 150 chữ, gạch đầu dòng,
nêu điều quan trọng nhất trước: họ là ai, người thân, công việc, sức khoẻ, sở thích, mục tiêu,
cách họ thích được trò chuyện. Chỉ dùng thông tin có sẵn, không thêm thắt.
${all.joinToString("\n") { "- ${it.content}" }}
${profile.summary()?.let { "\nHồ sơ cũ:\n$it" } ?: ""}<end_of_turn>
<start_of_turn>model
""".trimIndent()).trim()
        if (text.isNotEmpty()) {
            profile.put(Profile.SUMMARY, text)
            profile.put(Profile.SUMMARY_AT, System.currentTimeMillis().toString())
        }
    }

    private fun parseJson(raw: String): JSONObject? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        return if (s < 0 || e <= s) null else runCatching { JSONObject(raw.substring(s, e + 1)) }.getOrNull()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<LearnWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hoc-ban-dem", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
