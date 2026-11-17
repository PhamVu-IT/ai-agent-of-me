package vn.phamvu.trolyai.dates

import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.ImportantDate
import vn.phamvu.trolyai.data.Setting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** Sổ ngày quan trọng: thêm, tìm trong lời kể, và biết ngày nào sắp tới để tự nhắc. */
class Dates(private val db: AppDb) {

    data class Upcoming(val d: ImportantDate, val next: Anniversary.Next)

    /** Tất cả ngày, xếp theo lần tới gần nhất. */
    suspend fun upcoming(from: LocalDate = LocalDate.now()): List<Upcoming> =
        db.dates().all().map { Upcoming(it, Anniversary.next(it.day, it.month, it.lunar, it.year, from)) }
            .sortedBy { it.next.daysLeft }

    /**
     * Những ngày cần nhắc trong bản tin sáng hôm nay: trước [ImportantDate.remindDaysBefore] ngày,
     * trước 3 ngày, ngày mai và đúng ngày. Không nhắc mỗi ngày để khỏi phiền.
     */
    suspend fun toAnnounce(today: LocalDate = LocalDate.now()): List<Upcoming> = upcoming(today).filter {
        val left = it.next.daysLeft
        left <= it.d.remindDaysBefore.coerceAtLeast(1) && (left <= 1 || left == 3 || left == it.d.remindDaysBefore)
    }

    /** Những ngày đang trong khoảng cần nhắc (để trợ lý nói khi bạn mở chat). */
    suspend fun inWindow(today: LocalDate = LocalDate.now()): List<Upcoming> =
        upcoming(today).filter { it.next.daysLeft <= it.d.remindDaysBefore.coerceAtLeast(1) }

    /** Ví dụ "Giỗ ông nội (mùng 10/3 âm lịch): Thứ Sáu 16/4/2027, còn 151 ngày". */
    fun line(u: Upcoming): String {
        val extra = Anniversary.yearsLabel(u.d.kind, u.next.years)?.let { ", $it" } ?: ""
        return "${u.d.title} (${Anniversary.label(u.d.day, u.d.month, u.d.lunar)}$extra): " +
            "${Anniversary.solarLabel(u.next.date)}, ${Anniversary.whenLabel(u.next.daysLeft)}"
    }

    /** Thêm một ngày; trả null nếu đã có ngày trùng (cùng ngày tháng, cùng kiểu lịch, cùng loại hoặc cùng tên). */
    suspend fun add(
        title: String, day: Int, month: Int, year: Int?, lunar: Boolean, kind: String, source: String,
        lead: Int = defaultLead(kind),
    ): ImportantDate? = lock.withLock {
        if (month !in 1..12 || day !in 1..(if (lunar) 30 else 31) || title.isBlank()) return@withLock null
        val t = DateExtractor.normalize(title.trim())
        val dup = db.dates().all().any {
            it.day == day && it.month == month && it.lunar == lunar &&
                ((it.kind == kind && kind != "khac") || DateExtractor.normalize(it.title) == t)
        }
        if (dup) return@withLock null
        // Ngày bạn đã xoá thì trợ lý không tự thêm lại; chỉ thêm lại khi chính bạn thêm ở màn hình
        if (source != "tu_them" && signature(day, month, lunar, kind) in deleted()) return@withLock null
        val d = ImportantDate(title = title.trim(), day = day, month = month, year = year, lunar = lunar,
            kind = kind, remindDaysBefore = lead.coerceIn(1, 30), source = source)
        d.copy(id = db.dates().insert(d))
    }

    /** Xoá một ngày và ghi nhớ là đã xoá, để lần quét sau không tự thêm lại. */
    suspend fun delete(d: ImportantDate) {
        db.dates().delete(d.id)
        val list = (deleted() + signature(d.day, d.month, d.lunar, d.kind)).toSet()
        db.settings().put(Setting(DELETED, list.joinToString(";")))
    }

    private suspend fun deleted(): Set<String> =
        db.settings().get(DELETED)?.split(";")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun signature(day: Int, month: Int, lunar: Boolean, kind: String) = "$day|$month|$lunar|$kind"

