package vn.phamvu.trolyai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.finance.FinanceAlarms
import vn.phamvu.trolyai.learn.LearnWorker
import vn.phamvu.trolyai.llm.MediaPipeEngine

class App : Application() {
    val db by lazy { AppDb.get(this) }
    /** Mô hình AI dùng chung cho chat, học ban đêm và chế độ phỏng vấn; tránh nạp hai lần tốn RAM. */
    val llm by lazy { MediaPipeEngine(this) }

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
    }

    companion object {
        const val CHANNEL_REMINDER = "reminder"
        const val CHANNEL_QUIET = "quiet"
    }
}
