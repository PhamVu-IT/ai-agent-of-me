package vn.phamvu.trolyai.dates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LunarAndExtractorTest {

    @Test fun tetNguyenDan() {
        assertEquals(LocalDate.of(2024, 2, 10), LunarCalendar.toSolar(LunarDate(1, 1, 2024)))
        assertEquals(LocalDate.of(2025, 1, 29), LunarCalendar.toSolar(LunarDate(1, 1, 2025)))
        assertEquals(LocalDate.of(2026, 2, 17), LunarCalendar.toSolar(LunarDate(1, 1, 2026)))
        assertEquals(LocalDate.of(2027, 2, 6), LunarCalendar.toSolar(LunarDate(1, 1, 2027)))
    }

    @Test fun thangNhuanVaRam() {
        assertEquals(LocalDate.of(2025, 7, 25), LunarCalendar.toSolar(LunarDate(1, 6, 2025, leap = true)))
        assertEquals(LocalDate.of(2023, 3, 22), LunarCalendar.toSolar(LunarDate(1, 2, 2023, leap = true)))
        assertEquals(LocalDate.of(2025, 9, 6), LunarCalendar.toSolar(LunarDate(15, 7, 2025)))
        assertEquals(LocalDate.of(2026, 9, 25), LunarCalendar.toSolar(LunarDate(15, 8, 2026)))
    }

    @Test fun doiQuaDoiLaiKhongLech() {
        var d = LocalDate.of(1950, 1, 1)
        while (d.year < 2100) {
            assertEquals(d, LunarCalendar.toSolar(LunarCalendar.toLunar(d)))
            d = d.plusDays(1)
        }
    }

    @Test fun lanToi() {
        val today = LocalDate.of(2026, 11, 16) // mùng 8/10 âm
        assertEquals(LunarDate(8, 10, 2026), LunarCalendar.toLunar(today))
        assertEquals(0, Anniversary.next(8, 10, true, null, today).daysLeft)
        assertEquals(LocalDate.of(2027, 4, 16), Anniversary.next(10, 3, true, null, today).date)
        assertEquals(LocalDate.of(2027, 2, 28), Anniversary.next(29, 2, false, null, today).date)
        assertEquals(36, Anniversary.next(16, 11, false, 1990, today).years)
        for (y in 2025..2035) for (m in 1..12) Anniversary.lunarInYear(30, m, y) // tháng thiếu không lỗi
    }

    @Test fun timNgayTrongCau() {
        fun one(s: String) = DateExtractor.find(s).single()
        one("Giỗ ông nội mùng 10 tháng 3 âm lịch").let {
            assertEquals("Giỗ ông nội", it.title); assertEquals(10, it.day); assertEquals(3, it.month); assertTrue(it.lunar)
        }
        one("năm nào nhà mình cũng có dỗ bà ngoại ngày 5/8").let { assertEquals("Giỗ bà ngoại", it.title); assertTrue(it.lunar) }
        one("Bố tôi mất ngày 3/5 âm").let { assertEquals("Giỗ bố tôi", it.title) }
        one("Rằm tháng giêng có giỗ bác").let { assertEquals(15, it.day); assertEquals(1, it.month) }
        one("kỷ niệm ngày cưới 15/8/2015").let {
            assertEquals("Kỷ niệm ngày cưới", it.title); assertEquals(2015, it.year); assertEquals(false, it.lunar)
        }
        one("chồng em sinh ngày 2/9/1988").let { assertEquals("Sinh nhật chồng em", it.title) }
        assertEquals(2, DateExtractor.find("Sinh nhật con gái là 1 tháng 6, còn giỗ ông là 10/3").size)
    }

    @Test fun khongNhamVoiTienHayLichHen() {
        assertTrue(DateExtractor.find("tiền nhà trả ngày 5/12").isEmpty())
        assertTrue(DateExtractor.find("nhắc tôi họp lúc 10 giờ ngày 20/11").isEmpty())
        assertTrue(DateExtractor.find("lương về 5.000.000 ngày 10").isEmpty())
    }
}
