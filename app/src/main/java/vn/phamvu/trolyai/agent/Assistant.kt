package vn.phamvu.trolyai.agent

import org.json.JSONObject
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.learn.Profile
import vn.phamvu.trolyai.llm.ChatTurn
import vn.phamvu.trolyai.llm.LlmEngine
import java.text.SimpleDateFormat
import java.util.*

/** Một hành động trợ lý muốn thực hiện, do mô hình AI quyết định. */
data class Action(val type: String, val args: JSONObject)
data class AssistantTurn(val reply: String, val action: Action?, val tier: Tier = Tier.LLM)

/** Câu hỏi được trả lời ở tầng nào: đơn giản (tức thì), dữ liệu (đọc từ máy), hay cần AI suy nghĩ. */
enum class Tier { QUICK, DATA, LLM }

/**
 * "Bộ não": ghép lời nhắn + hồ sơ + quy tắc + trí nhớ + giờ hiện tại thành prompt,
 * yêu cầu mô hình trả lời dạng JSON gồm câu trả lời và (nếu cần) một hành động.
 */
class Assistant(private val llm: LlmEngine, private val db: AppDb) {

    private val fmt = SimpleDateFormat("EEEE yyyy-MM-dd'T'HH:mm", Locale("vi", "VN"))
    private val profile = Profile(db)
    private val data = DataReply(db)
    private val owner = OwnerReply(db)

    /**
     * Bộ phân loại 3 tầng:
     * 1. Câu đơn giản (ngày giờ, chào hỏi) trả lời ngay theo luật, không gọi AI.
     * 2. Câu tra cứu (khoản chi, nhắc nhở, điều đã nhớ) đọc thẳng dữ liệu trong máy.
     * 3. Còn lại mới gọi mô hình AI suy nghĩ; chữ hiện dần qua [onPartial] trong lúc AI đang viết.
     * Đang có lời sửa (sau 👎) thì luôn để AI xử lý để nó ghi được bài học.
     */
    suspend fun handle(userText: String, onPartial: (String) -> Unit = {}): AssistantTurn {
        // Tên, xưng hô, "tôi là ai": trả lời từ hồ sơ trên máy, luôn đúng và tức thì
        owner.answer(userText)?.let { return AssistantTurn(it, null, Tier.DATA) }
        val correction = profile.get(Profile.PENDING_CORRECTION)?.takeIf { it.isNotBlank() }
        if (correction == null) {
            val (self, you) = xungHo()
            QuickReply.answer(userText, self, you)?.let { return AssistantTurn(it, null, Tier.QUICK) }
            data.answer(userText, you)?.let { return AssistantTurn(it, null, Tier.DATA) }
        } else {
            profile.put(Profile.PENDING_CORRECTION, "")
        }
        // Tin nhắn mới nhất đã được lưu trước khi gọi hàm này; bỏ nó khỏi lịch sử để không lặp.
        // Lịch sử chỉ dùng khi mô hình bắt đầu cuộc trò chuyện mới; các lượt sau mô hình tự nhớ.
        val history = db.chats().recent(7).drop(1).reversed().map { it.fromUser to it.text }
        val turn = ChatTurn(
            system = systemPrompt(persona(), db.memories().ofKind("rule").map { it.content }),
            history = history,
            user = userTurn(
                user = userText,
                memories = relevantMemories(userText),
                money = if (aboutMoney(userText)) Finance(db).summaryForPrompt() else null,
                correction = correction,
            ),
        )
        val raw = llm.chat(turn) { partial -> partialReply(partial)?.let(onPartial) }
        return parse(raw)
    }

    private suspend fun xungHo(): Pair<String, String> =
        profile.xungHo().split("-").map { it.trim() }.let { it[0] to it.getOrElse(1) { "bạn" } }

    /** Chỉ đưa sổ chi vào prompt khi câu hỏi nói về tiền, để prompt ngắn và AI chạy nhanh hơn. */
    private fun aboutMoney(text: String): Boolean =
        Regex("\\b(tien|tra|chi|no|luong|ngan hang|chuyen khoan|khoan|dong|trieu|nghin|k)\\b|\\d")
            .containsMatchIn(Finance.normalize(text))

