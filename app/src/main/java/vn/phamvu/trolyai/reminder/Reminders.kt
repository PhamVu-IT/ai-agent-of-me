package vn.phamvu.trolyai.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.R
import vn.phamvu.trolyai.finance.FinanceAlarms

object ReminderScheduler {
    fun schedule(ctx: Context, id: Long, text: String, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, id.toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // có thể trễ vài phút
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** Hiện thông báo khi đến giờ, kể cả khi app đã tắt. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        val n = NotificationCompat.Builder(ctx, App.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(intent.getStringExtra("text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id.toInt(), n) } catch (_: SecurityException) {}
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            (ctx.applicationContext as App).db.reminders().markDone(id)
            pending.finish()
        }
    }
}

/** Android xoá mọi báo thức khi khởi động lại máy, nên đặt lại từ cơ sở dữ liệu. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FinanceAlarms.scheduleDaily(ctx)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val db = (ctx.applicationContext as App).db
            db.reminders().upcoming(System.currentTimeMillis()).forEach {
                ReminderScheduler.schedule(ctx, it.id, it.text, it.triggerAt)
            }
            pending.finish()
        }
    }
}
