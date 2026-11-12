package vn.phamvu.trolyai.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.Memory
import vn.phamvu.trolyai.data.Reminder
import vn.phamvu.trolyai.finance.Finance
import vn.phamvu.trolyai.learn.Profile
import vn.phamvu.trolyai.reminder.ReminderScheduler
import vn.phamvu.trolyai.security.Authenticator
import java.text.SimpleDateFormat
import java.util.Locale

/** Biến quyết định của AI thành việc thật trên điện thoại. Trả về một dòng mô tả kết quả. */
class ActionExecutor(private val ctx: Context, private val db: AppDb) {

    /** Do MainActivity gắn vào. Chưa có thì mặc định từ chối mọi lệnh nhạy cảm. */
    var auth: Authenticator = Authenticator { false }

    /** Các lệnh phải xác nhận vân tay / khuôn mặt trước khi làm. */
    private val sensitive = mapOf(
        "call" to "Cho phép trợ lý gọi điện",
        "sms" to "Cho phép trợ lý soạn tin nhắn",
        "forget" to "Cho phép trợ lý xoá trí nhớ",
    )

    suspend fun run(a: Action): String {
        sensitive[a.type]?.let { reason ->
            if (!auth.confirm(reason)) return "Chưa xác nhận được là bạn nên mình không làm việc này."
        }
        return execute(a)
    }

    private suspend fun execute(a: Action): String = when (a.type) {
        "remember" -> {
            db.memories().insert(Memory(content = a.args.getString("content")))
            "Đã ghi nhớ."
        }
        "learn_rule" -> {
            db.memories().insert(Memory(content = a.args.getString("rule"), kind = "rule"))
            "📝 Đã ghi nhớ bài học."
        }
        "set_profile" -> {
            val p = Profile(db)
            a.args.optString("user_name").takeIf { it.isNotBlank() }?.let { p.put(Profile.USER_NAME, it) }
            a.args.optString("assistant_name").takeIf { it.isNotBlank() }?.let { p.put(Profile.ASSISTANT_NAME, it) }
            a.args.optString("xung_ho").takeIf { it.contains("-") }?.let { p.put(Profile.XUNG_HO, it) }
            ""
        }
        "forget" -> {
            val n = db.memories().deleteMatching(a.args.getString("keyword"))
            if (n > 0) "Đã quên $n điều." else "Mình không tìm thấy điều đó trong trí nhớ."
        }
        "add_obligation" -> {
            val f = Finance(db)
            val amount = a.args.optLong("amount", 0)
            f.addObligation(a.args.getString("name"), a.args.getInt("day"), amount, a.args.optString("keywords"))
            "Đã thêm khoản \"${a.args.getString("name")}\" vào sổ, hạn ngày ${a.args.getInt("day")} hằng tháng."
        }
        "paid" -> {
            val f = Finance(db)
            val ob = f.findByName(a.args.getString("name"))
            if (ob == null) "Mình chưa có khoản \"${a.args.getString("name")}\" trong sổ. Bạn cho mình biết hạn ngày nào để thêm nhé."
            else {
                val amt = a.args.optLong("amount", 0).takeIf { it > 0 }
                f.markPaid(ob, amt, "tu_bao")
                "Đã ghi: ${ob.name} tháng này đã trả ${Finance.money(amt ?: ob.amount)}."
            }
        }
        "remind" -> {
            val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(a.args.getString("at"))!!.time
            val text = a.args.getString("text")
            val id = db.reminders().insert(Reminder(text = text, triggerAt = at))
            ReminderScheduler.schedule(ctx, id, text, at)
            "Đã đặt nhắc nhở."
        }
        "alarm" -> launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.args.getInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.args.optInt("minute", 0))
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.args.optString("label")))
        // Mở màn hình quay số / soạn tin để bạn tự bấm gửi: an toàn hơn gửi tự động
        "call" -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.args.getString("number"))))
        "sms" -> launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.args.getString("number")))
            .putExtra("sms_body", a.args.optString("body")))
        "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.args.getString("url"))))
        else -> ""
    }

    private fun launch(i: Intent): String {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Đã mở."
    }
}
