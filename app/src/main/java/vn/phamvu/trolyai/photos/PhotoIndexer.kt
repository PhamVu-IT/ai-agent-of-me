package vn.phamvu.trolyai.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.llm.OfflineLlm
import vn.phamvu.trolyai.media.ChatMedia
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Cho AI xem lần lượt các ảnh chưa có mô tả, mới nhất trước. Dùng chung cho nút trên màn hình và việc chạy lúc sạc. */
object PhotoIndexer {
    private const val PROMPT = "Mô tả ảnh này để sau này tìm lại bằng chữ. Viết 1–2 câu tiếng Việt: có ai hay con vật gì, " +
        "đồ vật, nơi chốn (trong nhà, bãi biển, quán ăn…), hoạt động, màu nổi bật, chữ viết trong ảnh nếu có. " +
        "Sau đó viết dòng \"Từ khoá:\" với 8–12 từ khoá cả tiếng Việt lẫn tiếng Anh, cách nhau bằng dấu phẩy. Không viết gì thêm."

    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Đọc tối đa [max] ảnh chưa có mô tả. [onProgress] nhận (đã có mô tả, tổng số ảnh). Lưu chỉ mục sau mỗi 5 ảnh
     * để dừng giữa chừng không mất công. Trả về số ảnh vừa đọc.
     */
    suspend fun run(
        ctx: Context,
        index: PhotoIndex,
        llm: OfflineLlm,
        max: Int = Int.MAX_VALUE,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        if (!llm.canListen()) throw IllegalStateException(
            "Mô hình AI đang dùng không xem được ảnh. Chọn Gemma 3n / Gemma 4 dạng .litertlm ở 🧠 Mô hình.")
        val all = index.scan()
        val todo = index.pending(all).take(max)
        var done = 0
        var captioned = all.size - index.pending(all).size
        onProgress(captioned, all.size)
        try {
            for ((id, taken) in todo) {
                coroutineContext.ensureActive()
                val e = PhotoEntry(id, taken, "")
                val caption = runCatching {
                    val bmp = ChatMedia.loadImage(ctx, e.uri)
                    val jpeg = withContext(Dispatchers.Default) { ChatMedia.toJpeg(bmp) }
                    llm.look(jpeg, PROMPT).trim()
                }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    "(không đọc được ảnh)" // ảnh hỏng: đánh dấu để không thử lại mãi
                }
                index.put(e.copy(caption = caption.take(600)))
                done++
                captioned++
                onProgress(captioned, all.size)
                if (done % 5 == 0) index.save()
            }
        } finally {
            index.save()
        }
        return done
    }
}

/** Đọc ảnh mới lúc máy đang sạc và không dùng, mỗi lần tối đa 200 ảnh, để chỉ mục luôn theo kịp thư viện. */
class PhotoIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!PhotoIndexer.hasPermission(applicationContext)) return Result.success()
        val app = applicationContext as App
        if (!app.llm.hasModel()) return Result.success()
        return try {
            PhotoIndexer.run(applicationContext, PhotoIndex(applicationContext), app.llm, max = 200)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.success() // mô hình không xem được ảnh: lần sau thử lại, không báo lỗi ở nền
        }
    }

    companion object {
        private const val WORK = "photo-index"

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<PhotoIndexWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
