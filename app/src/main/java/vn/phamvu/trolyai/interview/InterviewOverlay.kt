package vn.phamvu.trolyai.interview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Ô gợi ý nhỏ nổi trên mọi ứng dụng, màu tối, chữ nhỏ.
 * - Chạm: thu gọn thành một chấm nhỏ / mở lại.
 * - Kéo: di chuyển tới chỗ kín đáo.
 * - Giữ lâu (1 giây): tắt chế độ ẩn.
 * Chấm thu gọn chuyển xanh khi có gợi ý mới.
 * Mọi hàm phải gọi trên luồng chính.
 */
class InterviewOverlay(private val ctx: Context, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val heard = TextView(ctx).apply {
        setTextColor(0xFF9E9E9E.toInt()); textSize = 11f
        maxLines = 2; ellipsize = TextUtils.TruncateAt.START
    }
    private val answer = TextView(ctx).apply {
        setTextColor(0xFFEEEEEE.toInt()); textSize = 13f; maxLines = 12
        setPadding(0, px(4), 0, 0)
        text = "Đang nghe…"
    }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(10), px(8), px(10), px(8))
        addView(heard); addView(answer)
    }
    private val dot = TextView(ctx).apply {
        text = "●"; textSize = 12f; setTextColor(IDLE); gravity = Gravity.CENTER
        setPadding(px(8), px(4), px(8), px(4)); visibility = View.GONE
    }
    private val root = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { setColor(0xD9121212.toInt()); cornerRadius = 12 * dp }
        addView(body); addView(dot)
    }
    private val params = WindowManager.LayoutParams(
        px(WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Không giành bàn phím, không chặn chạm ngoài ô
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = px(12); y = px(96) }

    private var collapsed = false
    private var shown = false
    private val handler = Handler(Looper.getMainLooper())

    init { setupTouch() }

    fun show() { if (!shown) { wm.addView(root, params); shown = true } }
    fun remove() { if (shown) { runCatching { wm.removeView(root) }; shown = false } }

    /** Chữ đang nghe được (dòng xám nhỏ phía trên). */
    fun heard(text: String) { heard.text = text }

    fun status(text: String) { answer.text = text }

    fun suggestion(text: String) {
        answer.text = text
        if (collapsed) dot.setTextColor(FRESH)
    }

    private fun toggle() {
        collapsed = !collapsed
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        dot.visibility = if (collapsed) View.VISIBLE else View.GONE
        dot.setTextColor(IDLE)
        params.width = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else px(WIDTH_DP)
        if (shown) wm.updateViewLayout(root, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false
        val longPress = Runnable { longPressed = true; onStop() }
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    moved = false; longPressed = false
                    handler.postDelayed(longPress, 1000)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > 10 * dp) { moved = true; handler.removeCallbacks(longPress) }
                    if (moved && shown) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) toggle()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
    }

    companion object {
        private const val WIDTH_DP = 260
        private val IDLE = 0xFF616161.toInt()
        private val FRESH = Color.rgb(102, 187, 106)
    }
}
