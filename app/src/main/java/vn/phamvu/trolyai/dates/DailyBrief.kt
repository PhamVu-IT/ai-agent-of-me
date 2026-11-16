package vn.phamvu.trolyai.dates

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
import vn.phamvu.trolyai.MainActivity
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.Setting
import vn.phamvu.trolyai.finance.Finance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Bản tin buổi sáng: mỗi sáng (mặc định 7 giờ) trợ lý tự gửi một thông báo gồm ngày âm hôm nay,
 * giỗ / sinh nhật / kỷ niệm sắp tới, khoản chi sắp đến hạn và lời nhắc trong ngày. Không có gì thì im lặng.
 */
object DailyBrief {
    private const val REQUEST = 9100
    const val NOTIF_ID = 9100

    /**
     * Đặt lần chạy kế tiếp. Gọi khi mở app, sau khi khởi động lại máy và sau mỗi lần chạy.
     * Máy tắt đúng giờ bản tin thì bật lên trước 12 giờ trưa vẫn gửi bù.
     */
    suspend fun scheduleNext(ctx: Context, db: AppDb) {
        val hour = db.settings().get(Dates.BRIEF_HOUR)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val sentToday = db.settings().get(Dates.BRIEF_DAY) == LocalDate.now().toEpochDay().toString()
        val now = LocalDateTime.now()
        val todayAt = LocalDate.now().atTime(LocalTime.of(hour, 0))
        val at = when {
            now.isBefore(todayAt) -> todayAt
            !sentToday && now.hour < 12 -> now.plusMinutes(1)
            else -> todayAt.plusDays(1)
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(ctx, REQUEST, Intent(ctx, DailyBriefReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi) // có thể trễ vài phút
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    /** Nội dung bản tin; null nếu hôm nay không có gì đáng nhắc. */
    suspend fun build(db: AppDb, today: LocalDate = LocalDate.now()): String? {
        val lines = mutableListOf<String>()
        val dates = Dates(db)
        dates.toAnnounce(today).forEach { u ->
            val icon = when (u.d.kind) { "gio" -> "🕯️"; "sinh_nhat" -> "🎂"; "ky_niem" -> "💞"; else -> "📅" }
            lines += "$icon ${dates.line(u)}"
        }
        val lunar = LunarCalendar.toLunar(today)
        if (lunar.day == 1 || lunar.day == 15) lines += "🙏 Hôm nay ${if (lunar.day == 1) "mùng 1" else "rằm"} tháng ${lunar.month} âm lịch."

        Finance(db).statusThisMonth().filter { it.payment == null && it.daysLeft in -3..2 }.forEach { s ->
            val w = when {
                s.daysLeft < 0 -> "quá hạn ${-s.daysLeft} ngày"
                s.daysLeft == 0 -> "hạn hôm nay"
                else -> "còn ${s.daysLeft} ngày"
            }
            lines += "💸 ${s.ob.name} ${Finance.money(s.ob.amount)}: $w"
        }

        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.reminders().upcoming(System.currentTimeMillis()).filter { it.triggerAt < endOfDay }
            .sortedBy { it.triggerAt }.forEach { r ->
                val t = java.time.Instant.ofEpochMilli(r.triggerAt).atZone(ZoneId.systemDefault()).toLocalTime()
                lines += "⏰ %02d:%02d %s".format(t.hour, t.minute, r.text)
            }

        if (lines.isEmpty()) return null
        val head = "${Anniversary.weekday(today)} ${today.dayOfMonth}/${today.monthValue} (${Anniversary.lunarToday(today)})"
        return head + "\n" + lines.joinToString("\n")
    }

    fun notify(ctx: Context, text: String) {
        val open = PendingIntent.getActivity(ctx, NOTIF_ID, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, App.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("☀️ Chào buổi sáng")
            .setContentText(text.lines().drop(1).firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }
}

class DailyBriefReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val db = (ctx.applicationContext as App).db
            try {
                val today = LocalDate.now().toEpochDay().toString()
                if (db.settings().get(Dates.BRIEF_DAY) != today) {
                    Dates(db).scanOld() // bắt kịp những ngày bạn kể hôm qua mà chưa có trong sổ
                    DailyBrief.build(db)?.let { DailyBrief.notify(ctx, it) }
                    db.settings().put(Setting(Dates.BRIEF_DAY, today))
                }
            } finally {
                DailyBrief.scheduleNext(ctx, db)
                pending.finish()
            }
        }
    }
}
