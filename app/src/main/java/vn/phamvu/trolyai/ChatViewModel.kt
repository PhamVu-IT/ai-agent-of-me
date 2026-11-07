package vn.phamvu.trolyai

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.agent.ActionExecutor
import vn.phamvu.trolyai.agent.Assistant
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.llm.MediaPipeEngine

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).db
    private val assistant = Assistant(MediaPipeEngine(app), db)
    val executor = ActionExecutor(app, db)

    val messages = mutableStateListOf<ChatMessage>()
    val thinking = mutableStateOf(false)

    init {
        viewModelScope.launch {
            messages.addAll(db.chats().recent(50).reversed())
            // Lần đầu: trợ lý chủ động hỏi để làm quen
            if (messages.isEmpty()) add(ChatMessage(fromUser = false, text =
                "Chào bạn! Mình là trợ lý chạy ngay trên máy, không gửi dữ liệu ra ngoài. " +
                "Để giúp bạn được nhiều nhất, cho mình hỏi: hằng tháng bạn có những khoản chi cố định nào " +
                "(ví dụ tiền nhà, trả lãi, gửi gia đình, nộp tiết kiệm)? Mỗi khoản vào ngày nào, khoảng bao nhiêu tiền? " +
                "Bạn kể từng khoản một cũng được."))
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

    private suspend fun add(m: ChatMessage) {
        db.chats().insert(m)
        messages.add(m)
    }
}
