package vn.phamvu.trolyai.dates

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Một ngày âm lịch. [leap] = tháng nhuận. */
data class LunarDate(val day: Int, val month: Int, val year: Int, val leap: Boolean = false)

/**
 * Đổi dương lịch ⇄ âm lịch Việt Nam, chạy hoàn toàn offline.
 * Thuật toán thiên văn của Hồ Ngọc Đức (tính điểm sóc và trung khí theo giờ Hà Nội, UTC+7),
 * cũng là cách lịch Việt Nam được tính, đúng cho khoảng năm 1800–2199.
 */
object LunarCalendar {
    private const val TZ = 7.0

    fun toLunar(d: LocalDate): LunarDate = solarToLunar(d.dayOfMonth, d.monthValue, d.year)

    /** null nếu ngày âm không có (ví dụ chọn tháng nhuận mà năm đó không nhuận tháng này). */
    fun toSolar(l: LunarDate): LocalDate? = lunarToSolar(l.day, l.month, l.year, l.leap)

    /** Tháng âm có 29 hay 30 ngày. */
    fun monthLength(month: Int, year: Int, leap: Boolean = false): Int {
        val start = toSolar(LunarDate(1, month, year, leap)) ?: return 29
        val day30 = start.plusDays(29)
        return if (toLunar(day30).day == 30) 30 else 29
    }

    private fun int(x: Double) = floor(x).toInt()

    private fun jdFromDate(dd: Int, mm: Int, yy: Int): Int {
        val a = (14 - mm) / 12
        val y = yy + 4800 - a
        val m = mm + 12 * a - 3
        var jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        if (jd < 2299161) jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083
        return jd
    }

    private fun jdToDate(jd: Int): LocalDate {
        val b: Int
        val c: Int
        if (jd > 2299160) {
            val a = jd + 32044
            b = (4 * a + 3) / 146097
            c = a - (b * 146097) / 4
        } else {
            b = 0
            c = jd + 32082
        }
        val d = (4 * c + 3) / 1461
        val e = c - (1461 * d) / 4
        val m = (5 * e + 2) / 153
        val day = e - (153 * m + 2) / 5 + 1
        val month = m + 3 - 12 * (m / 10)
        val year = b * 100 + d - 4800 + m / 10
        return LocalDate.of(year, month, day)
    }

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        var jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd1 += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c1 = c1 - 0.4068 * sin(mpr * dr) + 0.0161 * sin(dr * 2 * mpr)
        c1 -= 0.0004 * sin(dr * 3 * mpr)
        c1 = c1 + 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr))
        c1 = c1 - 0.0074 * sin(dr * (m - mpr)) + 0.0004 * sin(dr * (2 * f + m))
        c1 = c1 - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mpr))
        c1 = c1 + 0.0010 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
        val deltat = if (t < -11) 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        else -0.000278 + 0.000265 * t + 0.000262 * t2
        return jd1 + c1 - deltat
    }

    private fun newMoonDay(k: Int): Int = int(newMoon(k) + 0.5 + TZ / 24)

    private fun sunLongitude(jdn: Double): Double {
        val t = (jdn - 2451545.0) / 36525
        val t2 = t * t
        val dr = PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m)
        dl += (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.000290 * sin(dr * 3 * m)
        var l = (l0 + dl) * dr
        l -= PI * 2 * int(l / (PI * 2))
        return l
    }

    /** Cung hoàng đạo (0..11) của mặt trời lúc đầu ngày. */
    private fun sunSector(dayNumber: Int): Int = int(sunLongitude(dayNumber - 0.5 - TZ / 24) / PI * 6)

    private fun lunarMonth11(yy: Int): Int {
        val off = jdFromDate(31, 12, yy) - 2415021
        val k = int(off / 29.530588853)
        var nm = newMoonDay(k)
        if (sunSector(nm) >= 9) nm = newMoonDay(k - 1)
        return nm
    }

    private fun leapMonthOffset(a11: Int): Int {
        val k = int((a11 - 2415021.076998695) / 29.530588853 + 0.5)
        var i = 1
        var arc = sunSector(newMoonDay(k + i))
        var last: Int
        do {
            last = arc
            i++
            arc = sunSector(newMoonDay(k + i))
        } while (arc != last && i < 14)
        return i - 1
    }

    private fun solarToLunar(dd: Int, mm: Int, yy: Int): LunarDate {
        val dayNumber = jdFromDate(dd, mm, yy)
        var k = int((dayNumber - 2415021.076998695) / 29.530588853) + 1
        var monthStart = newMoonDay(k)
        // Ước lượng k có khi lệch một tháng (ví dụ 7/5/2054): lùi tới khi đầu tháng không quá ngày cần đổi
        while (monthStart > dayNumber) monthStart = newMoonDay(--k)
        var a11 = lunarMonth11(yy)
        var b11 = a11
        var lunarYear: Int
        if (a11 >= monthStart) {
            lunarYear = yy
            a11 = lunarMonth11(yy - 1)
        } else {
            lunarYear = yy + 1
            b11 = lunarMonth11(yy + 1)
        }
        val lunarDay = dayNumber - monthStart + 1
        val diff = int((monthStart - a11) / 29.0)
        var leap = false
        var lunarMonth = diff + 11
        if (b11 - a11 > 365) {
            val leapDiff = leapMonthOffset(a11)
            if (diff >= leapDiff) {
                lunarMonth = diff + 10
                if (diff == leapDiff) leap = true
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1
        return LunarDate(lunarDay, lunarMonth, lunarYear, leap)
    }

    private fun lunarToSolar(lunarDay: Int, lunarMonth: Int, lunarYear: Int, leap: Boolean): LocalDate? {
        if (lunarMonth !in 1..12 || lunarDay !in 1..30) return null
        val a11: Int
        val b11: Int
        if (lunarMonth < 11) {
            a11 = lunarMonth11(lunarYear - 1)
            b11 = lunarMonth11(lunarYear)
        } else {
            a11 = lunarMonth11(lunarYear)
            b11 = lunarMonth11(lunarYear + 1)
        }
        val k = int(0.5 + (a11 - 2415021.076998695) / 29.530588853)
        var off = lunarMonth - 11
        if (off < 0) off += 12
        if (b11 - a11 > 365) {
            val leapOff = leapMonthOffset(a11)
            var leapMonth = leapOff - 2
            if (leapMonth < 0) leapMonth += 12
            if (leap && lunarMonth != leapMonth) return null
            if (leap || off >= leapOff) off += 1
        } else if (leap) {
            return null
        }
        val monthStart = newMoonDay(k + off)
        return jdToDate(monthStart + lunarDay - 1)
    }
}
