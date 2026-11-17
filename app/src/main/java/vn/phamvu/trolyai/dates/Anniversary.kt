package vn.phamvu.trolyai.dates

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Tính lần tới của một ngày lặp lại hằng năm (giỗ, sinh nhật, kỷ niệm), theo âm lịch hoặc dương lịch.
 * Không phụ thuộc Android để chạy thử được trên máy tính.
 */
object Anniversary {

    /** Lần tới: ngày dương lịch, còn bao nhiêu ngày, và là lần thứ mấy (nếu biết năm gốc). */
    data class Next(val date: LocalDate, val daysLeft: Int, val years: Int?)

    /**
     * Lần tới tính từ [from] (tính cả chính ngày [from]).
     * Âm lịch: tháng thiếu không có ngày 30 thì lấy ngày 29, như cách các nhà vẫn làm giỗ.
     * Dương lịch: 29/2 vào năm không nhuận thì lấy 28/2.
     */
    fun next(day: Int, month: Int, lunar: Boolean, originYear: Int?, from: LocalDate): Next {
        val (date, year) = if (lunar) nextLunar(day, month, from) else nextSolar(day, month, from)
        val years = originYear?.let { year - it }?.takeIf { it > 0 }
        return Next(date, ChronoUnit.DAYS.between(from, date).toInt(), years)
    }

    private fun nextSolar(day: Int, month: Int, from: LocalDate): Pair<LocalDate, Int> {
        fun inYear(y: Int): LocalDate {
            val ym = java.time.YearMonth.of(y, month)
            return ym.atDay(day.coerceAtMost(ym.lengthOfMonth()))
        }
        val thisYear = inYear(from.year)
        return if (!thisYear.isBefore(from)) thisYear to from.year else inYear(from.year + 1) to from.year + 1
    }

    private fun nextLunar(day: Int, month: Int, from: LocalDate): Pair<LocalDate, Int> {
        val lunarYear = LunarCalendar.toLunar(from).year
        for (y in lunarYear..lunarYear + 1) {
            val d = lunarInYear(day, month, y)
            if (!d.isBefore(from)) return d to y
        }
        return lunarInYear(day, month, lunarYear + 2) to lunarYear + 2
    }

    /** Ngày dương lịch của ngày [day] tháng [month] âm lịch (tháng thường, không nhuận) năm âm [year]. */
    fun lunarInYear(day: Int, month: Int, year: Int): LocalDate {
        val d = day.coerceIn(1, 30).coerceAtMost(LunarCalendar.monthLength(month, year))
        return LunarCalendar.toSolar(LunarDate(d, month, year))!!
    }

    private val weekdays = listOf("Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ nhật")

    fun weekday(d: LocalDate) = weekdays[d.dayOfWeek.value - 1]

    /** Ví dụ "mùng 10/3 âm lịch", "rằm tháng 7 âm lịch" hay "20/10". */
    fun label(day: Int, month: Int, lunar: Boolean): String = when {
        !lunar -> "$day/$month"
        day == 15 -> "rằm tháng $month âm lịch"
        day <= 10 -> "mùng $day/$month âm lịch"
        else -> "$day/$month âm lịch"
    }

    /** Ví dụ "Thứ Bảy 18/4/2027". */
    fun solarLabel(d: LocalDate) = "${weekday(d)} ${d.dayOfMonth}/${d.monthValue}/${d.year}"

    /** "hôm nay", "ngày mai", "còn 3 ngày". */
    fun whenLabel(daysLeft: Int) = when (daysLeft) {
        0 -> "hôm nay"
        1 -> "ngày mai"
        2 -> "ngày kia"
        else -> "còn $daysLeft ngày"
    }

    /** Ví dụ "tròn 30 tuổi", "tròn 5 năm", "giỗ năm thứ 3". */
    fun yearsLabel(kind: String, years: Int?): String? = years?.let {
        when (kind) {
            "sinh_nhat" -> "tròn $it tuổi"
            "gio" -> if (it == 1) "giỗ đầu" else if (it == 2) "giỗ hết" else "giỗ năm thứ $it"
            else -> "tròn $it năm"
        }
    }

    /** Lịch âm hôm nay, ví dụ "mùng 8/10 âm lịch". */
    fun lunarToday(d: LocalDate): String {
        val l = LunarCalendar.toLunar(d)
        return label(l.day, l.month, true) + if (l.leap) " (tháng nhuận)" else ""
    }
}
