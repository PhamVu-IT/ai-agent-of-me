package vn.phamvu.trolyai

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.agent.ActionExecutor
import vn.phamvu.trolyai.agent.Assistant
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.interview.EnglishAsr
import vn.phamvu.trolyai.voice.VietnameseAsr
import vn.phamvu.trolyai.voice.VoiceId
import vn.phamvu.trolyai.learn.Profile

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).db
    private val llm = (app as App).llm
    private val assistant = Assistant(llm, db)
    val executor = ActionExecutor(app, db)

    val messages = mutableStateListOf<ChatMessage>()
    val thinking = mutableStateOf(false)
    /** Phần câu trả lời AI đang viết dở, hiện dần trên màn hình; rỗng khi chưa có chữ nào. */
    val partial = mutableStateOf("")
    /** Dòng trạng thái dưới thanh tiến độ, ví dụ "Đang nạp mô hình…" hay "Đang suy nghĩ…". */
    val status = mutableStateOf("")

    /**
     * Câu trả lời mới cần đọc to, null khi không có gì để đọc. Chỉ đặt khi trợ lý vừa trả lời trong lúc
     * đang dùng app, không đặt cho tin cũ nạp lại từ máy, nên back rồi mở lại app không đọc lại câu cũ.
     */
    val toSpeak = mutableStateOf<ChatMessage?>(null)

    /** Màn hình gọi sau khi đã đọc xong một câu, để xoay máy hay quay lại màn chat không đọc lại. */
    fun markSpoken(m: ChatMessage) { if (toSpeak.value?.id == m.id) toSpeak.value = null }

    /** Đã có file mô hình AI trong máy chưa; chưa có thì màn hình chat hiện nút chọn file. */
    val modelReady = mutableStateOf(llm.hasModel())
    /** Tiến độ chép mô hình 0..1, null khi không chép. */
    val importProgress = mutableStateOf<Float?>(null)
    /** Đang nạp mô hình vào RAM sau khi chép xong. */
    val importLoading = mutableStateOf(false)
    val importNote = mutableStateOf("")
    private var importJob: Job? = null

    fun refreshModel() { modelReady.value = llm.hasModel() }

    /** Chép file .task người dùng vừa chọn vào app rồi nạp luôn. Chạy tiếp khi xoay màn hình. */
    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        importNote.value = ""
        importProgress.value = 0f
        importJob = viewModelScope.launch {
            importNote.value = try {
                llm.importModel(uri) { p ->
                    importProgress.value = p
                    if (p >= 1f) importLoading.value = true
                }
                "✅ Đã cài mô hình mới. Giờ bạn nhắn cho mình được rồi."
            } catch (e: CancellationException) {
                "Đã huỷ chép mô hình."
            } catch (e: Throwable) {
                if (llm.hasModel() && importLoading.value)
                    "Đã chép file nhưng không nạp được mô hình: ${e.message}. Có thể file không đúng định dạng MediaPipe hoặc máy thiếu RAM; hãy chọn file khác."
                else "Lỗi khi chép mô hình: ${e.message}"
            } finally {
                importProgress.value = null
                importLoading.value = false
                refreshModel()
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    // Mô hình nhận giọng và nghe tiếng Anh. Chép trong ViewModel để không bị mất khi đổi màn hình,
    // khoá app hay xoay máy giữa chừng.
    private val voiceFiles = VoiceId(app, db)
    private val asrFiles = EnglishAsr(app)
    val speakerReady = mutableStateOf(voiceFiles.modelReady())
    val speakerBusy = mutableStateOf(false)
    val speakerNote = mutableStateOf("")
    val asrMissing = mutableStateOf(asrFiles.missing())
    val asrBusy = mutableStateOf(false)
    val asrNote = mutableStateOf("")
    // Mô hình nghe tiếng Việt offline (nút 🎤), chọn giống mô hình tiếng Anh
    private val viAsrFiles = VietnameseAsr(app)
    val viAsrMissing = mutableStateOf(viAsrFiles.missing())
    val viAsrBusy = mutableStateOf(false)
    val viAsrNote = mutableStateOf("")

    fun speakerSizeMb() = voiceFiles.modelSizeMb()

    fun refreshSideModels() {
        speakerReady.value = voiceFiles.modelReady()
        asrMissing.value = asrFiles.missing()
        viAsrMissing.value = viAsrFiles.missing()
    }

    fun importSpeaker(uri: Uri) {
        if (speakerBusy.value) return
        speakerBusy.value = true
        speakerNote.value = "Đang chép…"
        viewModelScope.launch {
            speakerNote.value = try { voiceFiles.importModel(uri); "✅ Đã cài mô hình nhận giọng." }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { "Lỗi: ${e.message ?: e.javaClass.simpleName}" }
            finally { speakerBusy.value = false; refreshSideModels() }
        }
    }

    fun importAsrFiles(uris: List<Uri>) = importAsr { asrFiles.importFiles(uris) }
    fun importAsrFolder(tree: Uri) = importAsr { asrFiles.importFolder(tree) }

    fun importViAsrFiles(uris: List<Uri>) = importViAsr { viAsrFiles.importFiles(uris) }
    fun importViAsrFolder(tree: Uri) = importViAsr { viAsrFiles.importFolder(tree) }

    private fun importViAsr(block: suspend () -> String) {
        if (viAsrBusy.value) return
        viAsrBusy.value = true
        viAsrNote.value = "Đang chép, file lớn có thể mất một hai phút. Để app mở nhé…"
        viewModelScope.launch {
            viAsrNote.value = try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { "Lỗi: ${e.message ?: e.javaClass.simpleName}" }
            finally { viAsrBusy.value = false; refreshSideModels() }
        }
    }

    private fun importAsr(block: suspend () -> String) {
        if (asrBusy.value) return
        asrBusy.value = true
        asrNote.value = "Đang chép, file lớn có thể mất một hai phút. Để app mở nhé…"
        viewModelScope.launch {
            asrNote.value = try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { "Lỗi: ${e.message ?: e.javaClass.simpleName}" }
            finally { asrBusy.value = false; refreshSideModels() }
        }
    }

    init {
        // Nạp sẵn mô hình ngay khi mở app để câu hỏi đầu tiên không phải đợi nạp vài GB vào RAM
        viewModelScope.launch {
            if (!llm.hasModel() || llm.isLoaded()) return@launch
            status.value = "Đang nạp mô hình AI vào bộ nhớ…"
            try { llm.warmUp() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                status.value = ""
                add(ChatMessage(fromUser = false, text = "⚠️ Không nạp được mô hình AI: ${e.message ?: e.javaClass.simpleName}. " +
                    "Câu đơn giản (ngày giờ, chào hỏi, khoản chi, nhắc nhở) mình vẫn trả lời được. Các câu khác cần chọn lại mô hình ở \"🧠 Mô hình\"."))
                return@launch
            }
            if (!thinking.value) status.value = ""
        }
        viewModelScope.launch {
            messages.addAll(db.chats().recent(50).reversed())
            // Lần đầu: trợ lý chủ động hỏi để làm quen
            if (messages.isEmpty()) add(ChatMessage(fromUser = false, text =
                "Chào bạn! Mình là người bạn AI sống ngay trong điện thoại này, mọi chuyện bạn kể chỉ nằm trên máy. " +
                "Làm quen chút nhé: mình nên gọi bạn là gì, xưng hô thế nào cho thoải mái " +
                "(mình - bạn, em - anh, tớ - cậu…), và bạn muốn đặt tên cho mình là gì? " +
                "Sau đó kể mình nghe các khoản chi cố định hằng tháng (tiền nhà, trả lãi, gửi gia đình, tiết kiệm…) " +
                "để mình nhắc bạn đúng hạn."))
        }
    }

    fun send(text: String) {
        if (text.isBlank() || thinking.value) return
        viewModelScope.launch {
            add(ChatMessage(fromUser = true, text = text))
            thinking.value = true
            partial.value = ""
            status.value = if (llm.isLoaded() || !llm.hasModel()) "Đang suy nghĩ…" else "Đang nạp mô hình AI, lần đầu hơi lâu…"
            val reply = try {
                val turn = assistant.handle(text) { p ->
                    // Gọi từ luồng của mô hình; Compose state cho phép ghi từ luồng khác
                    partial.value = p
                    status.value = "Đang viết…"
                }
                val result = turn.action?.let { runCatching { executor.run(it) }.getOrElse { e -> "Không thực hiện được: ${e.message}" } }
                listOf(turn.reply, result).filter { !it.isNullOrBlank() }.joinToString("\n")
                    .ifBlank { "Mình chưa nghĩ ra câu trả lời. Bạn nói lại theo cách khác giúp mình nhé." }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Bắt cả lỗi nặng (thiếu RAM, lỗi thư viện) để không bao giờ im lặng
                "⚠️ Lỗi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                thinking.value = false
                partial.value = ""
                status.value = ""
            }
            add(ChatMessage(fromUser = false, text = reply))
        }
    }

    /** 👍 / 👎 một câu trả lời. 👎 thì tin nhắn kế tiếp của bạn được hiểu là lời sửa. */
    fun rate(m: ChatMessage, rating: Int) {
        viewModelScope.launch {
            db.chats().rate(m.id, rating)
            val i = messages.indexOfFirst { it.id == m.id }
            if (i >= 0) messages[i] = m.copy(rating = rating)
            if (rating < 0) {
                Profile(db).put(Profile.PENDING_CORRECTION, m.text)
                add(ChatMessage(fromUser = false, text = "Ôi, mình sai ở đâu vậy? Nói mình nghe để lần sau mình nhớ nhé."))
            }
        }
    }

    private suspend fun add(m: ChatMessage) {
        val id = db.chats().insert(m)
        val saved = m.copy(id = id)
        messages.add(saved)
        if (!saved.fromUser) toSpeak.value = saved
    }
}
