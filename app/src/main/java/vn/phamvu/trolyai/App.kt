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
    /** Một mô hình dùng chung cho cả app, tránh nạp hai lần tốn RAM. */
    val llm by lazy { MediaPipeEngine(this) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Nhắc nhở", NotificationManager.IMPORTANCE_HIGH)
        )
        FinanceAlarms.scheduleDaily(this)
        LearnWorker.schedule(this)
    }

    companion object { const val CHANNEL_REMINDER = "reminder" }
}
