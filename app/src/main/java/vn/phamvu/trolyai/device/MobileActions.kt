package vn.phamvu.trolyai.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject
import vn.phamvu.trolyai.finance.Finance
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Điều khiển điện thoại bằng lời, như "Mobile Actions" của Google AI Edge Gallery: bật / tắt đèn pin, mở Wi‑Fi,
 * Bluetooth và các trang cài đặt, mở app theo tên, hẹn giờ, chỉnh âm lượng, mở máy ảnh, thêm lịch, thêm danh bạ,
 * xem bản đồ. Việc nào Android không cho app tự làm (bật Wi‑Fi, độ sáng…) thì mở đúng bảng cài đặt để bạn bấm.
 */
class MobileActions(private val ctx: Context) {

    /** Các loại lệnh do lớp này làm. */
    val types = setOf("torch", "open_settings", "open_app", "timer", "volume", "camera", "calendar", "contact", "map")

    fun run(type: String, a: JSONObject): String = when (type) {
        "torch" -> torch(a.optBoolean("on", true))
        "open_settings" -> openSettings(a.optString("page"))
        "open_app" -> openApp(a.optString("name"))
        "timer" -> timer(a.optInt("seconds", a.optInt("minutes", 0) * 60), a.optString("label"))
        "volume" -> volume(a.optString("level", "up"), a.optString("stream", "media"))
        "camera" -> launch(Intent(if (a.optBoolean("video")) MediaStore.INTENT_ACTION_VIDEO_CAMERA
            else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "📷 Đã mở máy ảnh.")
        "calendar" -> calendar(a.optString("title"), a.optString("start"), a.optString("end"), a.optString("location"))
        "contact" -> launch(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, a.optString("name"))
            .putExtra(ContactsContract.Intents.Insert.PHONE, a.optString("phone"))
            .putExtra(ContactsContract.Intents.Insert.EMAIL, a.optString("email")),
            "👤 Đã mở danh bạ, bạn xem lại rồi bấm Lưu nhé.")
        "map" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.optString("query")))),
            "🗺️ Đã mở bản đồ.")
        else -> ""
    }

    /** Đèn pin: dùng đèn flash của camera sau, không cần quyền camera. */
    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Máy này không có đèn pin."
        cm.setTorchMode(id, on)
        return if (on) "🔦 Đã bật đèn pin." else "🔦 Đã tắt đèn pin."
    }

    /**
     * Mở một trang cài đặt. Từ Android 10 app không được tự bật / tắt Wi‑Fi, nên mở bảng nhỏ nổi lên
     * (Settings Panel) để bạn bấm một cái là xong, như Gallery.
     */
    private fun openSettings(page: String): String {
        val p = Finance.normalize(page)
        val panel = Build.VERSION.SDK_INT >= 29
        val (action, label) = when {
            "wifi" in p || "wi fi" in p -> (if (panel) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) to "Wi‑Fi"
            "bluetooth" in p -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "du lieu" in p || "mang" in p || "internet" in p || "4g" in p || "5g" in p ->
                (if (panel) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS) to "mạng"
            "am luong" in p || "volume" in p || "am thanh" in p ->
                (if (panel) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS) to "âm lượng"
            "nfc" in p -> (if (panel) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS) to "NFC"
            "vi tri" in p || "gps" in p || "dinh vi" in p -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "vị trí"
            "do sang" in p || "man hinh" in p || "brightness" in p -> Settings.ACTION_DISPLAY_SETTINGS to "màn hình"
            "pin" in p || "battery" in p -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "pin"
            "may bay" in p || "airplane" in p -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "chế độ máy bay"
            "khong lam phien" in p || "dnd" in p -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS to "không làm phiền"
            "phat wifi" in p || "diem phat" in p || "hotspot" in p -> Settings.ACTION_WIRELESS_SETTINGS to "điểm phát sóng"
            "ngon ngu" in p || "language" in p -> Settings.ACTION_LOCALE_SETTINGS to "ngôn ngữ"
            "ngay gio" in p || "date" in p -> Settings.ACTION_DATE_SETTINGS to "ngày giờ"
            "ung dung" in p || "app" in p -> Settings.ACTION_APPLICATION_SETTINGS to "ứng dụng"
            "bo nho" in p || "storage" in p -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "bộ nhớ"
            else -> Settings.ACTION_SETTINGS to "Cài đặt"
        }
        return launch(Intent(action), "⚙️ Đã mở $label.")
    }

    /** Mở app theo tên gọi, ví dụ "Zalo", "youtube", "máy tính". Tìm trong các app có biểu tượng ngoài màn hình chính. */
    fun openApp(name: String): String {
        if (name.isBlank()) return "Bạn muốn mở app nào?"
        val pm = ctx.packageManager
        val pkg = findApp(name) ?: return "Mình không tìm thấy app \"$name\" trong máy."
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "Không mở được \"$name\"."
        return launch(i, "📱 Đã mở ${appLabel(pm, pkg) ?: name}.")
    }

    /** Tên gói của app gọi là [name], null nếu không có. Khớp đúng tên trước, rồi tên hay gọi, rồi khớp một phần. */
    fun findApp(name: String): String? {
        val want = Finance.normalize(name).replace(Regex("[^a-z0-9 ]"), "").trim()
        if (want.isEmpty()) return null
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to Finance.normalize(it.loadLabel(pm).toString()) }
        val aliases = ALIASES[want] ?: emptyList()
        return (apps.firstOrNull { it.second == want }
            ?: apps.firstOrNull { (_, label) -> aliases.any { label == it } }
            ?: apps.firstOrNull { (pkg, _) -> aliases.any { pkg.contains(it) } }
            ?: apps.firstOrNull { it.second.startsWith(want) }
            ?: apps.firstOrNull { want.length >= 3 && it.second.contains(want) })?.first
    }

    private fun appLabel(pm: PackageManager, pkg: String): String? =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()

    private fun timer(seconds: Int, label: String): String {
        if (seconds <= 0) return "Bạn muốn hẹn giờ bao lâu?"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val err = launch(i, "")
        if (err.isNotEmpty()) return err
        val m = seconds / 60
        val s = seconds % 60
        return "⏱️ Đã hẹn giờ " + listOfNotNull(m.takeIf { it > 0 }?.let { "$it phút" }, s.takeIf { it > 0 }?.let { "$it giây" })
            .joinToString(" ") + "."
    }

    /** [level]: "up", "down", "max", "mute", "unmute" hoặc số 0–100. [stream]: "media", "ring", "alarm". */
    private fun volume(level: String, stream: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = when (Finance.normalize(stream)) {
            "ring", "chuong", "nhac chuong" -> AudioManager.STREAM_RING
            "alarm", "bao thuc" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        val flags = AudioManager.FLAG_SHOW_UI
        try {
            when (level.lowercase()) {
                "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, flags) }
                "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, flags) }
                "max" -> am.setStreamVolume(s, max, flags)
                "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, flags)
                "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, flags)
                else -> level.toIntOrNull()?.let { am.setStreamVolume(s, (it.coerceIn(0, 100) * max + 50) / 100, flags) }
                    ?: return "Mình chưa hiểu mức âm lượng \"$level\"."
            }
        } catch (e: SecurityException) {
            // Chỉnh chuông về 0 khi đang bật "Không làm phiền" cần quyền riêng: mở bảng âm lượng cho bạn tự chỉnh
            return openSettings("am luong")
        }
        val now = am.getStreamVolume(s) * 100 / max.coerceAtLeast(1)
        return "🔊 Âm lượng giờ là $now%."
    }

    /** Mở lịch với sự kiện điền sẵn; [start] / [end] dạng "yyyy-MM-ddTHH:mm". */
    private fun calendar(title: String, start: String, end: String, location: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val begin = runCatching { fmt.parse(start)!!.time }.getOrNull()
        val finish = runCatching { fmt.parse(end)!!.time }.getOrNull() ?: begin?.plus(60 * 60 * 1000L)
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        begin?.let { i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        finish?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        if (location.isNotBlank()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        return launch(i, "📅 Đã mở lịch, bạn xem lại rồi bấm Lưu nhé.")
    }

    private fun launch(i: Intent, ok: String): String = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ok
    } catch (e: android.content.ActivityNotFoundException) {
        "Máy không có app nào làm được việc này."
    }

    companion object {
        /** Tên hay gọi → tên app / tên gói thường gặp ở Việt Nam. */
        private val ALIASES = mapOf(
            "may tinh" to listOf("calculator", "may tinh", "calculator"),
            "may anh" to listOf("camera", "may anh"),
            "danh ba" to listOf("contacts", "danh ba"),
            "tin nhan" to listOf("messages", "tin nhan", "messaging"),
            "dien thoai" to listOf("phone", "dien thoai", "dialer"),
            "lich" to listOf("calendar", "lich"),
            "dong ho" to listOf("clock", "dong ho", "deskclock"),
            "anh" to listOf("photos", "gallery", "thu vien", "bo suu tap"),
            "thu vien anh" to listOf("photos", "gallery", "thu vien", "bo suu tap"),
            "cai dat" to listOf("settings", "cai dat"),
            "ban do" to listOf("maps", "ban do"),
            "cua hang" to listOf("play store", "cua hang play", "vending"),
            "ch play" to listOf("play store", "cua hang play", "vending"),
            "youtube" to listOf("youtube"),
            "zalo" to listOf("zalo"),
            "facebook" to listOf("facebook", "katana"),
            "messenger" to listOf("messenger", "orca"),
        )
    }
}