    /**
     * Lấy phần "reply" từ JSON AI đang viết dở để hiện dần lên màn hình.
     * AI viết {"reply": "Chào bạn...  thì trả về "Chào bạn...". Chưa tới phần reply thì trả null.
     */
    private fun partialReply(raw: String): String? {
        val trimmed = raw.trimStart().removePrefix("```json").removePrefix("```").trimStart()
        if (!trimmed.startsWith("{")) return trimmed.takeIf { it.isNotBlank() }
        val key = Regex("\"reply\"\\s*:\\s*\"").find(trimmed) ?: return null
        val sb = StringBuilder()
        var i = key.range.last + 1
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == '\\') {
                if (i + 1 >= trimmed.length) break
                when (val n = trimmed[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append(' '); else -> sb.append(n) }
                i += 2; continue
            }
            if (c == '"') break
            sb.append(c); i++
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private suspend fun persona(): String {
        val me = profile.assistantName()
        val (self, you) = profile.xungHo().split("-").map { it.trim() }.let { it[0] to it.getOrElse(1) { "bạn" } }
        val name = profile.userName()
        val who = if (name == null) "người dùng (chưa biết tên, hãy hỏi tên và cách xưng hô khi thích hợp)" else name
        val ownerLine = if (name == null) "" else
            "\nNgười đang nhắn cho bạn chính là $name, chủ của chiếc điện thoại này. Nếu họ hỏi họ là ai, trả lời là $name."
        return """
Bạn tên là $me, một người bạn thân của $who, sống trong điện thoại của họ.$ownerLine
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
        val top = scored.filter { it.second >= 1 }.sortedByDescending { it.second }.take(6).map { it.first }
        val recent = all.take(3).map { it.content }
        return (top + recent).distinct()
    }

    /**
     * Phần cố định của cuộc trò chuyện: vai trò, hồ sơ, quy tắc và cách trả lời. Không đưa giờ hay dữ liệu thay đổi
     * từng câu vào đây, vì phần này đổi thì mô hình phải bắt đầu lại cuộc trò chuyện (chậm hơn).
     */
    private suspend fun systemPrompt(persona: String, rules: List<String>): String {
        val summary = profile.summary() ?: "(chưa có, sẽ tự hình thành sau vài ngày trò chuyện)"
        val ruleText = if (rules.isEmpty()) "(chưa có)" else rules.joinToString("\n") { "- $it" }
        return """
$persona

Hồ sơ về họ (bạn tự tóm tắt từ những lần trò chuyện trước):
$summary

Quy tắc họ đã dặn, LUÔN tuân theo:
$ruleText

Mỗi lời nhắn của họ kèm giờ hiện tại, điều bạn nhớ liên quan và (nếu cần) sổ chi định kỳ. Sổ chi là dữ liệu thật:
chỉ trả lời về tiền dựa trên đó, không đoán.

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
""".trim()
    }

    /** Lời nhắn của lượt này kèm dữ liệu chỉ dùng cho câu này. */
    private fun userTurn(user: String, memories: List<String>, money: String?, correction: String?): String {
        val now = fmt.format(Date())
        val mem = if (memories.isEmpty()) "(chưa có)" else memories.joinToString("\n") { "- $it" }
        return buildString {
            append("Giờ hiện tại: $now.\n")
            append("Điều bạn nhớ liên quan:\n$mem\n")
            if (money != null) append("Sổ chi định kỳ:\n$money\n")
            if (correction != null) append("""
LƯU Ý: họ vừa không hài lòng với câu trả lời này của bạn: "$correction".
Lời nhắn mới có thể là lời sửa. Nếu đúng vậy, xin lỗi ngắn gọn, làm lại cho đúng,
và ghi lại bài học bằng hành động learn_rule để lần sau không sai nữa.
""")
            append("\nLời nhắn mới: $user")
        }
    }

    private fun parse(raw: String): AssistantTurn {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        // Không có JSON trọn vẹn (ví dụ bấm dừng giữa chừng): lấy phần reply đã viết được
        if (start < 0 || end <= start) return AssistantTurn(partialReply(raw) ?: raw.trim(), null)
        return try {
            val obj = JSONObject(raw.substring(start, end + 1))
            val a = obj.optJSONObject("action")
            AssistantTurn(obj.optString("reply", ""), a?.let { Action(it.optString("type"), it) })
        } catch (e: Exception) {
            // JSON hỏng (thường do AI viết bị cắt): vẫn cứu được phần reply nếu có
            AssistantTurn(partialReply(raw) ?: raw.trim(), null)
        }
    }
}
