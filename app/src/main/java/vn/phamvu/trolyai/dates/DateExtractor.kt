package vn.phamvu.trolyai.dates

import java.text.Normalizer

/**
 * Tìm ngày giỗ, sinh nhật, kỷ niệm trong một câu tiếng Việt, không cần mô hình AI.
 * Ví dụ "giỗ ông nội mùng 10 tháng 3 âm" → Giỗ ông nội, 10/3, âm lịch.
 * Chỉ nhận khi trong cùng một vế câu có cả từ khoá (giỗ, sinh nhật, kỷ niệm, ngày cưới…) và một ngày,
 * để không nhầm với hạn trả tiền hay lịch hẹn.
 */
object DateExtractor {

    data class Found(
        val title: String,
        val day: Int,
        val month: Int,
        val year: Int?,
        val lunar: Boolean,
        /** "gio", "sinh_nhat", "ky_niem" */
        val kind: String,
    )

    private const val L = "\\p{L}"

    /** Từ khoá trên chữ có dấu (viết thường). Thứ tự quan trọng: cụm dài trước. */
    private val keywords: List<Triple<Regex, String, String>> = listOf(
        // (mẫu, loại, tiền tố tên) — tiền tố rỗng nghĩa là lấy chủ ngữ đứng trước, ví dụ "bố tôi mất ngày…"
        Triple(kw("ngày giỗ|đám giỗ|giỗ|dỗ(?! dành)|kỵ nhật|húy nhật|huý nhật"), "gio", "Giỗ"),
        Triple(kw("ngày mất|mất ngày|mất vào|mất hôm|qua đời ngày|qua đời vào|mất năm"), "gio", ""),
        Triple(kw("sinh nhật|ngày sinh"), "sinh_nhat", "Sinh nhật"),
        Triple(kw("sinh ngày|sinh vào|sinh hôm|sinh năm"), "sinh_nhat", ""),
        Triple(kw("kỷ niệm|kỉ niệm"), "ky_niem", "Kỷ niệm"),
        Triple(kw("ngày cưới|đám cưới|cưới nhau"), "ky_niem", "Kỷ niệm ngày cưới"),
    )

    private fun kw(alts: String) = Regex("(?<![$L])($alts)(?![$L])")

