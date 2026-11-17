package vn.phamvu.trolyai.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import vn.phamvu.trolyai.mcp.McpClient
import vn.phamvu.trolyai.mcp.McpStore
import vn.phamvu.trolyai.skills.JsSkillRunner
import vn.phamvu.trolyai.skills.SkillStore

/** Kết quả một lần dùng công cụ: phần chữ đưa lại cho mô hình, và (nếu có) ảnh / trang web hiện cho người dùng. */
data class ToolOutput(
    /** Ví dụ "🧩 query-wikipedia" hay "🔌 fetch", hiện thành nhãn nhỏ dưới câu trả lời. */
    val label: String,
    val text: String,
    val imageBase64: String? = null,
    val webviewUrl: String? = null,
    val webviewIframe: Boolean = false,
    val aspectRatio: Float = 1.333f,
)

/**
 * Agent Skills và MCP, làm theo Google AI Edge Gallery. Mô hình thấy tên + mô tả của các skill / công cụ đang bật
 * trong phần system; khi cần thì viết dòng lệnh, app chạy rồi đưa kết quả lại để mô hình trả lời tiếp:
 * - load_skill: đọc hướng dẫn trong SKILL.md (skill chỉ có chữ cũng dùng cách này)
 * - run_js: chạy scripts/index.html của skill trong WebView ẩn
 * - run_intent: gửi email / tin nhắn bằng app có sẵn trong máy (skill "native" của Gallery)
 * - mcp: gọi một công cụ trên máy chủ MCP
 */
class AgentTools(private val ctx: Context) {
    val skills = SkillStore(ctx)
    val mcp = McpStore(ctx)
    private val js = JsSkillRunner(ctx)

    /** Đoạn thêm vào phần system; rỗng nếu không có skill / công cụ nào đang bật. */
    fun promptSection(): String {
        val sk = skills.enabled()
        val tools = mcp.enabledTools()
        if (sk.isEmpty() && tools.isEmpty()) return ""
        return buildString {
            append("\nCông cụ thêm (chỉ dùng khi thật sự cần, ví dụ cần tra cứu trên mạng hay cần hiện bản đồ):\n")
            if (sk.isNotEmpty()) {
                append("Skill:\n")
                sk.forEach { append("- ${it.name}: ${it.description.take(120)}\n") }
                append("Muốn dùng skill: [[LỆNH]] {\"type\":\"load_skill\",\"name\":\"tên skill\"}. App sẽ gửi hướng dẫn của skill, bạn làm theo.\n")
                append("Hướng dẫn bảo gọi run_js thì viết: [[LỆNH]] {\"type\":\"run_js\",\"skill\":\"tên skill\",\"script\":\"index.html\",\"data\":{...}}\n")
                append("Hướng dẫn bảo gọi run_intent thì viết: [[LỆNH]] {\"type\":\"run_intent\",\"intent\":\"send_email\",\"parameters\":{...}}\n")
            }
            if (tools.isNotEmpty()) {
                append("Công cụ MCP (tham số có * là bắt buộc):\n")
                tools.forEach { (_, t) -> append("- ${t.name}(${t.paramsSummary()}): ${t.description.take(120)}\n") }
                append("Gọi công cụ MCP: [[LỆNH]] {\"type\":\"mcp\",\"tool\":\"tên công cụ\",\"args\":{...}}\n")
            }
            append("Sau khi có kết quả, trả lời người dùng bằng lời bình thường, ngắn gọn, bằng tiếng Việt.\n")
        }
    }

    fun handles(type: String) = type in setOf("load_skill", "run_js", "run_intent", "mcp")

    /** Chạy một lệnh công cụ. Không bao giờ ném lỗi: lỗi được trả về thành chữ để mô hình xin lỗi / thử cách khác. */
    suspend fun run(a: Action): ToolOutput = try { runOrThrow(a) } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        ToolOutput(a.type, "Công cụ chạy quá lâu nên đã dừng.")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ToolOutput(a.type, "Lỗi khi chạy công cụ: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun runOrThrow(a: Action): ToolOutput {
        return when (a.type) {
            "load_skill" -> {
                val name = a.args.optString("name")
                val s = skills.find(name)?.takeIf { skills.isEnabled(it.name) }
                if (s == null) ToolOutput("🧩 $name", "Không có skill tên \"$name\". Các skill đang bật: " +
                    skills.enabled().joinToString { it.name })
                else ToolOutput("🧩 ${s.name}", "Hướng dẫn của skill ${s.name}:\n${s.instructions.take(3000)}")
            }
            "run_js" -> {
                val name = a.args.optString("skill").ifBlank { a.args.optString("name") }
                val s = skills.find(name)?.takeIf { skills.isEnabled(it.name) }
                    ?: return ToolOutput("🧩 $name", "Không có skill tên \"$name\".")
                // Mô hình có thể viết data là đối tượng JSON hoặc chuỗi JSON; skill của Gallery nhận chuỗi
                val data = when (val d = a.args.opt("data")) {
                    is JSONObject -> d.toString()
                    is String -> d
                    null -> "{}"
                    else -> d.toString()
                }
                val r = js.run(s, a.args.optString("script").ifBlank { null }, data, skills.secret(s.name))
                ToolOutput(
                    label = "🧩 ${s.name}",
                    text = r.error?.let { "Skill báo lỗi: $it" } ?: r.text.take(3000).ifBlank { "Xong." },
                    imageBase64 = r.imageBase64,
                    webviewUrl = r.webviewUrl,
                    webviewIframe = r.webviewIframe,
                    aspectRatio = r.aspectRatio,
                )
            }
            "run_intent" -> runIntent(a.args)
            "mcp" -> {
                val name = a.args.optString("tool")
                val (server, tool) = mcp.enabledTools().firstOrNull { it.second.name == name }
                    ?: return ToolOutput("🔌 $name", "Không có công cụ MCP tên \"$name\" đang bật.")
                val args = a.args.optJSONObject("args") ?: a.args.optString("args").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                ToolOutput("🔌 ${tool.name}", McpClient(server).callTool(tool.name, args).take(3000).ifBlank { "Xong." })
            }
            else -> ToolOutput(a.type, "Không biết lệnh ${a.type}.")
        }
    }

    /** Skill "native" của Gallery: mở app email / tin nhắn có sẵn, bạn tự bấm gửi. */
    private fun runIntent(args: JSONObject): ToolOutput {
        val p = args.optJSONObject("parameters")
            ?: args.optString("parameters").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        val intent = when (args.optString("intent")) {
            "send_email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + p.optString("extra_email")))
                .putExtra(Intent.EXTRA_SUBJECT, p.optString("extra_subject"))
                .putExtra(Intent.EXTRA_TEXT, p.optString("extra_text"))
            "send_sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + p.optString("phone_number")))
                .putExtra("sms_body", p.optString("sms_body"))
            else -> return ToolOutput("📲", "App chưa hỗ trợ intent \"${args.optString("intent")}\".")
        }
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolOutput("📲 ${args.optString("intent")}", "Đã mở app để người dùng xem lại và tự bấm gửi.")
    }
}
