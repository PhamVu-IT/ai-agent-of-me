package vn.phamvu.trolyai

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioTrack
import android.net.Uri
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.agent.ActionExecutor
import vn.phamvu.trolyai.agent.AgentTools
import vn.phamvu.trolyai.agent.Assistant
import vn.phamvu.trolyai.agent.ToolOutput
import vn.phamvu.trolyai.mcp.McpServer
import vn.phamvu.trolyai.skills.Skill
import vn.phamvu.trolyai.agent.Tier
import vn.phamvu.trolyai.data.ChatMessage
import vn.phamvu.trolyai.interview.EnglishAsr
import vn.phamvu.trolyai.voice.VietnameseAsr
import vn.phamvu.trolyai.voice.VoiceId
import vn.phamvu.trolyai.learn.Profile
import vn.phamvu.trolyai.llm.GenSettings
import vn.phamvu.trolyai.llm.GenStats
import vn.phamvu.trolyai.settings.AppSettings
import vn.phamvu.trolyai.settings.SpeechSettings
import vn.phamvu.trolyai.media.ChatMedia
import vn.phamvu.trolyai.media.MessageMedia

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).db
    private val llm = (app as App).llm
    /** Agent Skills và máy chủ MCP (như Gallery), dùng chung cho chat và màn quản lý. */
    val tools = AgentTools(app)
    val executor = ActionExecutor(app, db)
    private val assistant = Assistant(llm, db, tools, hasApp = { executor.device.findApp(it) != null })

    val messages = mutableStateListOf<ChatMessage>()
    val thinking = mutableStateOf(false)
    /** Phần câu trả lời AI đang viết dở, hiện dần trên màn hình; rỗng khi chưa có chữ nào. */
    val partial = mutableStateOf("")
    /** Phần "suy nghĩ" mô hình đang viết (chế độ suy nghĩ của Gemma 4), rỗng nếu không bật. */
    val thought = mutableStateOf("")
    /** Phần suy nghĩ của các câu trả lời trong lần mở app này, theo id tin nhắn, để mở ra xem lại. */
    val thoughts = mutableStateMapOf<Long, String>()

    // ---- Màn ⚙️ Cài đặt ----
    private val appSettings = AppSettings(app)
    val genSettings = mutableStateOf(appSettings.gen())
    val speechSettings = mutableStateOf(appSettings.speech())

    fun setGen(s: GenSettings) {
        genSettings.value = s
        appSettings.saveGen(s)
        viewModelScope.launch {
            val reload = s.backend != llm.settings.backend
            llm.applySettings(s)
            if (reload) backendLabel.value = ""
        }
    }

    fun resetGen() = setGen(AppSettings.DEFAULT_GEN.copy(backend = genSettings.value.backend))

    fun setSpeech(s: SpeechSettings) { speechSettings.value = s; appSettings.saveSpeech(s) }

    /** Dòng trạng thái dưới thanh tiến độ, ví dụ "Đang nạp mô hình…" hay "Đang suy nghĩ…". */
    val status = mutableStateOf("")
    /**
     * Số liệu từng lượt trong lần mở app này (mới nhất ở đầu), cho màn 📊 Thống kê. Không hiện ở màn chat
     * để màn chat gọn; cần xem thì mở màn thống kê.
     */
    val stats = mutableStateListOf<TurnStat>()
    /** Tên trợ lý hiện trên đầu màn hình và trên bong bóng trả lời. */
    val assistantName = mutableStateOf("Trợ lý")
    /** Ví dụ "LiteRT-LM GPU", rỗng khi mô hình chưa nạp. */
    val backendLabel = mutableStateOf("")
    private var job: Job? = null

    // Ảnh và ghi âm đính kèm tin nhắn sắp gửi (như Gallery). Chỉ giữ trong RAM, không lưu ra máy.
    val attachedImages = mutableStateListOf<Bitmap>()
    /** Đoạn ghi âm đính kèm (PCM 16 kHz), null nếu không có. */
    val attachedAudio = mutableStateOf<ByteArray?>(null)
    val recording = mutableStateOf(false)
    val recordMs = mutableLongStateOf(0L)
    val recordLevel = mutableFloatStateOf(0f)
    @Volatile private var stopRecording = false
    private var recordCancelled = false
    /** Ảnh / ghi âm của các tin đã gửi trong lần mở app này, theo id tin nhắn, để hiện trong bong bóng. */
    val media = mutableStateMapOf<Long, MessageMedia>()
    /** Skill / công cụ MCP đã chạy cho từng câu trả lời trong lần mở app này (ảnh QR, bản đồ…). */
    val toolOutputs = mutableStateMapOf<Long, List<ToolOutput>>()
    private var player: AudioTrack? = null
    /** Báo lỗi ngắn khi chọn ảnh hay thu âm hỏng. */
    val mediaNote = mutableStateOf("")

    /** Tăng mỗi lượt; lượt đã bị dừng thì không được ghi đè trạng thái của lượt mới. */
    @Volatile private var turnId = 0

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

    fun refreshModel() { modelReady.value = llm.hasModel(); backendLabel.value = llm.backend }

    /** Ví dụ "model.litertlm, 2584 MB, LiteRT-LM" để biết đang dùng mô hình nào. */
    fun modelLabel(): String = "${llm.modelFile.name}, ${llm.modelSizeMb()} MB, ${llm.format.label}"

    /** Chép file .task hoặc .litertlm người dùng vừa chọn vào app rồi nạp luôn. Chạy tiếp khi xoay màn hình. */
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
                backendLabel.value = llm.backend
                "✅ Đã cài mô hình mới. Giờ bạn nhắn cho mình được rồi."
            } catch (e: CancellationException) {
                "Đã huỷ chép mô hình."
            } catch (e: Throwable) {
                if (llm.hasModel() && importLoading.value)
                    "Đã chép file nhưng không nạp được mô hình: ${e.message}. Có thể file hỏng, không đúng định dạng (.task cho MediaPipe, .litertlm cho LiteRT-LM) hoặc máy thiếu RAM; hãy chọn file khác."
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
            // Cài đặt (GPU / CPU, temperature…) phải có trước khi nạp mô hình
            llm.applySettings(genSettings.value)
            if (!llm.hasModel() || llm.isLoaded()) return@launch
            status.value = "Đang nạp mô hình AI vào bộ nhớ…"
            try { llm.warmUp() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                status.value = ""
                add(ChatMessage(fromUser = false, text = "⚠️ Không nạp được mô hình AI: ${e.message ?: e.javaClass.simpleName}. " +
                    "Câu đơn giản (ngày giờ, chào hỏi, khoản chi, nhắc nhở) mình vẫn trả lời được. Các câu khác cần chọn lại mô hình ở \"🧠 Mô hình\"."))
                return@launch
            }
            backendLabel.value = llm.backend
            if (!thinking.value) status.value = ""
        }
        viewModelScope.launch { assistantName.value = Profile(db).assistantName() }
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

    /** Thêm ảnh từ thư viện hoặc máy ảnh vào tin nhắn sắp gửi (tối đa [ChatMedia.MAX_IMAGES] ảnh). */
    fun attachImages(uris: List<Uri>) {
        viewModelScope.launch {
            mediaNote.value = ""
            for (uri in uris) {
                if (attachedImages.size >= ChatMedia.MAX_IMAGES) { mediaNote.value = "Tối đa ${ChatMedia.MAX_IMAGES} ảnh một lần."; break }
                try { attachedImages.add(ChatMedia.loadImage(getApplication<Application>(), uri)) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { mediaNote.value = "Không mở được ảnh: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    fun removeImage(i: Int) { if (i in attachedImages.indices) attachedImages.removeAt(i) }

    /** Bắt đầu thu âm để gửi cho mô hình nghe (khác nút 🎤 là đổi giọng nói thành chữ). */
    fun startRecording() {
        if (recording.value) return
        stopPlaying()
        stopRecording = false
        recordCancelled = false
        recordMs.longValue = 0
        recordLevel.floatValue = 0f
        recording.value = true
        mediaNote.value = ""
        viewModelScope.launch {
            try {
                val pcm = ChatMedia.record({ stopRecording }) { ms, level ->
                    recordMs.longValue = ms
                    recordLevel.floatValue = level
                }
                if (!recordCancelled && ChatMedia.durationMs(pcm) >= 500) attachedAudio.value = pcm
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mediaNote.value = "Không thu âm được: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                recording.value = false
            }
        }
    }

    /** Xong ([cancel] = false) thì giữ đoạn vừa thu để gửi; huỷ thì bỏ. */
    fun finishRecording(cancel: Boolean) {
        recordCancelled = cancel
        stopRecording = true
    }

    fun removeAudio() { stopPlaying(); attachedAudio.value = null }

    fun play(pcm: ByteArray) {
        stopPlaying()
        player = ChatMedia.play(pcm)
    }

    private fun stopPlaying() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    override fun onCleared() { stopRecording = true; stopPlaying(); super.onCleared() }

    /**
     * Gửi tin nhắn cùng ảnh / ghi âm đang đính kèm (nếu có).
     * [voiceMs] là thời gian nghe và nhận dạng giọng nói (nếu nói bằng 🎤), để hiện ở màn thống kê.
     */
    fun send(input: String, voiceMs: Long = 0) {
        val images = attachedImages.toList()
        val audio = attachedAudio.value
        if ((input.isBlank() && images.isEmpty() && audio == null) || thinking.value || recording.value) return
        val text = input.trim().ifEmpty {
            when {
                images.isNotEmpty() && audio != null -> "Hãy xem ảnh và nghe đoạn ghi âm này giúp mình."
                images.isNotEmpty() -> "Ảnh này có gì vậy?"
                else -> "Hãy nghe đoạn ghi âm này giúp mình."
            }
        }
        attachedImages.clear()
        attachedAudio.value = null
        stopPlaying()
        val myTurn = ++turnId
        thinking.value = true
        partial.value = ""
        thought.value = ""
        status.value = if (llm.isLoaded() || !llm.hasModel()) "" else "Đang nạp mô hình AI, lần đầu hơi lâu…"
        job = viewModelScope.launch {
            // Lưu vào lịch sử kèm dấu 📷 / 🎙️ để đọc lại vẫn biết tin này có ảnh hay ghi âm (ảnh không lưu ra máy)
            val tag = buildString {
                if (images.isNotEmpty()) append(if (images.size > 1) "📷×${images.size} " else "📷 ")
                if (audio != null) append("🎙️ ")
            }
            val userMsg = add(ChatMessage(fromUser = true, text = tag + text))
            if (images.isNotEmpty() || audio != null) media[userMsg.id] = MessageMedia(images, audio)
            val jpegs = withContext(Dispatchers.Default) { images.map(ChatMedia::toJpeg) }
            val wavs = listOfNotNull(audio?.let { ChatMedia.wav(it) })
            val t0 = System.currentTimeMillis()
            var firstMs = 0L
            val wasLoaded = llm.isLoaded()
            var stat: TurnStat? = null
            var used: List<ToolOutput> = emptyList()
            val reply = try {
                val turn = assistant.handle(text, jpegs, wavs, onPartial = { p ->
                    // Gọi từ luồng của mô hình; Compose state cho phép ghi từ luồng khác.
                    // Lượt đã bị dừng (MediaPipe vẫn chạy nốt ở nền) thì không vẽ lên màn hình nữa.
                    if (myTurn != turnId) return@handle
                    if (firstMs == 0L) firstMs = System.currentTimeMillis() - t0
                    partial.value = p
                    status.value = ""
                }, onThinking = { t ->
                    if (myTurn == turnId) { thought.value = t; status.value = "" }
                })
                used = turn.tools
                val total = System.currentTimeMillis() - t0
                stat = TurnStat(
                    at = System.currentTimeMillis(),
                    question = tag + text,
                    tier = turn.tier,
                    totalMs = total,
                    firstMs = firstMs,
                    voiceMs = voiceMs,
                    backend = if (turn.tier == Tier.LLM) llm.backend else "",
                    loadMs = if (turn.tier == Tier.LLM && !wasLoaded) llm.loadMs else 0,
                    gen = if (turn.tier == Tier.LLM) llm.lastStats else null,
                )
                val result = turn.action?.let { runCatching { executor.run(it) }.getOrElse { e -> "Không thực hiện được: ${e.message}" } }
                // Hành động set_profile có thể vừa đổi tên trợ lý
                if (turn.action?.type == "set_profile") assistantName.value = Profile(db).assistantName()
                listOf(turn.reply, result).filter { !it.isNullOrBlank() }.joinToString("\n")
                    .ifBlank { "Mình chưa nghĩ ra câu trả lời. Bạn nói lại theo cách khác giúp mình nhé." }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Bắt cả lỗi nặng (thiếu RAM, lỗi thư viện) để không bao giờ im lặng
                "⚠️ Lỗi: ${e.message ?: e.javaClass.simpleName}"
            }
            if (myTurn != turnId) return@launch
            backendLabel.value = llm.backend
            val thoughtText = thought.value
            finishTurn()
            stat?.let { stats.add(0, it); if (stats.size > 100) stats.removeAt(stats.size - 1) }
            val saved = add(ChatMessage(fromUser = false, text = reply))
            if (used.isNotEmpty()) toolOutputs[saved.id] = used
            if (thoughtText.isNotBlank()) thoughts[saved.id] = thoughtText
        }
    }

    // ---- Màn 🧩 Skill & MCP ----
    val skillList = mutableStateListOf<Skill>()
    val mcpServers = mutableStateListOf<McpServer>()
    val toolsBusy = mutableStateOf(false)
    val toolsNote = mutableStateOf("")

    fun refreshTools() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { tools.skills.all() }
            skillList.clear(); skillList.addAll(list)
            mcpServers.clear(); mcpServers.addAll(tools.mcp.servers())
        }
    }

    fun setSkillEnabled(name: String, on: Boolean) { tools.skills.setEnabled(name, on); refreshTools() }
    fun setSkillSecret(name: String, value: String) { tools.skills.setSecret(name, value); toolsNote.value = "Đã lưu khoá cho $name." }
    fun deleteSkill(s: Skill) { tools.skills.delete(s); refreshTools() }
    fun addSkillFromUrl(url: String) = toolsJob { "✅ Đã thêm skill ${tools.skills.addFromUrl(url).name}." }
    fun importSkillFolder(tree: Uri) = toolsJob { "✅ Đã thêm skill ${tools.skills.importFolder(tree).name}." }

    fun connectMcp(server: McpServer) = toolsJob {
        val s = tools.mcp.connect(server)
        "✅ Đã kết nối ${s.label}: ${s.tools.size} công cụ."
    }
    fun removeMcp(url: String) { tools.mcp.remove(url); refreshTools() }
    fun setMcpEnabled(url: String, on: Boolean) {
        tools.mcp.servers().firstOrNull { it.url == url }?.let { tools.mcp.upsert(it.copy(enabled = on)) }
        refreshTools()
    }
    fun setMcpToolEnabled(url: String, tool: String, on: Boolean) {
        tools.mcp.servers().firstOrNull { it.url == url }?.let { s ->
            tools.mcp.upsert(s.copy(tools = s.tools.map { if (it.name == tool) it.copy(enabled = on) else it }))
        }
        refreshTools()
    }

    private fun toolsJob(block: suspend () -> String) {
        if (toolsBusy.value) return
        toolsBusy.value = true
        toolsNote.value = "Đang làm…"
        viewModelScope.launch {
            toolsNote.value = try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { "Lỗi: ${e.message ?: e.javaClass.simpleName}" }
            finally { toolsBusy.value = false; refreshTools() }
        }
    }

    /**
     * Nút ■: dừng câu trả lời đang viết, giữ lại phần đã viết như Gallery. Màn hình trả lại ô nhập ngay,
     * không đợi mô hình dừng hẳn.
     */
    fun stop() {
        if (!thinking.value) return
        llm.stop()
        val shown = partial.value
        turnId++ // lượt cũ có chạy nốt cũng không ghi đè gì nữa
        job?.cancel()
        finishTurn()
        viewModelScope.launch {
            add(ChatMessage(fromUser = false, text = shown.ifBlank { "(Đã dừng)" }))
        }
    }

    private fun finishTurn() {
        thinking.value = false
        partial.value = ""
        thought.value = ""
        status.value = ""
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

    /** Thông tin mô hình cho màn thống kê. */
    fun modelInfo(): ModelInfo = ModelInfo(
        file = if (llm.hasModel()) llm.modelFile.name else "",
        sizeMb = llm.modelSizeMb(),
        backend = llm.backend,
        loadMs = llm.loadMs,
        loaded = llm.isLoaded(),
    )

    private suspend fun add(m: ChatMessage): ChatMessage {
        val id = db.chats().insert(m)
        val saved = m.copy(id = id)
        messages.add(saved)
        if (!saved.fromUser && speechSettings.value.autoSpeak) toSpeak.value = saved
        return saved
    }
}

/** Một lượt hỏi đáp: trả lời ở tầng nào, mất bao lâu, và (nếu dùng AI) số liệu của mô hình. */
data class TurnStat(
    val at: Long,
    val question: String,
    val tier: Tier,
    val totalMs: Long,
    /** Từ lúc bấm gửi tới khi chữ đầu hiện lên (0 nếu không dùng AI). */
    val firstMs: Long,
    val voiceMs: Long,
    val backend: String,
    /** Thời gian nạp mô hình nếu lượt này phải nạp (0 nếu đã nạp sẵn). */
    val loadMs: Long,
    val gen: GenStats?,
)

data class ModelInfo(val file: String, val sizeMb: Long, val backend: String, val loadMs: Long, val loaded: Boolean)
