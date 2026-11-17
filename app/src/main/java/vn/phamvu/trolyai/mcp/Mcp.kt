package vn.phamvu.trolyai.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Một công cụ của máy chủ MCP: tên, mô tả và JSON schema tham số. */
data class McpTool(val name: String, val description: String, val schema: JSONObject, val enabled: Boolean = true) {
    /** Tóm tắt tham số cho prompt (ngắn để tiết kiệm bộ nhớ ngữ cảnh 4096 token): "url*, max_length". */
    fun paramsSummary(): String {
        val props = schema.optJSONObject("properties") ?: return ""
        val required = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        return props.keys().asSequence().map { if (it in required) "$it*" else it }.joinToString(", ")
    }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description)
        .put("schema", schema).put("enabled", enabled)

    companion object {
        fun from(o: JSONObject) = McpTool(
            o.optString("name"), o.optString("description"),
            o.optJSONObject("schema") ?: o.optJSONObject("inputSchema") ?: JSONObject(), o.optBoolean("enabled", true),
        )
    }
}

/** Một máy chủ MCP bạn thêm vào: đường link (Streamable HTTP), một header xác thực tuỳ chọn, và các công cụ đã tìm thấy. */
data class McpServer(
    val url: String,
    val headerName: String = "",
    val headerValue: String = "",
    val enabled: Boolean = true,
    val tools: List<McpTool> = emptyList(),
) {
    /** Tên ngắn để hiện và để mô hình gọi, ví dụ "mapstools.googleapis.com". */
    val label: String get() = runCatching { URL(url).host }.getOrDefault(url)

    fun toJson(): JSONObject = JSONObject().put("url", url).put("headerName", headerName)
        .put("headerValue", headerValue).put("enabled", enabled)
        .put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject) = McpServer(
            o.optString("url"), o.optString("headerName"), o.optString("headerValue"), o.optBoolean("enabled", true),
            o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { McpTool.from(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/**
 * Máy khách MCP tối giản theo chuẩn Streamable HTTP (https://modelcontextprotocol.io), như Gallery:
 * initialize → tools/list → tools/call, mỗi yêu cầu là một POST JSON-RPC; máy chủ trả JSON hoặc luồng SSE.
 */
class McpClient(private val server: McpServer) {
    private var sessionId: String? = null
    private var protocol: String? = null
    private var nextId = 1

    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        initialize()
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val res = request("tools/list", params)
            val arr = res.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                out += McpTool(t.optString("name"), t.optString("description"), t.optJSONObject("inputSchema") ?: JSONObject())
            }
            cursor = res.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null && out.size < 200)
        out
    }

    /** Gọi một công cụ, trả về phần chữ trong kết quả (các khối "text" nối lại). */
    suspend fun callTool(name: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        initialize()
        val res = request("tools/call", JSONObject().put("name", name).put("arguments", args))
        val content = res.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                when (c.optString("type")) {
                    "text" -> append(c.optString("text")).append('\n')
                    "resource" -> c.optJSONObject("resource")?.optString("text")?.let { append(it).append('\n') }
                    else -> append("[${c.optString("type")}]\n")
                }
            }
            if (isEmpty()) res.optJSONObject("structuredContent")?.let { append(it.toString()) }
        }.trim()
        if (res.optBoolean("isError")) "Lỗi từ công cụ: $text" else text
    }

    private fun initialize() {
        if (sessionId != null || protocol != null) return
        val res = request("initialize", JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "tro-ly-ai").put("version", "0.1")))
        protocol = res.optString("protocolVersion").ifBlank { "2025-06-18" }
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectReply = false)
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val reply = post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), true)
            ?: throw IOException("Máy chủ MCP không trả lời $method")
        reply.optJSONObject("error")?.let { throw IOException("MCP $method lỗi: ${it.optString("message")}") }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    private fun post(body: JSONObject, expectReply: Boolean): JSONObject? {
        val conn = URL(server.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            protocol?.let { conn.setRequestProperty("MCP-Protocol-Version", it) }
            sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            if (server.headerName.isNotBlank()) conn.setRequestProperty(server.headerName.trim(), server.headerValue.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code == 202 || !expectReply) return null
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
                throw IOException("Máy chủ MCP báo lỗi $code${if (err.isNotBlank()) ": $err" else ""}")
            }
            val type = conn.contentType ?: ""
            val wantId = body.opt("id")
            return conn.inputStream.bufferedReader().use { r ->
                if (!type.contains("event-stream")) return@use JSONObject(r.readText())
                // Luồng SSE: đọc từng sự kiện "data:" cho tới khi gặp câu trả lời đúng id
                val data = StringBuilder()
                var found: JSONObject? = null
                while (found == null) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                        line.isEmpty() && data.isNotEmpty() -> {
                            val o = runCatching { JSONObject(data.toString()) }.getOrNull()
                            data.setLength(0)
                            if (o != null && o.has("id") && o.opt("id").toString() == wantId.toString()) found = o
                        }
                    }
                }
                found ?: data.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it.toString()) }.getOrNull() }
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Danh sách máy chủ MCP, lưu trong bộ nhớ riêng của app (kể cả header xác thực). */
class McpStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mcp", Context.MODE_PRIVATE)

    fun servers(): List<McpServer> = runCatching {
        val a = JSONArray(prefs.getString("servers", "[]"))
        (0 until a.length()).map { McpServer.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(list: List<McpServer>) =
        prefs.edit().putString("servers", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()

    fun upsert(s: McpServer) = save(servers().filter { it.url != s.url } + s)
    fun remove(url: String) = save(servers().filter { it.url != url })

    /** Kết nối thử rồi lưu máy chủ cùng danh sách công cụ. Công cụ đã tắt trước đó vẫn giữ trạng thái tắt. */
    suspend fun connect(s: McpServer): McpServer {
        val old = servers().firstOrNull { it.url == s.url }?.tools?.associateBy { it.name } ?: emptyMap()
        val tools = McpClient(s).listTools().map { t -> t.copy(enabled = old[t.name]?.enabled ?: true) }
        return s.copy(tools = tools).also(::upsert)
    }

    /** Công cụ đang bật của các máy chủ đang bật. */
    fun enabledTools(): List<Pair<McpServer, McpTool>> =
        servers().filter { it.enabled }.flatMap { s -> s.tools.filter { it.enabled }.map { s to it } }
}
