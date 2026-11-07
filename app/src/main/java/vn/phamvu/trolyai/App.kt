package vn.phamvu.trolyai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.finance.FinanceAlarms

class App : Application() {
    val db by lazy { AppDb.get(this) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Nhắc nhở", NotificationManager.IMPORTANCE_HIGH)
        )
        FinanceAlarms.scheduleDaily(this)
    }

    companion object { const val CHANNEL_REMINDER = "reminder" }
}
