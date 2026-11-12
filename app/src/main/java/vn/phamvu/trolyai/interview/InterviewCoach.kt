package vn.phamvu.trolyai.interview

import vn.phamvu.trolyai.data.AppDb
import vn.phamvu.trolyai.llm.LlmEngine

/** Ghép câu hỏi của người phỏng vấn + hồ sơ của bạn thành prompt, nhờ mô hình gợi ý ý trả lời ngắn. */
class InterviewCoach(private val llm: LlmEngine, private val db: AppDb) {

    /** [recent]: vài lượt gần nhất, dạng "Interviewer: …" hoặc "Me: …". */
    suspend fun suggest(question: String, recent: List<String> = emptyList()): String {
        val profile = db.interview().get()?.text?.takeIf { it.isNotBlank() } ?: "(not provided)"
        return llm.generate(prompt(question, profile, recent))
            .replace("<end_of_turn>", "").trim()
    }

    private fun prompt(question: String, profile: String, recent: List<String>): String {
        val hist = if (recent.isEmpty()) "(start of interview)" else recent.takeLast(6).joinToString("\n")
        return """
<start_of_turn>user
You help me during a live job interview in English. I will glance at your answer for 2 seconds, so be very short.
The interviewer's words come from speech recognition and may contain wrong words; guess the intended meaning.

About me (use only these facts, never invent experience, numbers or company names):
$profile

Recent conversation:
$hist

Interviewer just said: "$question"

Reply in exactly this format, nothing else:
VI: <what the interviewer asked, in Vietnamese, one short line>
- <key point 1, simple English, max 12 words>
- <key point 2, max 12 words>
- <key point 3, max 12 words>
Say: <one natural first sentence I can say out loud>
<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }
}
