package vn.phamvu.trolyai.device

import org.json.JSONObject
import vn.phamvu.trolyai.agent.Action
import vn.phamvu.trolyai.finance.Finance

/**
 * Bắt ngay các câu ra lệnh ngắn hay gặp ("bật đèn pin", "mở wifi", "mở Zalo", "hẹn giờ 5 phút", "tăng âm lượng")
 * mà không cần chờ mô hình AI, nên làm tức thì và chạy cả khi chưa có mô hình. Câu dài hơn hay lạ hơn thì để AI hiểu
 * rồi gọi cùng các lệnh này (xem [MobileActions]).
 */
object QuickCommand {
    private const val LEAD = "^(hay |giup (toi|minh|em|anh|chi|tao|to) |lam on |ban oi |tro ly oi )*"
    private const val TAIL = "( (di|nhe|nha|giup (toi|minh|em|anh|chi|tao|to)|voi|cai|ngay|luon|a|di nhe|nhe ban))*$"

    /** [hasApp] cho biết trong máy có app tên đó không, để "mở …" lạ không bị bắt nhầm. */
    fun match(text: String, hasApp: (String) -> Boolean): Action? {
        val t = Finance.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex(LEAD), "").replace(Regex(TAIL), "").trim()
        if (t.isEmpty() || t.split(' ').size > 7) return null

        // Đèn pin
        Regex("^(bat|mo|tat) (den pin|den flash|flash|den)$").find(t)?.let {
            return act("torch", JSONObject().put("on", it.groupValues[1] != "tat"))
        }
        // Wi‑Fi, Bluetooth, mạng, vị trí…: Android không cho app tự bật, nên mở bảng cài đặt
        Regex("^(bat|mo|tat|ket noi|vao|mo cai dat) (wifi|wi fi|bluetooth|blutooth|du lieu( di dong)?|4g|5g|mang|nfc|gps|dinh vi|vi tri|che do may bay|khong lam phien|diem phat( wifi)?|phat wifi|hotspot)$")
            .find(t)?.let { return act("open_settings", JSONObject().put("page", it.groupValues[2])) }
        Regex("^(mo|vao) cai dat( (.+))?$").find(t)?.let {
            return act("open_settings", JSONObject().put("page", it.groupValues[3]))
        }
        // Âm lượng
        when {
            Regex("^(tang|to|lon) (am luong|tieng|loa)( len)?( them)?$").matches(t) || t == "to len" ->
                return act("volume", JSONObject().put("level", "up"))
            Regex("^(giam|nho|be) (am luong|tieng|loa)( lai| xuong)?( bot)?$").matches(t) || t == "nho lai" ->
                return act("volume", JSONObject().put("level", "down"))
            Regex("^(tat tieng|im lang|tat am thanh)$").matches(t) -> return act("volume", JSONObject().put("level", "mute"))
            Regex("^(bat tieng|mo tieng)$").matches(t) -> return act("volume", JSONObject().put("level", "unmute"))
        }
        Regex("^(chinh |de )?am luong (len )?(\\d{1,3})( phan tram)?$").find(t)?.let {
            return act("volume", JSONObject().put("level", it.groupValues[3]))
        }
        // Hẹn giờ / đếm ngược
        Regex("^(hen gio|dem nguoc|bam gio|dat hen gio)( (trong|sau))? (\\d+) (giay|phut|tieng|gio)( (\\d+) (phut|giay))?$").find(t)?.let {
            val n = it.groupValues[4].toInt()
            var sec = when (it.groupValues[5]) { "giay" -> n; "phut" -> n * 60; else -> n * 3600 }
            it.groupValues[7].toIntOrNull()?.let { m -> sec += if (it.groupValues[8] == "giay") m else m * 60 }
            return act("timer", JSONObject().put("seconds", sec))
        }
        // Máy ảnh
        if (Regex("^(mo|bat) (camera|may anh)$|^(quay phim|quay video)$").matches(t)) {
            return act("camera", JSONObject().put("video", t.startsWith("quay")))
        }
        // Mở app theo tên
        Regex("^(mo|bat|vao|chay) (app |ung dung )?(.+)$").find(t)?.let {
            val name = it.groupValues[3].trim()
            if (name.split(' ').size <= 4 && hasApp(name)) return act("open_app", JSONObject().put("name", name))
        }
        return null
    }

    private fun act(type: String, args: JSONObject) = Action(type, args)
}
