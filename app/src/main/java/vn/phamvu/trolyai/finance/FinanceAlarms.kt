package vn.phamvu.trolyai.finance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.data.Obligation
import java.util.Calendar

object FinanceAlarms {
    /** Mỗi sáng 9 giờ kiểm tra các khoản sắp đến hạn hoặc quá hạn. */
    fun scheduleDaily(ctx: Context) {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val pi = PendingIntent.getBroadcast(ctx, 9000, Intent(ctx, DueCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY, pi)
    }

    /** Thông báo có hai nút; bấm nút không cần mở app. */
    fun notify(ctx: Context, id: Int, text: String, yesLabel: String, yes: Intent, no: Intent? = null) {
        fun pi(i: Intent, code: Int) = PendingIntent.getBroadcast(ctx, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, App.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Trợ lý AI")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .addAction(0, yesLabel, pi(yes.putExtra("notif", id), id * 2))
        if (no != null) b.addAction(0, "Không phải", pi(no.putExtra("notif", id), id * 2 + 1))
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}

class DueCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val finance = Finance((ctx.applicationContext as App).db)
            finance.statusThisMonth().filter { it.payment == null && it.daysLeft <= 2 }.forEach { s ->
                val when_ = when {
                    s.daysLeft < 0 -> "đã quá hạn ${-s.daysLeft} ngày"
                    s.daysLeft == 0 -> "đến hạn hôm nay"
                    else -> "còn ${s.daysLeft} ngày"
                }
                FinanceAlarms.notify(ctx, 10_000 + s.ob.id.toInt(),
                    "${s.ob.name} (${Finance.money(s.ob.amount)}) $when_.", "Đã trả",
                    FinanceActionReceiver.paid(ctx, s.ob))
            }
            pending.finish()
        }
    }
}

/** Xử lý nút bấm trên thông báo: "Đã trả", "Đúng", "Không phải". */
class FinanceActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val db = (ctx.applicationContext as App).db
            val finance = Finance(db)
            val ob = db.finance().obligation(intent.getLongExtra("ob", -1))
            val txnId = intent.getLongExtra("txn", -1)
            when (intent.action) {
                ACTION_PAID -> if (ob != null) finance.markPaid(ob, null, "nut_bam")
                ACTION_TXN_YES -> {
                    val txn = db.finance().txn(txnId)
                    if (ob != null && txn != null) {
                        finance.markPaid(ob, txn.amount, "ngan_hang")
                        db.finance().setTxnStatus(txnId, "khop", ob.id)
                    }
                }
                ACTION_TXN_NO -> db.finance().setTxnStatus(txnId, "bo_qua", null)
            }
            NotificationManagerCompat.from(ctx).cancel(intent.getIntExtra("notif", 0))
            pending.finish()
        }
    }

    companion object {
        const val ACTION_PAID = "vn.phamvu.trolyai.PAID"
        const val ACTION_TXN_YES = "vn.phamvu.trolyai.TXN_YES"
        const val ACTION_TXN_NO = "vn.phamvu.trolyai.TXN_NO"
        private fun base(ctx: Context, action: String) = Intent(ctx, FinanceActionReceiver::class.java).setAction(action)
        fun paid(ctx: Context, ob: Obligation) = base(ctx, ACTION_PAID).putExtra("ob", ob.id)
        fun txnYes(ctx: Context, txn: Long, ob: Obligation) = base(ctx, ACTION_TXN_YES).putExtra("txn", txn).putExtra("ob", ob.id)
        fun txnNo(ctx: Context, txn: Long) = base(ctx, ACTION_TXN_NO).putExtra("txn", txn)
    }
}
