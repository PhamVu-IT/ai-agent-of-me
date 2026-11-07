package vn.phamvu.trolyai.finance

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.data.BankTxn

/**
 * Đọc thông báo biến động số dư của app ngân hàng / ví điện tử đang hiện trên máy.
 * Không đăng nhập ngân hàng, không gửi gì ra ngoài. Thông báo không phải giao dịch bị bỏ qua ngay,
 * không lưu lại. Chỉ giao dịch TIỀN RA (có dấu trừ) được ghi, để hỏi bạn đó là khoản nào.
 */
class BankNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val e = sbn.notification.extras
        val text = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ")
        val amount = parseOutflow(text) ?: return

        scope.launch {
            val db = (application as App).db
            val id = db.finance().insertTxn(BankTxn(app = sbn.packageName, text = text, amount = amount))
            val finance = Finance(db)
            val guess = finance.guess(text, amount)
            val ctx = this@BankNotificationListener
            if (guess != null) {
                FinanceAlarms.notify(ctx, 20_000 + id.toInt(),
                    "Vừa chuyển ${Finance.money(amount)}. Có phải khoản \"${guess.name}\" tháng này không?",
                    "Đúng", FinanceActionReceiver.txnYes(ctx, id, guess), FinanceActionReceiver.txnNo(ctx, id))
            }
            // Không đoán được thì vẫn lưu; bạn có thể nói "khoản 5 triệu hôm qua là tiền nhà" để gán.
        }
    }

    companion object {
        // Dấu hiệu đây là thông báo giao dịch, ví dụ: "TK 0123 | GD: -5,000,000VND | SD: ..."
        private val BANK_HINT = Regex("(?i)(số dư|so du|\\bSD\\b|\\bGD\\b|giao dịch|giao dich|biến động|bien dong|\\bTK\\b)")
        private val OUTFLOW = Regex("(?i)[-−]\\s?(\\d{1,3}(?:[.,]\\d{3})+|\\d{4,})\\s?(VND|VNĐ|đ|d)\\b")

        /** Trả về số tiền ra (VND), hoặc null nếu không phải giao dịch tiền ra. */
        fun parseOutflow(text: String): Long? {
            if (!BANK_HINT.containsMatchIn(text)) return null
            val m = OUTFLOW.find(text) ?: return null
            return m.groupValues[1].replace(".", "").replace(",", "").toLongOrNull()
        }
    }
}
