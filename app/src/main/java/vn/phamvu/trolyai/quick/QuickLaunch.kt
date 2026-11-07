package vn.phamvu.trolyai.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import vn.phamvu.trolyai.MainActivity
import vn.phamvu.trolyai.R

private fun openListening(ctx: Context): Intent =
    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private fun pending(ctx: Context): PendingIntent =
    PendingIntent.getActivity(ctx, 1, openListening(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Nút "Trợ lý AI" trong thanh kéo xuống (Quick Settings). Chạm là mở và nghe ngay. */
class TalkTile : TileService() {
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending(this))
        else @Suppress("DEPRECATION") startActivityAndCollapse(openListening(this))
    }
}

/** Widget ngoài màn hình chính: một nút mic lớn. */
class TalkWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_talk).apply {
            setOnClickPendingIntent(R.id.widget_talk, pending(ctx))
        }
        ids.forEach { mgr.updateAppWidget(it, views) }
    }
}
