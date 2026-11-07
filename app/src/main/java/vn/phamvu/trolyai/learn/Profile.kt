package vn.phamvu.trolyai.learn

import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.data.Setting

/** Những điều làm nên "tính cách" của trợ lý với riêng bạn. Lưu trong bảng Setting (đã mã hoá). */
class Profile(private val db: AppDb) {
    suspend fun get(key: String) = db.settings().get(key)
    suspend fun put(key: String, value: String) = db.settings().put(Setting(key, value))

    suspend fun userName() = get(USER_NAME)
    suspend fun assistantName() = get(ASSISTANT_NAME) ?: "Bạn AI"
    /** Ví dụ "mình - bạn", "em - anh", "tớ - cậu", "tao - mày". Phần đầu là trợ lý tự xưng. */
    suspend fun xungHo() = get(XUNG_HO) ?: "mình - bạn"
    suspend fun summary() = get(SUMMARY)

    companion object {
        const val USER_NAME = "user_name"
        const val ASSISTANT_NAME = "assistant_name"
        const val XUNG_HO = "xung_ho"
        const val SUMMARY = "profile_summary"
        const val SUMMARY_AT = "profile_summary_at"
        const val LAST_LEARN = "last_learn_at"
        const val PENDING_CORRECTION = "pending_correction"
    }
}
