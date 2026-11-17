package vn.phamvu.trolyai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.dates.DailyBrief
import vn.phamvu.trolyai.dates.Dates
import vn.phamvu.trolyai.finance.FinanceAlarms
import vn.phamvu.trolyai.learn.LearnWorker
import vn.phamvu.trolyai.llm.OfflineLlm

class App : Application() {
    val db by lazy { AppDb.get(this) }
    /** Mô hình AI dùng chung cho chat, học ban đêm và chế độ phỏng vấn; tránh nạp hai lần tốn RAM. */
    val llm by lazy { OfflineLlm(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Nhắc nhở", NotificationManager.IMPORTANCE_HIGH)
        )
        // Thông báo bắt buộc khi app dùng micro ở nền: mức thấp nhất, không kêu, không rung, không hiện trên thanh trạng thái
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Chế độ ẩn", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
        FinanceAlarms.scheduleDaily(this)
        LearnWorker.schedule(this)
        // Đặt bản tin sáng và tìm lại ngày giỗ / sinh nhật bạn đã kể từ trước (chạy nền, không làm chậm lúc mở app)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Dates(db).scanOld()
                DailyBrief.scheduleNext(this@App, db)
            }
        }
    }

    companion object {
        const val CHANNEL_REMINDER = "reminder"
        const val CHANNEL_QUIET = "quiet"
    }
}
