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
import vn.phamvu.trolyai.voice.VoiceId
import vn.phamvu.trolyai.learn.Profile

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).db
    private val llm = (app as App).llm
    private val assistant = Assistant(llm, db)
    val executor = ActionExecutor(app, db)

    val messages = mutableStateListOf<ChatMessage>()
    val thinking = mutableStateOf(false)

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

    fun speakerSizeMb() = voiceFiles.modelSizeMb()

    fun refreshSideModels() {
        speakerReady.value = voiceFiles.modelReady()
        asrMissing.value = asrFiles.missing()
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
            val reply = try {
                val turn = assistant.handle(text)
                val result = turn.action?.let { runCatching { executor.run(it) }.getOrElse { e -> "Không thực hiện được: ${e.message}" } }
                listOf(turn.reply, result).filter { !it.isNullOrBlank() }.joinToString("\n")
            } catch (e: Exception) {
                "Lỗi: ${e.message}"
            }
            thinking.value = false
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
        messages.add(m.copy(id = id))
    }
}
