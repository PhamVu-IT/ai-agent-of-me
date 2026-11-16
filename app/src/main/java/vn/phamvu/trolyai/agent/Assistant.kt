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
data class AssistantTurn(
    val reply: String,
    val action: Action?,
    val tier: Tier = Tier.LLM,
    /** Skill / công cụ MCP đã chạy trong lượt này (nhãn, ảnh, trang web để hiện cho người dùng). */
    val tools: List<ToolOutput> = emptyList(),
)

/** Câu hỏi được trả lời ở tầng nào: đơn giản (tức thì), dữ liệu (đọc từ máy), hay cần AI suy nghĩ. */
enum class Tier { QUICK, DATA, LLM }

/**
 * "Bộ não": ghép lời nhắn + hồ sơ + quy tắc + trí nhớ + giờ hiện tại thành prompt,
 * yêu cầu mô hình trả lời dạng JSON gồm câu trả lời và (nếu cần) một hành động.
 */
class Assistant(private val llm: LlmEngine, private val db: AppDb, private val tools: AgentTools? = null) {

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
    suspend fun handle(
        userText: String,
        images: List<ByteArray> = emptyList(),
        audios: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
    ): AssistantTurn {
        // Có ảnh hay ghi âm gửi kèm thì luôn để AI xem / nghe, không trả lời theo luật
        val media = images.isNotEmpty() || audios.isNotEmpty()
        // Tên, xưng hô, "tôi là ai": trả lời từ hồ sơ trên máy, luôn đúng và tức thì
        if (!media) owner.answer(userText)?.let { return AssistantTurn(it, null, Tier.DATA) }
        val correction = profile.get(Profile.PENDING_CORRECTION)?.takeIf { it.isNotBlank() }
        if (correction == null && !media) {
            val (self, you) = xungHo()
            QuickReply.answer(userText, self, you)?.let { return AssistantTurn(it, null, Tier.QUICK) }
            data.answer(userText, you)?.let { return AssistantTurn(it, null, Tier.DATA) }
        } else if (correction != null) {
            profile.put(Profile.PENDING_CORRECTION, "")
        }
        // Tin nhắn mới nhất đã được lưu trước khi gọi hàm này; bỏ nó khỏi lịch sử để không lặp.
        // Lịch sử chỉ dùng khi mô hình bắt đầu cuộc trò chuyện mới; các lượt sau mô hình tự nhớ.
        val history = db.chats().recent(7).drop(1).reversed().map { it.fromUser to it.text }
        val turn = ChatTurn(
            system = systemPrompt(persona(), db.memories().ofKind("rule").map { it.content }) + (tools?.promptSection() ?: ""),
            history = history,
            user = userTurn(
                user = userText,
                memories = relevantMemories(userText),
                money = if (aboutMoney(userText)) Finance(db).summaryForPrompt() else null,
                correction = correction,
                images = images.size,
                audios = audios.size,
            ),
            images = images,
            audios = audios,
        )
        return agentLoop(turn, userText, onPartial, onThinking)
    }

