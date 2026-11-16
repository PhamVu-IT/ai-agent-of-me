package vn.phamvu.trolyai.agent

import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.learn.Profile

/**
 * Trợ lý biết chủ của mình là ai, không cần hỏi AI:
 * - "Tôi tên là Vũ", "Gọi tôi là anh Vũ" → lưu tên và cách xưng hô ngay.
 * - "Tôi là ai?", "Tên tôi là gì?", "Bạn có nhớ tôi không?" → trả lời từ hồ sơ đã lưu.
 * - "Bạn tên là Mây" → đặt tên cho trợ lý; "Bạn là ai?" → trợ lý tự giới thiệu.
 * Hồ sơ nằm trong bảng Setting (đã mã hoá), chỉ ở trên máy.
 */
class OwnerReply(private val db: AppDb) {
    private val profile = Profile(db)

    suspend fun answer(text: String): String? {
        val raw = text.trim().trimEnd('.', '!', '?', ' ')
        val t = norm(text)
        if (t.isEmpty() || t.split(' ').size > 12) return null

        // 1. Họ hỏi về chính họ
        if (Regex("^(vay |the )?(toi|tao|minh|to) (la ai|ten (la )?gi|ten gi)( (vay|the|nhi|nhe|a|ha))*$").matches(t) ||
            Regex("^ten (cua )?(toi|tao|minh|to) (la )?gi( (vay|the|nhi|a|ha))*$").matches(t) ||
            Regex("(biet|nho) (toi|tao|minh|to) (la ai|ten gi|khong|ko|chu)").containsMatchIn(t)
        ) return whoAmI()

        // 2. Họ hỏi trợ lý là ai
        if (Regex("^(ban|em|cau|may) (la ai|ten (la )?gi|ten gi)( (vay|the|nhi|a|ha))*$").matches(t) ||
            Regex("^ten (cua )?(ban|em|cau) (la )?gi( (vay|the|nhi|a|ha))*$").matches(t)
        ) return whoAreYou()

        // 3. Họ tự giới thiệu: lấy phần tên từ câu gốc (giữ dấu, giữ chữ hoa)
        val self = Regex("(?iu)^(?:tôi|tao|mình|tớ|em|anh|chị)\\s+tên\\s+(?:là\\s+)?(.+)$").find(raw)
            ?: Regex("(?iu)^tên\\s+(?:của\\s+)?(?:tôi|tao|mình|tớ|em|anh|chị)\\s+là\\s+(.+)$").find(raw)
            ?: Regex("(?iu)^(?:hãy\\s+|cứ\\s+)?gọi\\s+(?:tôi|tao|mình|tớ)\\s+là\\s+(.+)$").find(raw)
        if (self != null) return setOwner(self.groupValues[1])

        // 4. Họ đặt tên cho trợ lý
        val bot = Regex("(?iu)^(?:tên\\s+(?:của\\s+)?bạn\\s+là|bạn\\s+tên\\s+là|(?:mình|tôi|tao)?\\s*đặt\\s+tên\\s+(?:cho\\s+)?bạn\\s+là)\\s+(.+)$").find(raw)
        if (bot != null) {
            val name = cleanName(bot.groupValues[1]) ?: return null
            profile.put(Profile.ASSISTANT_NAME, name)
            val (s, y) = xungHo()
            return "Từ giờ $s tên là $name nhé! Cảm ơn $y đã đặt tên cho $s 😊"
        }
        return null
    }

    private suspend fun whoAmI(): String {
        val name = profile.userName()?.takeIf { it.isNotBlank() }
        val (s, y) = xungHo()
        if (name == null) return "${cap(s)} chưa biết tên $y. $y nói \"Tôi tên là …\" (hoặc \"Gọi tôi là anh …\") để $s nhớ nhé."
        val call = if (y == "bạn") "" else " ${cap(s)} gọi $y là \"$y\"."
        val sb = StringBuilder("${cap(y)} là $name, chủ của $s đó!$call")
        profile.summary()?.takeIf { it.isNotBlank() }?.let { sb.append("\n\nNhững gì $s biết về $y:\n").append(it.trim()) }
            ?: db.memories().all().filter { it.kind == "fact" }.take(5).takeIf { it.isNotEmpty() }?.let { list ->
                sb.append("\n\nVài điều $s nhớ về $y:\n").append(list.joinToString("\n") { "• ${it.content}" })
            }
        return sb.toString()
    }

    private suspend fun whoAreYou(): String {
        val me = profile.assistantName()
        val (s, y) = xungHo()
        val owner = profile.userName()?.takeIf { it.isNotBlank() }?.let { " của $it" } ?: ""
        return "${cap(s)} là $me, trợ lý AI$owner, sống ngay trong điện thoại này. Mọi điều $y kể chỉ nằm trên máy thôi."
    }

    private suspend fun setOwner(rawName: String): String? {
        val words = rawName.trim().split(Regex("\\s+"))
        // "anh Vũ", "chị Lan": từ đầu là cách gọi, phần sau là tên
        val pronoun = words.firstOrNull()?.lowercase()?.takeIf { it in YOU_TO_SELF }
        val namePart = if (pronoun != null) words.drop(1).joinToString(" ") else rawName
        val name = if (namePart.isBlank()) null else cleanName(namePart) ?: return null
        if (pronoun != null) profile.put(Profile.XUNG_HO, "${YOU_TO_SELF.getValue(pronoun)} - $pronoun")
        if (name != null) profile.put(Profile.USER_NAME, name)
        val (s, y) = xungHo()
        return when {
            name != null && pronoun != null -> "Rõ rồi, $s sẽ gọi là $pronoun $name. Rất vui được làm quen!"
            name != null -> "Chào $name! ${cap(s)} nhớ rồi, từ giờ $s biết $y là ai rồi nhé."
            else -> "Được, từ giờ $s gọi là \"$y\" nhé."
        }
    }

    /** Bỏ phần đuôi kiểu "nhé", "nha", dấu câu; tên dài quá 4 chữ hoặc là từ hỏi thì bỏ qua. */
    private fun cleanName(s: String): String? {
        val words = s.trim().trim('"', '\'', '“', '”').split(Regex("\\s+"))
            .dropLastWhile { norm(it) in TAIL }
        if (words.isEmpty() || words.size > 4) return null
        val n = norm(words.joinToString(" "))
        if (n in QUESTION || n.startsWith("gi ")) return null
        return words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }

    private suspend fun xungHo(): Pair<String, String> =
        profile.xungHo().split("-").map { it.trim() }.let { it[0] to it.getOrElse(1) { "bạn" } }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun norm(s: String) = Finance.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        /** Cách gọi người dùng → cách trợ lý tự xưng. */
        private val YOU_TO_SELF = mapOf(
            "anh" to "em", "chị" to "em", "em" to "anh", "bạn" to "mình", "cậu" to "tớ",
            "ông" to "cháu", "bà" to "cháu", "cô" to "cháu", "chú" to "cháu", "bác" to "cháu", "sếp" to "em",
        )
        private val TAIL = setOf("nhe", "nha", "nhen", "a", "day", "do", "nhe ban", "di")
        private val QUESTION = setOf("gi", "ai", "chi", "gi vay", "gi the", "nao")
    }
}
