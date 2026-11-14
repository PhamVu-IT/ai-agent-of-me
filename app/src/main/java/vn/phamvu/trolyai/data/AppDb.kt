package vn.phamvu.trolyai.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import vn.phamvu.trolyai.security.DbKey

/** Một điều trợ lý ghi nhớ về bạn, ví dụ "Tôi dị ứng hải sản". */
@Entity
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** "fact" (điều về bạn), "rule" (lời bạn sửa, luôn tuân theo), "habit" (thói quen trợ lý tự nhận ra) */
    @ColumnInfo(defaultValue = "fact") val kind: String = "fact",
)

@Entity
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val done: Boolean = false,
)

@Entity
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUser: Boolean,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 1 = 👍, -1 = 👎, 0 = chưa chấm */
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
)

/** Cài đặt và hồ sơ dạng khoá–giá trị: tên bạn, tên trợ lý, cách xưng hô, bản hồ sơ tóm tắt… */
@Entity
data class Setting(@PrimaryKey val key: String, val value: String)

/** Khoản chi cố định hằng tháng, ví dụ tiền nhà ngày 5. */
@Entity
data class Obligation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dayOfMonth: Int,
    val amount: Long,
    /** Từ khoá để nhận ra khoản này trong nội dung chuyển khoản, cách nhau bởi dấu phẩy. */
    val keywords: String = "",
    val active: Boolean = true,
)

/** Một lần đã trả một khoản. month dạng "2026-11". */
@Entity
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val obligationId: Long,
    val month: String,
    val amount: Long,
    val paidAt: Long = System.currentTimeMillis(),
    /** "tu_bao" (bạn nói/gõ), "nut_bam" (bấm trên thông báo), "ngan_hang" (từ thông báo số dư) */
    val source: String,
)

/** Giao dịch tiền ra đọc được từ thông báo ngân hàng. */
@Entity
data class BankTxn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val app: String,
    val text: String,
    val amount: Long,
    val at: Long = System.currentTimeMillis(),
    /** "cho" (chờ xác nhận), "khop" (đã gán vào một khoản), "bo_qua" */
    val status: String = "cho",
    val obligationId: Long? = null,
)

/** Dấu vân giọng của chủ máy. Chỉ có một dòng (id = 1). */
@Entity
data class VoiceProfile(
    @PrimaryKey val id: Int = 1,
    val embedding: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Hồ sơ dùng cho chế độ phỏng vấn: vị trí ứng tuyển, kinh nghiệm, điểm mạnh. Chỉ có một dòng (id = 1). */
@Entity
data class InterviewProfile(
    @PrimaryKey val id: Int = 1,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface MemoryDao {
    @Insert suspend fun insert(m: Memory): Long
    @Query("SELECT * FROM Memory ORDER BY createdAt DESC") suspend fun all(): List<Memory>
    @Query("SELECT * FROM Memory WHERE kind = :kind ORDER BY createdAt") suspend fun ofKind(kind: String): List<Memory>
    @Query("DELETE FROM Memory WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM Memory WHERE content LIKE '%' || :keyword || '%'") suspend fun deleteMatching(keyword: String): Int
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: Reminder): Long
    @Query("SELECT * FROM Reminder WHERE id = :id") suspend fun get(id: Long): Reminder?
    @Query("SELECT * FROM Reminder WHERE done = 0 AND triggerAt > :now") suspend fun upcoming(now: Long): List<Reminder>
    @Query("UPDATE Reminder SET done = 1 WHERE id = :id") suspend fun markDone(id: Long)
}

@Dao
interface ChatDao {
    @Insert suspend fun insert(m: ChatMessage): Long
    @Query("SELECT * FROM ChatMessage ORDER BY createdAt DESC LIMIT :n") suspend fun recent(n: Int): List<ChatMessage>
    @Query("SELECT * FROM ChatMessage WHERE createdAt >= :since ORDER BY createdAt") suspend fun since(since: Long): List<ChatMessage>
    @Query("UPDATE ChatMessage SET rating = :rating WHERE id = :id") suspend fun rate(id: Long, rating: Int)
}

@Dao
interface FinanceDao {
    @Insert suspend fun insertObligation(o: Obligation): Long
    @Query("SELECT * FROM Obligation WHERE active = 1 ORDER BY dayOfMonth") suspend fun obligations(): List<Obligation>
    @Query("SELECT * FROM Obligation WHERE id = :id") suspend fun obligation(id: Long): Obligation?

    @Insert suspend fun insertPayment(p: Payment): Long
    @Query("SELECT * FROM Payment WHERE month = :month") suspend fun paymentsIn(month: String): List<Payment>

    @Insert suspend fun insertTxn(t: BankTxn): Long
    @Query("SELECT * FROM BankTxn WHERE id = :id") suspend fun txn(id: Long): BankTxn?
    @Query("UPDATE BankTxn SET status = :status, obligationId = :obligationId WHERE id = :id")
    suspend fun setTxnStatus(id: Long, status: String, obligationId: Long?)
    @Query("SELECT * FROM BankTxn WHERE at >= :since ORDER BY at DESC") suspend fun txnsSince(since: Long): List<BankTxn>
}

@Dao
interface SettingDao {
    @Upsert suspend fun put(s: Setting)
    @Query("SELECT value FROM Setting WHERE `key` = :key") suspend fun get(key: String): String?
}

@Dao
interface VoiceDao {
    @Upsert suspend fun save(v: VoiceProfile)
    @Query("SELECT * FROM VoiceProfile WHERE id = 1") suspend fun get(): VoiceProfile?
}

@Dao
interface InterviewDao {
    @Upsert suspend fun save(p: InterviewProfile)
    @Query("SELECT * FROM InterviewProfile WHERE id = 1") suspend fun get(): InterviewProfile?
}

@Database(
    entities = [Memory::class, Reminder::class, ChatMessage::class, Obligation::class,
        Payment::class, BankTxn::class, VoiceProfile::class, InterviewProfile::class, Setting::class],
    version = 3,
)
abstract class AppDb : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun reminders(): ReminderDao
    abstract fun chats(): ChatDao
    abstract fun finance(): FinanceDao
    abstract fun voice(): VoiceDao
    abstract fun settings(): SettingDao
    abstract fun interview(): InterviewDao

    companion object {
        /** Bản 2 thêm bảng hồ sơ phỏng vấn; giữ nguyên dữ liệu cũ. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `InterviewProfile` (`id` INTEGER NOT NULL, " +
                    "`text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /** Bản 3: trí nhớ có loại (fact/rule/habit), chấm 👍/👎, bảng cài đặt và hồ sơ. Giữ nguyên dữ liệu cũ. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Memory` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'fact'")
                db.execSQL("ALTER TABLE `ChatMessage` ADD COLUMN `rating` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `Setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: run {
                System.loadLibrary("sqlcipher")
                // Toàn bộ file dữ liệu được mã hoá AES-256 bằng SQLCipher
                Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "trolyai-secure.db")
                    .openHelperFactory(SupportOpenHelperFactory(DbKey.passphrase(ctx)))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
            }.also { inst = it }
        }
    }
}