    /**
     * Như Gallery: mô hình có thể gọi skill / công cụ MCP; app chạy rồi gửi kết quả lại, mô hình trả lời tiếp.
     * Tối đa [MAX_STEPS] bước để không lặp mãi. Chữ mô hình viết ở các bước được nối lại để hiện dần.
     */
    private suspend fun agentLoop(
        first: ChatTurn,
        userText: String,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit,
    ): AssistantTurn {
        var turn = first
        val shown = StringBuilder()
        val used = mutableListOf<ToolOutput>()
        repeat(MAX_STEPS) { step ->
            val before = shown.toString()
            val raw = llm.chat(turn, { partial -> partialReply(partial)?.let { onPartial(before + it) } }, onThinking)
            val res = parse(raw)
            val action = res.action
            val toolBox = tools
            if (action == null || toolBox == null || !toolBox.handles(action.type) || step == MAX_STEPS - 1) {
                return AssistantTurn((before + res.reply).trim(), action?.takeIf { toolBox?.handles(it.type) != true },
                    Tier.LLM, used)
            }
            if (res.reply.isNotBlank()) shown.append(res.reply.trim()).append("\n")
            onPartial(shown.toString() + "⏳ Đang chạy ${action.args.optString("name").ifBlank {
                action.args.optString("skill").ifBlank { action.args.optString("tool").ifBlank { action.type } } }}…")
            val out = toolBox.run(action)
            used += out
            // Lượt kế tiếp: kết quả công cụ + nhắc lại câu hỏi (mô hình .task không nhớ lượt trước)
            turn = ChatTurn(
                system = first.system,
                history = first.history,
                user = "Kết quả của lệnh ${action.type} (${out.label}):\n${out.text}\n\n" +
                    "Tiếp tục làm theo hướng dẫn nếu cần, hoặc trả lời câu hỏi ban đầu của họ: \"$userText\"",
            )
        }
        return AssistantTurn(shown.toString().trim(), null, Tier.LLM, used)
    }

    private suspend fun xungHo(): Pair<String, String> =
        profile.xungHo().split("-").map { it.trim() }.let { it[0] to it.getOrElse(1) { "bạn" } }

    /** Chỉ đưa sổ chi vào prompt khi câu hỏi nói về tiền, để prompt ngắn và AI chạy nhanh hơn. */
    private fun aboutMoney(text: String): Boolean =
        Regex("\\b(tien|tra|chi|no|luong|ngan hang|chuyen khoan|khoan|dong|trieu|nghin|k)\\b|\\d")
            .containsMatchIn(Finance.normalize(text))

    /**
     * Phần chữ để hiện dần lên màn hình trong lúc AI đang viết. AI trả lời bằng lời bình thường như
     * Google AI Edge Gallery; nếu có việc cần làm thì dòng cuối là "[[LỆNH]] {...}", phần đó không hiện ra.
     * Chưa có chữ nào để hiện thì trả null.
     */
    private fun partialReply(raw: String): String? {
        val text = stripFence(raw)
        // Mô hình cũ quen trả JSON {"reply": "..."}: vẫn lấy được phần reply
        if (text.startsWith("{")) return jsonReplyPartial(text)
        return cutAction(text).trimEnd().takeIf { it.isNotBlank() }
    }

    /** Bỏ phần lệnh ở cuối: từ "[[" (kể cả đang viết dở) hoặc một dòng bắt đầu bằng "{". */
    private fun cutAction(text: String): String {
        var end = text.length
        ACTION_MARK.find(text)?.let { end = minOf(end, it.range.first) }
        text.indexOf("[[").takeIf { it >= 0 }?.let { end = minOf(end, it) }
        // Dấu "[" lẻ ở cuối có thể là đầu của "[[LỆNH]]" đang viết dở
        if (text.endsWith("[")) end = minOf(end, text.length - 1)
        Regex("(?m)^\\s*\\{").find(text)?.let { end = minOf(end, it.range.first) }
        return text.substring(0, end)
    }

    private fun stripFence(raw: String): String =
        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private fun jsonReplyPartial(trimmed: String): String? {
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

Cách trả lời: viết bằng lời bình thường, tự nhiên như nhắn tin cho bạn bè (được dùng **chữ đậm** và gạch đầu dòng).
KHÔNG viết JSON hay mã trong câu trả lời.
Chỉ khi cần làm một việc dưới đây thì sau câu trả lời viết thêm MỘT dòng cuối: [[LỆNH]] rồi đến JSON của việc đó.
Ví dụ:
Được rồi, 8 giờ sáng mai mình nhắc bạn uống thuốc nhé!
[[LỆNH]] {"type":"remind","text":"Uống thuốc","at":"2026-11-17T08:00"}
Các việc làm được:
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
    private fun userTurn(
        user: String, memories: List<String>, money: String?, correction: String?, images: Int = 0, audios: Int = 0,
    ): String {
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
            if (images > 0) append("\nHọ gửi kèm $images ảnh ở trên. Hãy xem kỹ ảnh rồi trả lời.")
            if (audios > 0) append("\nHọ gửi kèm một đoạn ghi âm ở trên. Hãy nghe kỹ rồi trả lời (nếu cần thì chép lại lời nói).")
            append("\nLời nhắn mới: $user")
        }
    }

    /** Tách câu trả lời (chữ bình thường) và lệnh ở dòng cuối (nếu có). */
    private fun parse(raw: String): AssistantTurn {
        val text = stripFence(raw)
        if (text.startsWith("{") && text.contains("\"reply\"")) return parseLegacyJson(text)
        val reply = cutAction(text).trim()
        val rest = text.substring(cutAction(text).length)
        val action = rest.indexOf('{').takeIf { it >= 0 }?.let { s ->
            val e = rest.lastIndexOf('}')
            if (e <= s) null else runCatching { JSONObject(rest.substring(s, e + 1)) }.getOrNull()
        }?.takeIf { it.optString("type").isNotBlank() }?.let { Action(it.optString("type"), it) }
        return AssistantTurn(reply.ifBlank { if (action == null) text else "" }, action)
    }

    /** Kiểu trả lời cũ {"reply": "...", "action": {...}}, phòng khi mô hình vẫn viết như vậy. */
    private fun parseLegacyJson(text: String): AssistantTurn {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (end <= start) return AssistantTurn(jsonReplyPartial(text) ?: text, null)
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            val a = obj.optJSONObject("action")
            AssistantTurn(obj.optString("reply", ""), a?.let { Action(it.optString("type"), it) })
        } catch (e: Exception) {
            // JSON hỏng (thường do AI viết bị cắt): vẫn cứu được phần reply nếu có
            AssistantTurn(jsonReplyPartial(text) ?: text, null)
        }
    }

    private companion object {
        const val MAX_STEPS = 4

        /** Dấu mở đầu dòng lệnh; nhận cả khi mô hình viết không dấu hay khác hoa thường. */
        val ACTION_MARK = Regex("\\[\\[\\s*L[ỆEệe]NH\\s*\\]\\]", RegexOption.IGNORE_CASE)
    }
}
