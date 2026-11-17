package vn.phamvu.trolyai.skills

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume

/** Kết quả một skill JS, theo quy ước của Gallery: result / error / image.base64 / webview {url, iframe, aspectRatio}. */
data class SkillResult(
    val text: String,
    val error: String? = null,
    /** Ảnh base64 (có thể kèm "data:image/png;base64,"). */
    val imageBase64: String? = null,
    /** Trang web hiện ngay trong bong bóng chat (bản đồ, bảng điều khiển…). */
    val webviewUrl: String? = null,
    val webviewIframe: Boolean = false,
    val aspectRatio: Float = 1.333f,
)

/**
 * Chạy skill JS giống Google AI Edge Gallery: nạp scripts/index.html vào một WebView ẩn rồi gọi
 * `window.ai_edge_gallery_get_result(data, secret)`, chờ chuỗi JSON trả về qua cầu nối Android.
 */
class JsSkillRunner(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(skill: Skill, script: String?, data: String, secret: String): SkillResult {
        val url = skill.scriptUrl(script)
        val raw = withContext(Dispatchers.Main) {
            withTimeout(45_000) {
                suspendCancellableCoroutine { cont ->
                    val wv = WebView(ctx.applicationContext)
                    var finished = false
                    var started = false
                    fun finish(r: String) {
                        if (finished) return
                        finished = true
                        main.post { runCatching { wv.destroy() } }
                        if (cont.isActive) cont.resume(r)
                    }
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        // Skill trong máy là file:// nhưng vẫn cần gọi API trên mạng và đọc file cạnh nó
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                    }
                    wv.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onResult(r: String?) { main.post { finish(r ?: "{}") } }
                    }, "TroLyBridge")
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            if (started) return
                            started = true
                            view.evaluateJavascript(callScript(data, secret), null)
                        }
                    }
                    cont.invokeOnCancellation { main.post { runCatching { wv.destroy() } } }
                    wv.loadUrl(url)
                }
            }
        }
        return parse(raw, skill)
    }

    private fun callScript(data: String, secret: String) = """
        (function(){
          var f = window['ai_edge_gallery_get_result'];
          if (typeof f !== 'function') {
            TroLyBridge.onResult(JSON.stringify({error: 'Skill không có hàm ai_edge_gallery_get_result'}));
            return;
          }
          Promise.resolve().then(function(){ return f(${JSONObject.quote(data)}, ${JSONObject.quote(secret)}); })
            .then(function(r){ TroLyBridge.onResult(typeof r === 'string' ? r : JSON.stringify(r)); })
            .catch(function(e){ TroLyBridge.onResult(JSON.stringify({error: String((e && e.message) || e)})); });
        })();
    """.trimIndent()

    private fun parse(raw: String, skill: Skill): SkillResult {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return SkillResult(text = raw)
        val error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" }
        val result = obj.opt("result")?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
        val image = obj.optJSONObject("image")?.optString("base64")?.takeIf { it.isNotBlank() }
        val web = obj.optJSONObject("webview")
        val webUrl = web?.optString("url")?.takeIf { it.isNotBlank() }?.let { u ->
            // Đường dẫn tương đối nằm trong thư mục assets/ của skill
            if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file:")) u else skill.baseUrl + "assets/" + u
        }
        // Những gì không phải ảnh / trang web thì đưa lại cho mô hình đọc
        val text = buildString {
            if (result.isNotEmpty()) append(result)
            obj.optString("title").takeIf { it.isNotBlank() }?.let { if (isNotEmpty()) append("\n"); append("Tiêu đề: $it") }
            if (image != null) { if (isNotEmpty()) append("\n"); append("(Đã hiện ảnh cho người dùng xem.)") }
            if (webUrl != null) { if (isNotEmpty()) append("\n"); append("(Đã hiện khung xem cho người dùng.)") }
        }
        return SkillResult(
            text = text,
            error = error,
            imageBase64 = image,
            webviewUrl = webUrl,
            webviewIframe = web?.optBoolean("iframe") == true,
            aspectRatio = web?.optDouble("aspectRatio", 1.333)?.toFloat()?.takeIf { it > 0.2f } ?: 1.333f,
        )
    }
}