    private val months = mapOf("gieng" to 1, "chap" to 12, "mot" to 1, "hai" to 2, "ba" to 3, "tu" to 4, "bon" to 4,
        "nam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9, "muoi" to 10, "muoi mot" to 11, "muoi hai" to 12)

    private const val MONTH = "(\\d{1,2}|gieng|chap|muoi mot|muoi hai|muoi|mot|hai|ba|tu|bon|nam|sau|bay|tam|chin)"

    /** Mẫu ngày trên chữ đã bỏ dấu. Nhóm: ngày, tháng, năm. */
    private val datePatterns = listOf(
        // rằm tháng 7 / rằm tháng giêng
        Regex("(?<![a-z])ram\\s+thang\\s+$MONTH(?![a-z0-9])(?:\\s*(?:nam|,)?\\s*(\\d{4}))?") to true,
        // mùng 10 tháng 3 (năm 1990)
        Regex("(?<![a-z0-9])(?:(?:ngay|mung|mong)\\s+)?(\\d{1,2})\\s+thang\\s+$MONTH(?![a-z0-9])(?:\\s*(?:nam|,)?\\s*(\\d{4}))?") to false,
        // 10/3, 10-3, 10.3, 10/3/1990
        Regex("(?<![\\d./-])(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})(?:\\s*[/.-]\\s*(\\d{4}))?(?![\\d./-])") to false,
    )

    /** Bỏ dấu, giữ nguyên độ dài để vị trí chữ khớp với câu gốc. */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s.lowercase()) {
            if (c == 'đ') { append('d'); continue }
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            append(base)
        }
    }

    fun find(text: String): List<Found> {
        val orig = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        val norm = normalize(orig)
        val out = mutableListOf<Found>()
        // Chia theo vế câu để mỗi vế chỉ ra một ngày
        var start = 0
        for (m in Regex("[.;!?\\n]+|,\\s*(?:còn|và|với lại|rồi)\\s").findAll(orig) + sequenceOf(null)) {
            val end = m?.range?.first ?: orig.length
            if (end > start) clause(orig.substring(start, end), norm.substring(start, end), text, start)?.let(out::add)
            start = m?.range?.last?.plus(1) ?: orig.length
        }
        return out.distinctBy { listOf(it.kind, it.day, it.month, it.lunar) }
    }

    private fun clause(o: String, n: String, original: String, offset: Int): Found? {
        val kwHit = keywords.asSequence().mapNotNull { (re, kind, prefix) ->
            re.find(o)?.let { Triple(it, kind, prefix) }
        }.minByOrNull { it.first.range.first } ?: return null
        val (kwMatch, kind, prefix) = kwHit

        // Ngày gần từ khoá nhất
        val date = datePatterns.flatMap { (re, isRam) -> re.findAll(n).map { it to isRam }.toList() }
            .mapNotNull { (m, isRam) -> parseDate(m, isRam)?.let { Triple(m, it, isRam) } }
            .minByOrNull { (m, _, _) -> kotlin.math.abs(m.range.first - kwMatch.range.first) } ?: return null
        val (dm, dmy, isRam) = date
        val (day, month, year) = dmy

        val lunar = when {
            Regex("(?<![$L])(dương lịch|lịch dương|dl|lịch tây)(?![$L])").containsMatchIn(o) -> false
            Regex("(?<![$L])(âm lịch|âm|al|lịch âm|lịch ta)(?![$L])").containsMatchIn(o) -> true
            isRam || Regex("thang\\s+(gieng|chap)").containsMatchIn(n) -> true
            kind == "gio" -> true // giỗ ở Việt Nam hầu như luôn tính theo âm lịch
            else -> false
        }
        if (lunar && day > 30) return null

        val oc = Normalizer.normalize(original, Normalizer.Form.NFC).substring(offset, offset + o.length)
        val title = makeTitle(oc, kwMatch.range, dm.range, prefix, kind)
        return Found(title, day, month, year, lunar, kind)
    }

    private fun parseDate(m: MatchResult, isRam: Boolean): Triple<Int, Int, Int?>? {
        val g = m.groupValues
        val day: Int
        val monthRaw: String
        val yearRaw: String
        if (isRam) { day = 15; monthRaw = g[1]; yearRaw = g[2] } else { day = g[1].toIntOrNull() ?: return null; monthRaw = g[2]; yearRaw = g[3] }
        val month = monthRaw.toIntOrNull() ?: months[monthRaw.trim()] ?: return null
        if (day !in 1..31 || month !in 1..12) return null
        val year = yearRaw.toIntOrNull()?.takeIf { it in 1900..2200 }
        return Triple(day, month, year)
    }

    private val fillers = setOf("là", "vào", "ngày", "mùng", "mồng", "hôm", "rằm", "của", "có", "thì", "nhằm", "đúng",
        "nhé", "nha", "đó", "này", "nhớ", "và", "còn", "à", "ạ", "ơi", "năm", "tháng", "trước", "sau", "đã", "đều", "cũng",
        "lịch", "âm", "dương", "al", "dl")

    private fun words(s: String) = s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** Chữ đứng đầu được bỏ; "ngày" thì giữ vì "kỷ niệm ngày cưới" cần nó. */
    private val leadFillers = fillers - setOf("ngày")

    private fun trimFillers(ws: List<String>): List<String> {
        var a = 0
        var b = ws.size
        while (a < b && ws[a].lowercase() in leadFillers) a++
        while (b > a && ws[b - 1].lowercase() in fillers) b--
        return ws.subList(a, b).filter { it.none(Char::isDigit) }
    }

    /** Tên ngày: tiền tố theo loại + phần chữ sau từ khoá (hoặc chủ ngữ đứng trước nếu sau từ khoá không còn gì). */
    private fun makeTitle(o: String, kw: IntRange, date: IntRange, prefix: String, kind: String): String {
        val after = if (date.first > kw.last) o.substring(kw.last + 1, date.first)
        else o.substring(kw.last + 1).split(Regex("[,:\\d]"))[0]
        var rest = trimFillers(words(after)).take(6)
        if (rest.isEmpty() || prefix.isEmpty()) {
            // "bố tôi mất ngày 3/5", "ngày 10/3 là giỗ ông nội": lấy chữ đứng trước từ khoá (sau ngày nếu ngày ở trước)
            val from = if (date.last < kw.first) date.last + 1 else 0
            val before = trimFillers(words(o.substring(from, kw.first))).takeLast(4)
                // Chữ đầu câu viết hoa ("Bố tôi") thì hạ xuống: "Giỗ bố tôi"
                .mapIndexed { i, w -> if (i == 0 && o.trimStart().startsWith(w)) w.lowercase() else w }
            rest = before.ifEmpty { rest }
        }
        val head = prefix.ifEmpty { if (kind == "gio") "Giỗ" else if (kind == "sinh_nhat") "Sinh nhật" else "Kỷ niệm" }
        val t = (listOf(head) + rest).joinToString(" ")
        return if (rest.isEmpty() && kind == "gio") "Ngày giỗ" else t
    }
}
