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
import vn.phamvu.trolyai.learn.Profile

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).db
    private val assistant = Assistant((app as App).llm, db)
    val executor = ActionExecutor(app, db)

    val messages = mutableStateListOf<ChatMessage>()
    val thinking = mutableStateOf(false)

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
