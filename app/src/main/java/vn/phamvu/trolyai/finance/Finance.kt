package vn.phamvu.trolyai.finance

import vn.phamvu.trolyai.data.*
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/** Sổ chi định kỳ: biết khoản nào đã trả, khoản nào chưa, trong tháng này. */
class Finance(private val db: AppDb) {
    private val dao get() = db.finance()

    data class Status(val ob: Obligation, val payment: Payment?, val daysLeft: Int)

    suspend fun statusThisMonth(): List<Status> {
        val paid = dao.paymentsIn(thisMonth()).associateBy { it.obligationId }
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return dao.obligations().map { Status(it, paid[it.id], it.dayOfMonth - today) }
    }

    /** Đoạn văn đưa vào prompt để AI trả lời câu hỏi về tiền từ dữ liệu thật. */
    suspend fun summaryForPrompt(): String {
        val list = statusThisMonth()
        if (list.isEmpty()) return "(chưa có khoản nào; nếu người dùng nhắc tới khoản chi cố định, hãy hỏi ngày và số tiền rồi thêm vào)"
        val dmy = SimpleDateFormat("dd/MM", Locale.US)
        val lines = list.map { s ->
            val head = "- ${s.ob.name} (hạn ngày ${s.ob.dayOfMonth}, ${money(s.ob.amount)})"
            if (s.payment != null) "$head: ĐÃ TRẢ ngày ${dmy.format(Date(s.payment.paidAt))}, ${money(s.payment.amount)}"
            else if (s.daysLeft < 0) "$head: CHƯA TRẢ, quá hạn ${-s.daysLeft} ngày"
            else "$head: CHƯA TRẢ, còn ${s.daysLeft} ngày"
        }
        val pending = dao.txnsSince(System.currentTimeMillis() - 7 * DAY).filter { it.status == "cho" }
        val extra = if (pending.isEmpty()) "" else
            "\nTiền ra gần đây chưa gán vào khoản nào:\n" + pending.joinToString("\n") { "- ${dmy.format(Date(it.at))}: ${money(it.amount)}" }
        return "Tháng ${thisMonth()}:\n" + lines.joinToString("\n") + extra
    }

    suspend fun addObligation(name: String, day: Int, amount: Long, keywords: String): Long =
        dao.insertObligation(Obligation(name = name, dayOfMonth = day.coerceIn(1, 31), amount = amount,
            keywords = (keywords.split(",") + name).joinToString(",") { normalize(it).trim() }))

    suspend fun findByName(name: String): Obligation? {
        val n = normalize(name)
        return dao.obligations().firstOrNull { o ->
            normalize(o.name).let { it.contains(n) || n.contains(it) } ||
                o.keywords.split(",").any { it.isNotBlank() && n.contains(it) }
        }
    }

    suspend fun markPaid(ob: Obligation, amount: Long?, source: String) {
        dao.insertPayment(Payment(obligationId = ob.id, month = thisMonth(), amount = amount ?: ob.amount, source = source))
    }

    /** Đoán giao dịch ngân hàng thuộc khoản nào: ưu tiên từ khoá trong nội dung, sau đó số tiền lệch dưới 5%. */
    suspend fun guess(text: String, amount: Long): Obligation? {
        val unpaid = statusThisMonth().filter { it.payment == null }.map { it.ob }
        val t = normalize(text)
        return unpaid.firstOrNull { o -> o.keywords.split(",").any { it.length >= 3 && t.contains(it) } }
            ?: unpaid.firstOrNull { o -> o.amount > 0 && abs(o.amount - amount) <= o.amount * 0.05 }
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        fun thisMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        fun money(v: Long): String = "%,d".format(Locale.US, v).replace(',', '.') + "đ"

        /** Bỏ dấu, chữ thường: "Tiền Nhà" → "tien nha", để khớp với nội dung chuyển khoản không dấu. */
        fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
    }
}
