package vn.phamvu.trolyai.agent

import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.dates.Anniversary
import java.time.LocalDate
import java.util.Calendar

/**
 * Trả lời ngay, không cần mô hình AI, cho vài câu hỏi ngắn hay gặp: ngày tháng, giờ, thứ, chào hỏi.
 * Ngày giờ lấy từ đồng hồ máy nên luôn đúng, còn mô hình nhỏ hay đoán sai.
 * Chỉ bắt câu ngắn để không "cướp" câu hỏi dài cần AI suy nghĩ (ví dụ "nhắc tôi ngày mai lúc 7 giờ").
 */
object QuickReply {

    /** [self] là cách trợ lý tự xưng, [you] là cách gọi người dùng, ví dụ "mình" và "bạn". */
    fun answer(text: String, self: String = "mình", you: String = "bạn", now: Calendar = Calendar.getInstance()): String? {
        val t = Finance.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.split(' ').size > 8) return null
        // Câu có ý nhờ làm việc thì để AI xử lý
        if (Regex("\\b(nhac|bao thuc|dat|hen|goi|nhan|ghi|mo)\\b").containsMatchIn(t)) return null

        // Hỏi về giỗ, sinh nhật, kỷ niệm ("giỗ ông ngày mấy") thì để tầng dữ liệu tra sổ Ngày quan trọng
        if (Regex("(giỗ|dỗ|sinh nhật|kỷ niệm|kỉ niệm|ngày cưới)").containsMatchIn(text.lowercase())) return null

        val Self = self.replaceFirstChar { it.uppercase() }
        val asksDate = Regex("(ngay (may|bao nhieu|gi)|ngay thang|bua nay ngay|hom nay la ngay|^hom nay ngay|today|what date|am lich|ngay am|bao nhieu am|mung may)").containsMatchIn(t)
        val asksWeekday = Regex("(thu may|thu gi|hom nay thu|what day)").containsMatchIn(t)
        val asksTime = Regex("(may gio|gio la may|bay gio la|what time)").containsMatchIn(t)
        val asksMonthYear = !asksDate && Regex("(thang may|thang bao nhieu|nam may|nam bao nhieu|nam nay la nam)").containsMatchIn(t)

        if (asksDate || asksWeekday || asksTime || asksMonthYear) {
            val parts = mutableListOf<String>()
            if (asksTime) parts += "Bây giờ là %d giờ %02d phút".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            if (asksDate || asksWeekday) parts += "Hôm nay là ${weekday(now)}, ngày ${now.get(Calendar.DAY_OF_MONTH)} " +
                "tháng ${now.get(Calendar.MONTH) + 1} năm ${now.get(Calendar.YEAR)}, tức " +
                Anniversary.lunarToday(LocalDate.of(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)))
            else if (asksMonthYear) parts += "Bây giờ là tháng ${now.get(Calendar.MONTH) + 1} năm ${now.get(Calendar.YEAR)}"
            return parts.joinToString(". ") + "."
        }

        if (Regex("(khoe khong|co khoe|khoe ko|khoe k$|the nao roi|on khong|how are you)").containsMatchIn(t)) {
            return "$Self khoẻ lắm, cảm ơn $you đã hỏi! Còn $you hôm nay thế nào?"
        }
        if (Regex("^(xin chao|chao|alo|hello|hi|hey)( (ban|em|anh|chi|cau|may|nhe|nha|a|oi))*$").matches(t)) {
            return "Chào $you! $Self đây, có chuyện gì cứ nói $self nghe nhé."
        }
        if (Regex("^(cam on|cam on (ban|em|anh|chi|cau)|thanks|thank you|tks)( nhe| nha| nhieu)*$").matches(t)) {
            return "Không có gì đâu, $self luôn ở đây mà!"
        }
        return null
    }

    private fun weekday(c: Calendar) = when (c.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "thứ Hai"
        Calendar.TUESDAY -> "thứ Ba"
        Calendar.WEDNESDAY -> "thứ Tư"
        Calendar.THURSDAY -> "thứ Năm"
        Calendar.FRIDAY -> "thứ Sáu"
        Calendar.SATURDAY -> "thứ Bảy"
        else -> "Chủ nhật"
    }
}
