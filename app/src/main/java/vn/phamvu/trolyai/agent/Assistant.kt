package vn.phamvu.trolyai.agent

import org.json.JSONObject
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.learn.Profile
import vn.phamvu.trolyai.llm.LlmEngine
import java.text.SimpleDateFormat
import java.util.*

/** Một hành động trợ lý muốn thực hiện, do mô hình AI quyết định. */
data class Action(val type: String, val args: JSONObject)
data class AssistantTurn(val reply: String, val action: Action?)

/**
 * "Bộ não": ghép lời nhắn + hồ sơ + quy tắc + trí nhớ + giờ hiện tại thành prompt,
 * yêu cầu mô hình trả lời dạng JSON gồm câu trả lời và (nếu cần) một hành động.
 */
class Assistant(private val llm: LlmEngine, private val db: AppDb) {

    private val fmt = SimpleDateFormat("EEEE yyyy-MM-dd'T'HH:mm", Locale("vi", "VN"))
    private val profile = Profile(db)

    suspend fun handle(userText: String): AssistantTurn {
        // Tin nhắn mới nhất đã được lưu trước khi gọi hàm này; bỏ nó khỏi lịch sử để không lặp
        val history = db.chats().recent(9).drop(1).reversed()
        val correction = profile.get(Profile.PENDING_CORRECTION)
        if (correction != null) profile.put(Profile.PENDING_CORRECTION, "")
        val prompt = buildPrompt(
            user = userText,
            persona = persona(),
            rules = db.memories().ofKind("rule").map { it.content },
            memories = relevantMemories(userText),
            history = history,
            money = Finance(db).summaryForPrompt(),
            correction = correction?.takeIf { it.isNotBlank() },
        )
        return parse(llm.generate(prompt))
    }

    private suspend fun persona(): String {
        val me = profile.assistantName()
        val (self, you) = profile.xungHo().split("-").map { it.trim() }.let { it[0] to it.getOrElse(1) { "bạn" } }
        val name = profile.userName()
        val who = if (name == null) "người dùng (chưa biết tên, hãy hỏi tên và cách xưng hô khi thích hợp)" else name
        return """
Bạn tên là $me, một người bạn thân của $who, sống trong điện thoại của họ.
Xưng "$self", gọi họ là "$you". Nói chuyện tự nhiên như bạn bè: ấm áp, có chút hài hước, quan tâm thật lòng,
hỏi han lại chuyện họ đã kể, chia vui khi họ vui, động viên khi họ mệt. Câu ngắn, không giảng bài.
Thật thà: không biết thì nói không biết, không bịa. Bạn là AI và không giả vờ là người thật.
Khi họ có điều phiền lòng nghiêm trọng về sức khoẻ hay an toàn, khuyên họ tìm người thân hoặc chuyên gia.
""".trim()
    }

    /**
     * Chọn trí nhớ liên quan: so từ khoá đã bỏ dấu (nên "tom" khớp "tôm"),
     * cộng điểm cho điều mới ghi gần đây, luôn kèm vài điều mới nhất.
     */
    private suspend fun relevantMemories(text: String): List<String> {
        val words = Finance.normalize(text).split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val all = db.memories().all().filter { it.kind != "rule" }
        val now = System.currentTimeMillis()
        val scored = all.map { m ->
            val c = Finance.normalize(m.content)
            val hits = words.count { c.contains(it) }
            val fresh = if (now - m.createdAt < 7 * Finance.DAY) 0.5 else 0.0
            m.content to hits + fresh
        }
        val top = scored.filter { it.second >= 1 }.sortedByDescending { it.second }.take(10).map { it.first }
        val recent = all.take(5).map { it.content }
        return (top + recent).distinct()
    }

    private suspend fun buildPrompt(
        user: String, persona: String, rules: List<String>, memories: List<String>,
        history: List<ChatMessage>, money: String, correction: String?,
    ): String {
        val now = fmt.format(Date())
        val summary = profile.summary() ?: "(chưa có, sẽ tự hình thành sau vài ngày trò chuyện)"
        val ruleText = if (rules.isEmpty()) "(chưa có)" else rules.joinToString("\n") { "- $it" }
        val mem = if (memories.isEmpty()) "(chưa có)" else memories.joinToString("\n") { "- $it" }
        val hist = history.joinToString("\n") { (if (it.fromUser) "Họ: " else "Bạn: ") + it.text }
        val fix = if (correction == null) "" else """

LƯU Ý: họ vừa không hài lòng với câu trả lời này của bạn: "$correction".
Lời nhắn mới có thể là lời sửa. Nếu đúng vậy, xin lỗi ngắn gọn, làm lại cho đúng,
và ghi lại bài học bằng hành động learn_rule để lần sau không sai nữa."""
        return """
<start_of_turn>user
$persona
Giờ hiện tại: $now.

Hồ sơ về họ (bạn tự tóm tắt từ những lần trò chuyện trước):
$summary

Quy tắc họ đã dặn, LUÔN tuân theo:
$ruleText

Những điều liên quan bạn nhớ về họ:
$mem

Sổ chi định kỳ (dữ liệu thật, chỉ trả lời về tiền dựa trên phần này, không đoán):
$money

Hội thoại gần đây:
$hist$fix

Chỉ trả lời bằng MỘT đối tượng JSON, không viết gì khác:
{"reply": "câu trả lời", "action": null}
hoặc kèm một hành động:
- Ghi nhớ điều họ kể: {"type":"remember","content":"..."}
- Ghi lại quy tắc / lời sửa: {"type":"learn_rule","rule":"..."}
- Lưu tên và xưng hô: {"type":"set_profile","user_name":"Vũ","assistant_name":"Mây","xung_ho":"em - anh"}
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
