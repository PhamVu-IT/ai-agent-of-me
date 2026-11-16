package vn.phamvu.trolyai.agent

import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.dates.Dates
import vn.phamvu.trolyai.finance.Finance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tầng 2 của bộ phân loại: câu hỏi tra cứu dữ liệu có sẵn trong máy (khoản chi, nhắc nhở, điều đã nhớ).
 * Đọc thẳng từ cơ sở dữ liệu nên trả lời ngay và đúng số liệu, không cần mô hình AI.
 * Chỉ bắt câu hỏi ngắn dạng "xem / còn gì / bao nhiêu"; câu muốn thêm, sửa, xoá thì để AI xử lý.
 */
class DataReply(private val db: AppDb) {

    suspend fun answer(text: String, you: String = "bạn"): String? {
        val t = Finance.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.split(' ').size > 12) return null
        // Có số tiền hoặc ý ra lệnh thì là việc cần làm, không phải tra cứu
        if (Regex("\\d").containsMatchIn(t)) return null
        if (Regex("\\b(them|xoa|quen|sua|doi|da tra|vua tra|nhac toi|nhac minh|nhac em|nhac anh|dat|hen)\\b").containsMatchIn(t)) return null
        val isQuestion = Regex("(gi|nao|bao nhieu|may|chua|khong|ko|xem|liet ke|kiem tra|danh sach|con)\\b").containsMatchIn(t)
        if (!isQuestion) return null

        // "giỗ" bỏ dấu thành "gio", trùng với "giờ", nên xét trên chữ có dấu
        val raw = text.lowercase()
        return when {
            Regex("(giỗ|dỗ|sinh nhật|kỷ niệm|kỉ niệm|ngày quan trọng|ngày cưới)").containsMatchIn(raw) &&
                Regex("(sap toi|ngay may|hom nao|khi nao|bao gio|ngay nao|nhung ngay|co gi|ai khong|danh sach|xem|liet ke|nao)").containsMatchIn(t) ->
                dates(you)
            Regex("(khoan chi|khoan phai tra|phai tra|can tra|tien nha|tra no|no nan|chi tieu|so chi|het han|qua han|den han)").containsMatchIn(t) ->
                finance(you)
            Regex("(nhac nho|loi nhac|lich nhac|co lich|lich hen|viec can lam)").containsMatchIn(t) ->
                reminders(you)
            Regex("(nho gi ve|biet gi ve|nho nhung gi|da nho gi)").containsMatchIn(t) ->
                memories(you)
            else -> null
        }
    }

    private suspend fun finance(you: String): String {
        val list = Finance(db).statusThisMonth()
        if (list.isEmpty()) return "Sổ chi của $you đang trống. Kể mình nghe các khoản chi cố định hằng tháng (tên, ngày, số tiền) để mình ghi nhé."
        val lines = list.map { s ->
            val head = "• ${s.ob.name} (ngày ${s.ob.dayOfMonth}, ${Finance.money(s.ob.amount)})"
            when {
                s.payment != null -> "$head: đã trả ✅"
                s.daysLeft < 0 -> "$head: chưa trả, quá hạn ${-s.daysLeft} ngày ⚠️"
                s.daysLeft == 0 -> "$head: chưa trả, hạn hôm nay ⚠️"
                else -> "$head: chưa trả, còn ${s.daysLeft} ngày"
            }
        }
        val unpaid = list.filter { it.payment == null }.sumOf { it.ob.amount }
        val tail = if (unpaid > 0) "\nCòn phải trả tháng này: ${Finance.money(unpaid)}." else "\nTháng này $you đã trả hết rồi 👏"
        return "Sổ chi tháng ${Finance.thisMonth()}:\n" + lines.joinToString("\n") + tail
    }

    private suspend fun dates(you: String): String {
        val d = Dates(db)
        val list = d.upcoming()
        if (list.isEmpty()) return "Sổ ngày quan trọng của $you đang trống. Kể mình nghe ngày giỗ, sinh nhật, kỷ niệm " +
            "(ví dụ \"giỗ ông nội mùng 10 tháng 3 âm\") là năm nào mình cũng tự nhắc trước."
        return "Các ngày quan trọng sắp tới:\n" + list.take(10).joinToString("\n") { "• ${d.line(it)}" }
    }

    private suspend fun reminders(you: String): String {
        val list = db.reminders().upcoming(System.currentTimeMillis()).sortedBy { it.triggerAt }
        if (list.isEmpty()) return "Hiện $you không có lời nhắc nào sắp tới."
        val f = SimpleDateFormat("HH:mm dd/MM", Locale.US)
        return "Các lời nhắc sắp tới:\n" + list.take(10).joinToString("\n") { "• ${f.format(Date(it.triggerAt))}: ${it.text}" }
    }

    private suspend fun memories(you: String): String {
        val list = db.memories().all().filter { it.kind != "rule" }
        if (list.isEmpty()) return "Mình chưa ghi nhớ điều gì về $you cả. Kể mình nghe đi!"
        return "Những điều mình nhớ về $you:\n" + list.take(15).joinToString("\n") { "• ${it.content}" }
    }
}