    /** Tìm và lưu các ngày giỗ / sinh nhật / kỷ niệm trong một câu. Trả về những ngày mới lưu. */
    suspend fun captureFrom(text: String, source: String): List<ImportantDate> =
        DateExtractor.find(text).mapNotNull { add(it.title, it.day, it.month, it.year, it.lunar, it.kind, source) }

    /**
     * Đọc lại trí nhớ và tin nhắn cũ của bạn (từ lần quét trước) để tìm ngày đã kể mà chưa có trong sổ.
     * Nhờ vậy điều bạn kể từ năm ngoái, trước khi có tính năng này, vẫn được nhắc.
     */
    suspend fun scanOld(): List<ImportantDate> {
        val last = db.settings().get(SCAN_AT)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val texts = db.memories().all().filter { it.createdAt > last && it.kind != "rule" }.map { it.content } +
            db.chats().since(last + 1).filter { it.fromUser }.map { it.text }
        val added = texts.flatMap { captureFrom(it, "quet") }
        db.settings().put(Setting(SCAN_AT, now.toString()))
        return added
    }

    /** Câu xác nhận khi vừa lưu ngày mới, ví dụ "📅 Mình đã ghi Giỗ ông nội (mùng 10/3 âm lịch)…". */
    fun confirmation(added: List<ImportantDate>, from: LocalDate = LocalDate.now()): String? {
        if (added.isEmpty()) return null
        return added.joinToString("\n") { d ->
            val n = Anniversary.next(d.day, d.month, d.lunar, d.year, from)
            "📅 Đã ghi ${d.title} (${Anniversary.label(d.day, d.month, d.lunar)}) vào Ngày quan trọng. " +
                "Lần tới là ${Anniversary.solarLabel(n.date)}; năm nào mình cũng sẽ tự nhắc trước ${d.remindDaysBefore} ngày."
        }
    }

    /** Đoạn đưa vào prompt để AI biết những ngày sắp tới trong 30 ngày. */
    suspend fun promptBlock(): String? {
        val soon = upcoming().filter { it.next.daysLeft <= 30 }.take(5)
        if (soon.isEmpty()) return null
        return soon.joinToString("\n") { "- ${line(it)}" }
    }

    /**
     * Lời trợ lý tự nói khi bạn mở chat mà có ngày quan trọng sắp tới. Mỗi ngày chỉ nói một lần.
     * null nếu không có gì hoặc hôm nay đã nói.
     */
    suspend fun openingNote(you: String, today: LocalDate = LocalDate.now()): String? {
        val key = today.toEpochDay().toString()
        if (db.settings().get(NOTE_DAY) == key) return null
        val list = inWindow(today)
        if (list.isEmpty()) return null
        db.settings().put(Setting(NOTE_DAY, key))
        val head = if (list.size == 1) "Nhắc $you nè:" else "Nhắc $you mấy ngày sắp tới nè:"
        val tail = if (list.any { it.d.kind == "gio" && it.next.daysLeft >= 1 })
            "\nCần mình đặt lời nhắc đi chợ, mời họ hàng hay chuẩn bị gì thì cứ bảo nhé." else ""
        return head + "\n" + list.joinToString("\n") { "• ${line(it)}" } + tail
    }

    companion object {
        /** Mở app và bản tin sáng có thể cùng quét một lúc; khoá để không lưu trùng. */
        private val lock = Mutex()
        const val SCAN_AT = "dates_scan_at"
        const val NOTE_DAY = "dates_note_day"
        const val BRIEF_HOUR = "brief_hour"
        const val BRIEF_DAY = "brief_last_day"
        const val DELETED = "dates_deleted"

        /** Giỗ cần chuẩn bị (đi chợ, báo họ hàng) nên nhắc trước một tuần; còn lại trước 3 ngày. */
        fun defaultLead(kind: String) = if (kind == "gio") 7 else 3

        val kinds = listOf("gio" to "Giỗ", "sinh_nhat" to "Sinh nhật", "ky_niem" to "Kỷ niệm", "khac" to "Khác")
    }
}
