package vn.phamvu.trolyai.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.llm.ModelFormat
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tải mô hình từ Hugging Face ở nền, như DownloadWorker của Google AI Edge Gallery: có thông báo tiến độ,
 * tắt màn hình hay rời app vẫn tải, mất mạng thì tải tiếp từ chỗ đang dở (HTTP Range) chứ không tải lại từ đầu.
 * Tải xong thì kiểm tra dung lượng rồi thay mô hình đang dùng.
 */
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val model = CatalogModel.find(inputData.getString(KEY_NAME) ?: "") ?: return Result.failure(fail("Không rõ mô hình"))
        val app = applicationContext as App
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = app.llm.downloadFile()
        // Phần đã tải dở chỉ dùng tiếp nếu đúng là của mô hình này
        if (prefs.getString(KEY_PARTIAL, "") != model.url) file.delete()
        prefs.edit().putString(KEY_PARTIAL, model.url).apply()

        val need = model.sizeBytes - file.length() + 300L * 1024 * 1024
        if (app.llm.freeSpace() < need) {
            return Result.failure(fail("Không đủ bộ nhớ trống: cần thêm khoảng ${need / 1_000_000} MB."))
        }
        runCatching { setForeground(foreground(model, 0f)) }

        return try {
            download(model, file, prefs.getString(KEY_TOKEN, "") ?: "")
            if (file.length() != model.sizeBytes) {
                file.delete()
                return Result.failure(fail("File tải về không đủ (${file.length()}/${model.sizeBytes} byte). Thử tải lại nhé."))
            }
            setProgress(workDataOf(KEY_DONE to model.sizeBytes, KEY_TOTAL to model.sizeBytes, KEY_STAGE to "install"))
            app.llm.installModel(file, ModelFormat.LITERTLM)
            prefs.edit().putString(KEY_INSTALLED, model.name).remove(KEY_PARTIAL).apply()
            Result.success(workDataOf(KEY_NAME to model.name))
        } catch (e: CancellationException) {
            throw e // bấm Huỷ: giữ phần đã tải để lần sau tải tiếp
        } catch (e: HttpError) {
            if (e.code == 401 || e.code == 403) Result.failure(fail(
                "Hugging Face không cho tải (mã ${e.code}). Mô hình này cần bạn đăng nhập Hugging Face, mở trang mô hình " +
                    "bấm đồng ý điều khoản, rồi dán mã truy cập (Access Token) vào ô bên dưới."))
            else Result.failure(fail("Hugging Face báo lỗi ${e.code}."))
        } catch (e: IOException) {
            // Mất mạng giữa chừng: WorkManager tự thử lại khi có mạng, tải tiếp từ chỗ dở
            if (runAttemptCount < 5) Result.retry() else Result.failure(fail("Mất kết nối: ${e.message}"))
        } catch (e: Throwable) {
            Result.failure(fail(e.message ?: e.javaClass.simpleName))
        }
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private suspend fun download(model: CatalogModel, file: java.io.File, token: String) = withContext(Dispatchers.IO) {
        var url = URL(model.url)
        var conn: HttpURLConnection
        // Hugging Face chuyển hướng sang máy chủ CDN; tự đi theo để giữ được header Range
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "tro-ly-ai")
            if (token.isNotBlank() && url.host.endsWith("huggingface.co")) {
                conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            if (file.length() > 0) conn.setRequestProperty("Range", "bytes=${file.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val loc = conn.getHeaderField("Location") ?: throw HttpError(code)
                url = URL(url, loc)
                conn.disconnect()
                continue
            }
            if (code == 416) { conn.disconnect(); return@withContext } // đã tải đủ rồi
            if (code !in 200..299) { conn.disconnect(); throw HttpError(code) }
            // Máy chủ không hỗ trợ tải tiếp thì tải lại từ đầu
            if (code == 200 && file.length() > 0) file.delete()
            break
        }
        try {
            conn.inputStream.use { ins ->
                FileOutputStream(file, true).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = file.length()
                    var lastReport = 0L
                    var lastNotify = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 500) {
                            lastReport = now
                            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to model.sizeBytes, KEY_STAGE to "download"))
                        }
                        if (now - lastNotify > 3000) {
                            lastNotify = now
                            runCatching { setForeground(foreground(model, done.toFloat() / model.sizeBytes)) }
                        }
                    }
                    out.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(msg: String) = workDataOf(KEY_ERROR to msg)

    private fun foreground(model: CatalogModel, p: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tải mô hình", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Đang tải ${model.name}")
            .setContentText("${(p * 100).toInt()}% · ${model.sizeMb} MB")
            .setProgress(1000, (p * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFY_ID, n)
    }

    companion object {
        const val WORK = "model-download"
        const val PREFS = "model_store"
        const val KEY_NAME = "name"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        /** Mã truy cập Hugging Face (tuỳ chọn), lưu trong bộ nhớ riêng của app. */
        const val KEY_TOKEN = "hf_token"
        /** Tên mô hình trong danh sách đã tải và cài, để hiện "Đang dùng". */
        const val KEY_INSTALLED = "installed"
        private const val KEY_PARTIAL = "partial_url"
        private const val CHANNEL = "download"
        private const val NOTIFY_ID = 4201

        fun start(ctx: Context, model: CatalogModel) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_NAME to model.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(WORK)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }
}
