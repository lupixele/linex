package com.linex.app.ui.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.linex.app.core.DisplayEndpoint
import com.linex.app.core.RfbClient
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicReference

/** An in-app desktop surface. All bitmap mutations and drawing stay on the UI thread. */
class EmbeddedDesktopView(context: Context) : View(context) {
    var onConnection: (Boolean, String) -> Unit = { _, _ -> }
    @Volatile private var disposed = false
    @Volatile private var client: RfbClient? = null
    private var worker: Thread? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private data class Frame(val width: Int, val height: Int, val pixels: IntArray)
    private val pending = AtomicReference<Frame?>()
    var trackpadMode = false
    private var pointerX = 0
    private var pointerY = 0
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pressed = false
    private val heldKeys = mutableMapOf<Int, Int>()
    private val applyFrame = object : Runnable {
        override fun run() {
            if (disposed) return
            val frame = pending.getAndSet(null) ?: return
            var image = bitmap
            if (image == null || image.width != frame.width || image.height != frame.height) {
                image = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                bitmap = image
            }
            image.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            onConnection(true, "Desktop connected")
            invalidate()
        }
    }
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        contentDescription = "Linux desktop. Touch to click or drag; use Keyboard to type."
    }
    fun connect(endpoint: DisplayEndpoint) {
        if (worker != null || disposed) return
        worker = Thread({
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!disposed) {
                val connection = RfbClient(endpoint.port, endpoint.password, { w, h, pixels ->
                    pending.set(Frame(w, h, pixels))
                    removeCallbacks(applyFrame)
                    post(applyFrame)
                }, { status -> post { if (!disposed) onConnection(false, status) } })
                client = connection
                if (disposed) { connection.close(); break }
                try {
                    connection.run()
                    if (!disposed) post { if (!disposed) onConnection(false, "Desktop disconnected. Retry to reconnect.") }
                    break
                } catch (failure: Exception) {
                    if (disposed) break
                    if (failure is ConnectException && System.nanoTime() < deadline) {
                        post { if (!disposed) onConnection(false, "Waiting for the desktop server…") }
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    } else {
                        post { if (!disposed) onConnection(false, if (failure is ConnectException) "Desktop server is not ready. First startup may still be installing packages; check instance logs, then retry." else failure.message ?: "Desktop connection failed") }
                        break
                    }
                } finally { connection.close() }
            }
        }, "linex-display-reader").apply { isDaemon = true; start() }
    }
    fun disconnect() {
        disposed = true
        client?.close()
        worker?.interrupt()
        removeCallbacks(applyFrame)
        pending.set(null)
        bitmap = null
        keepScreenOn = false
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) releaseInput()

    }
    fun releaseInput() {
        heldKeys.values.forEach { client?.key(it, false) }
        heldKeys.clear()
        if (pressed) { client?.pointer(pointerX, pointerY, 0); pressed = false }
    }
    fun toggleKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(image, null, destination, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        if (destination.isEmpty) return false
        var x = ((event.x - destination.left) * image.width / destination.width()).toInt().coerceIn(0, image.width - 1)
        var y = ((event.y - destination.top) * image.height / destination.height()).toInt().coerceIn(0, image.height - 1)
        if (trackpadMode) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                if (kotlin.math.abs(event.x - lastX) + kotlin.math.abs(event.y - lastY) > 2) moved = true
                pointerX = (pointerX + (event.x - lastX).toInt()).coerceIn(0, image.width - 1)
                pointerY = (pointerY + (event.y - lastY).toInt()).coerceIn(0, image.height - 1)
            }
            x = pointerX; y = pointerY
        } else { pointerX = x; pointerY = y }
        lastX = event.x; lastY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { requestFocus(); moved = false; pressed = !trackpadMode; parent.requestDisallowInterceptTouchEvent(true); client?.pointer(x, y, if (pressed) 1 else 0) }
            MotionEvent.ACTION_MOVE -> client?.pointer(x, y, if (pressed) 1 else 0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (trackpadMode && !moved && event.actionMasked == MotionEvent.ACTION_UP) client?.pointer(x, y, 1); pressed = false; client?.pointer(x, y, 0); parent.requestDisallowInterceptTouchEvent(false); if (event.actionMasked == MotionEvent.ACTION_UP) performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun symbol(event: KeyEvent): Int = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER -> 0xff0d
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        else -> unicodeSymbol(event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv()))
    }
    private fun unicodeSymbol(value: Int): Int = if (value <= 255) value else 0x01000000 or value
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = symbol(event)
        if (key == 0) return super.onKeyDown(keyCode, event)
        heldKeys[keyCode] = key
        client?.key(key, true)
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val key = heldKeys.remove(keyCode) ?: return super.onKeyUp(keyCode, event)
        client?.key(key, false)
        return true
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.let { value ->
                    if (value.codePointCount(0, value.length) > 4096) {
                        android.widget.Toast.makeText(context, "Paste up to 4,096 characters at a time", android.widget.Toast.LENGTH_LONG).show()
                    } else client?.text(value)
                }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                client?.deleteText(beforeLength, afterLength)
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)
        }
    }
}
