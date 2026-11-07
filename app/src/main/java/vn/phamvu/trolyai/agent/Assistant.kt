package vn.phamvu.trolyai.agent

import org.json.JSONObject
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.llm.LlmEngine
import java.text.SimpleDateFormat
import java.util.*

/** Một hành động trợ lý muốn thực hiện, do mô hình AI quyết định. */
data class Action(val type: String, val args: JSONObject)
data class AssistantTurn(val reply: String, val action: Action?)

/**
 * "Bộ não": ghép lời nhắn + trí nhớ + giờ hiện tại thành prompt,
 * yêu cầu mô hình trả lời dạng JSON gồm câu trả lời và (nếu cần) một hành động.
 */
class Assistant(private val llm: LlmEngine, private val db: AppDb) {

    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)

    suspend fun handle(userText: String): AssistantTurn {
        val memories = relevantMemories(userText)
        val history = db.chats().recent(6).reversed()
        val money = Finance(db).summaryForPrompt()
        val prompt = buildPrompt(userText, memories, history, money)
        val raw = llm.generate(prompt)
        return parse(raw)
    }

    /** Tìm trí nhớ liên quan bằng từ khoá. Bản sau có thể thay bằng tìm kiếm vector (embedding). */
    private suspend fun relevantMemories(text: String): List<String> {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        return db.memories().all()
            .map { m -> m.content to words.count { m.content.lowercase().contains(it) } }
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first }
    }

    private fun buildPrompt(user: String, memories: List<String>, history: List<ChatMessage>, money: String): String {
        val now = fmt.format(Date())
        val mem = if (memories.isEmpty()) "(chưa có)" else memories.joinToString("\n") { "- $it" }
        val hist = history.joinToString("\n") { (if (it.fromUser) "Người dùng: " else "Trợ lý: ") + it.text }
        return """
<start_of_turn>user
Bạn là trợ lý cá nhân nói tiếng Việt, ngắn gọn, thân thiện. Giờ hiện tại: $now.
Những điều bạn đã biết về người dùng:
$mem

Sổ chi định kỳ (dữ liệu thật, chỉ trả lời về tiền dựa trên phần này, không đoán):
$money

Hội thoại gần đây:
$hist

Chỉ trả lời bằng MỘT đối tượng JSON, không viết gì khác:
{"reply": "câu trả lời", "action": null}
hoặc kèm một hành động:
- Ghi nhớ:    {"type":"remember","content":"..."}
- Quên đi:    {"type":"forget","keyword":"từ khoá của điều cần quên"}
- Nhắc nhở:   {"type":"remind","text":"...","at":"yyyy-MM-ddTHH:mm"}
- Báo thức:   {"type":"alarm","hour":7,"minute":0,"label":"..."}
- Gọi điện:   {"type":"call","number":"..."}
- Nhắn tin:   {"type":"sms","number":"...","body":"..."}
- Mở web/bản đồ: {"type":"open_url","url":"..."}
- Thêm khoản chi cố định hằng tháng: {"type":"add_obligation","name":"Tiền nhà","day":5,"amount":5000000,"keywords":"tien nha,thue nha"}
- Báo đã trả một khoản: {"type":"paid","name":"Tiền nhà","amount":5000000}

Lời nhắn mới: $user<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    private fun parse(raw: String): AssistantTurn {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return AssistantTurn(raw.trim(), null)
        return try {
            val obj = JSONObject(raw.substring(start, end + 1))
            val a = obj.optJSONObject("action")
            AssistantTurn(obj.optString("reply", ""), a?.let { Action(it.optString("type"), it) })
        } catch (e: Exception) {
            AssistantTurn(raw.trim(), null)
        }
    }
}
