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
import android.widget.FrameLayout
import android.view.Gravity
import com.termux.x11.LorieView
import com.linex.app.core.DisplayBackend
import com.linex.app.core.EmbeddedX11Server
import kotlinx.coroutines.runBlocking
import android.view.ViewConfiguration
import android.view.InputDevice
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.linex.app.core.DisplayEndpoint
import com.linex.app.core.RfbClient
import com.linex.app.core.DesktopInput
import com.linex.app.core.NativeDesktopInput
import com.linex.app.core.DesktopGesture
import com.linex.app.core.LatestFrameMailbox
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicReference
import android.os.ParcelFileDescriptor

/** An in-app desktop surface. All bitmap mutations and drawing stay on the UI thread. */
class EmbeddedDesktopView(context: Context) : FrameLayout(context) {
    var onConnection: (Boolean, String) -> Unit = { _, _ -> }
    /** Controls and dialogs must never forward typing to the desktop behind them. */
    var inputEnabled = true
        set(value) { if (field && !value) releaseInput(); field = value }
    @Volatile private var disposed = false
    @Volatile private var client: RfbClient? = null
    private var worker: Thread? = null
    @Volatile private var displayVisible = true
    private var bitmap: Bitmap? = null
    private var nativeView: LorieView? = null
    private val nativeDescriptor = AtomicReference<ParcelFileDescriptor?>()
    private var nativeWidth = 0
    private var nativeHeight = 0
    private var nativeButtons = 0
    private val nativeKeys = mutableMapOf<Int, Int>()
    private val checkNativeConnection = object : Runnable {
        override fun run() {
            val surface = nativeView ?: return
            if (disposed || !displayVisible) return
            if (!surface.isConnected) {
                releaseInput()
                onConnection(false, "Native display disconnected. Restart the instance or select Compatibility display.")
                return
            }
            postDelayed(this, 1000)
        }
    }
    val frameMetricsAvailable: Boolean get() = nativeView == null
    private val frameWidth: Int get() = bitmap?.width ?: nativeWidth
    private val frameHeight: Int get() = bitmap?.height ?: nativeHeight
    var presentedFrameCount: Long = 0
        private set
    private var frameDirty = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        setShadowLayer(2f, 0f, 0f, android.graphics.Color.BLACK)
    }
    private val destination = RectF()
    private data class Frame(val width: Int, val height: Int, val pixels: IntArray, val owner: RfbClient) {
        fun release() = owner.recycleFrame(pixels)
    }
    var trackpadMode = false
        set(value) { if (field != value) releaseInput(); field = value; invalidate() }
    private var pointerX = 0
    private var pointerY = 0
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pressed = false
    private var buttons = 0
    private var gestureStart = 0L
    private var multiTouch = false
    private var scrollY = 0f
    private var scrollX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val gesture = DesktopGesture(touchSlop.toFloat())
    private val startDrag = Runnable {
        if (trackpadMode && !moved && !multiTouch) { pressed = true; pointerButton(1, true) }
    }
    private val heldKeys = mutableMapOf<Int, Int>()
    private val applyFrame: Runnable = object : Runnable {
        override fun run() {
            if (disposed) return
            val frame = frames.take() ?: return
            try {
                var image = bitmap
                if (image == null || image.width != frame.width || image.height != frame.height) {
                    image = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    bitmap = image
                    pointerX = frame.width / 2
                    pointerY = frame.height / 2
                }
                image.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                frameDirty = true
                onConnection(true, "Desktop connected")
                invalidate()
            } finally {
                frame.release()
                frames.complete()
            }
        }
    }
    private val frames: LatestFrameMailbox<Frame> = LatestFrameMailbox(
        schedule = { postOnAnimation(applyFrame) },
        cancel = { removeCallbacks(applyFrame) },
        release = { it.release() }
    )
    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        contentDescription = "Linux desktop. Touch to click or drag; use Keyboard to type."
    }
    fun connect(endpoint: DisplayEndpoint, targetFps: Int = 15) {
        if (worker != null || disposed) return
        if (endpoint.backend == DisplayBackend.NATIVE_X11) {
            connectNative(endpoint, targetFps)
            return
        }
        requestFocus()
        displayVisible = windowVisibility == VISIBLE
        worker = Thread({
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!disposed) {
                lateinit var connection: RfbClient
                connection = RfbClient(endpoint.port, endpoint.password, { w, h, pixels ->
                    frames.offer(Frame(w, h, pixels, connection))
                }, { status -> post { if (!disposed) onConnection(false, status) } }, targetFps)
                client = connection
                connection.pauseUpdates(!displayVisible)
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
    private fun connectNative(endpoint: DisplayEndpoint, fps: Int) {
        requestFocus()
        worker = Thread({
            try {
                val descriptor = runBlocking { EmbeddedX11Server.obtainConnection(endpoint.sessionId ?: error("Missing display session")) }
                    ?: error("Native display connection unavailable. Restart the instance or select Compatibility display.")
                nativeDescriptor.getAndSet(descriptor)?.close()
                if (disposed) { nativeDescriptor.getAndSet(null)?.close(); return@Thread }
                post {
                    val pendingDescriptor = nativeDescriptor.getAndSet(null) ?: return@post
                    if (disposed) { pendingDescriptor.close(); return@post }
                    try {
                        nativeWidth = endpoint.width
                        nativeHeight = endpoint.height
                        val surface = LorieView(context)
                        nativeView = surface
                        surface.isFocusable = false
                        surface.configureDesktop(nativeWidth, nativeHeight, fps)
                        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
                        updateNativeLayout()
                        surface.attachConnection(pendingDescriptor)
                        surface.setRenderingVisible(displayVisible)
                        pointerX = nativeWidth / 2
                        pointerY = nativeHeight / 2
                        onConnection(true, "Native X11 display connected")
                        postDelayed(checkNativeConnection, 1000)
                    } catch (failure: Throwable) {
                        nativeView?.release()
                        nativeView?.let { removeView(it) }
                        nativeView = null
                        onConnection(false, failure.message ?: "Native display failed. Select Compatibility display and restart.")
                    } finally { pendingDescriptor.close() }
                }
            } catch (failure: Exception) {
                post { if (!disposed) onConnection(false, failure.message ?: "Native display unavailable") }
            }
        }, "linex-native-display-connect").apply { isDaemon = true; start() }
    }

    private fun updateNativeLayout() {
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) return
        val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val w = (frameWidth * scale).toInt().coerceAtLeast(1)
        val h = (frameHeight * scale).toInt().coerceAtLeast(1)
        destination.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        nativeView?.let { surface ->
            val current = surface.layoutParams
            if (current.width != w || current.height != h)
                surface.layoutParams = LayoutParams(w, h, Gravity.CENTER)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (nativeView != null) updateNativeLayout()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = inputEnabled

    private fun sendPointer(x: Int, y: Int, mask: Int) {
        val surface = nativeView
        if (surface == null) { client?.pointer(x, y, mask); return }
        surface.pointer(x.toFloat(), y.toFloat(), 0, false, false)
        NativeDesktopInput.buttonChanges(nativeButtons, mask).forEach { change ->
            surface.pointer(x.toFloat(), y.toFloat(), change.button, change.down, false)
        }
        nativeButtons = mask and 7
    }

    private fun sendText(value: String) {
        if (nativeView != null) nativeView?.text(value) else client?.text(value)
    }

    fun disconnect() {
        releaseInput()
        disposed = true
        removeCallbacks(checkNativeConnection)
        nativeDescriptor.getAndSet(null)?.close()
        client?.close()
        worker?.interrupt()
        frames.close()
        nativeView?.release()
        nativeView?.let { removeView(it) }
        nativeView = null
        nativeWidth = 0; nativeHeight = 0
        nativeButtons = 0
        bitmap = null
        keepScreenOn = false
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        displayVisible = visibility == VISIBLE
        client?.pauseUpdates(!displayVisible)
        // View can dispatch visibility while its superclass is being constructed.
        // A client exists only after connect(), once our input state is initialized.
        nativeView?.let { surface ->
            surface.setRenderingVisible(displayVisible)
            removeCallbacks(checkNativeConnection)
            if (displayVisible) postDelayed(checkNativeConnection, 1000)
        }
        if (!displayVisible && (client != null || nativeView != null)) releaseInput()
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) releaseInput()

    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && (client != null || nativeView != null)) releaseInput()
    }
    fun releaseInput() {
        removeCallbacks(startDrag)
        nativeKeys.forEach { (key, scan) -> nativeView?.key(scan, key, false) }
        nativeKeys.clear()
        heldKeys.values.forEach { client?.key(it, false) }
        heldKeys.clear()
        if (buttons != 0 || pressed) sendPointer(pointerX, pointerY, 0)
        buttons = 0
        pressed = false
        multiTouch = false
    }
    fun pointerButton(button: Int, down: Boolean) {
        val mask = DesktopInput.buttonMask(button)
        buttons = if (down) buttons or mask else buttons and mask.inv()
        sendPointer(pointerX, pointerY, buttons)
        invalidate()
    }
    private fun wheel(horizontal: Boolean, positive: Boolean) {
        nativeView?.let { surface ->
            val (dx, dy) = NativeDesktopInput.wheelDelta(horizontal, positive)
            surface.pointer(dx, dy, 4, false, true)
            return
        }
        sendPointer(pointerX, pointerY, buttons or DesktopInput.wheelMask(horizontal, positive))
        sendPointer(pointerX, pointerY, buttons)
    }
    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }
    fun toggleKeyboard() = showKeyboard()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        if (nativeView != null) return
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(image, null, destination, paint)
        if (frameDirty) { presentedFrameCount++; frameDirty = false }
        if (trackpadMode) {
            val x = destination.left + pointerX * destination.width() / image.width
            val y = destination.top + pointerY * destination.height() / image.height
            canvas.drawCircle(x, y, 7f * resources.displayMetrics.density, cursorPaint)
        }
    }
    private fun absolutePointer(event: MotionEvent) {
        if (frameWidth <= 0 || frameHeight <= 0 || destination.isEmpty) return
        pointerX = ((event.x - destination.left) * frameWidth / destination.width()).toInt().coerceIn(0, frameWidth - 1)
        pointerY = ((event.y - destination.top) * frameHeight / destination.height()).toInt().coerceIn(0, frameHeight - 1)
    }
    private fun isMouse(event: MotionEvent) = event.isFromSource(InputDevice.SOURCE_MOUSE)
    private fun mouseButtons(event: MotionEvent): Int {
        var mask = 0
        if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or 1
        if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or 2
        if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or 4
        return mask
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return super.onGenericMotionEvent(event)
        if (!isMouse(event) || frameWidth <= 0) return super.onGenericMotionEvent(event)
        absolutePointer(event)
        buttons = mouseButtons(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vertical != 0f) repeat(kotlin.math.ceil(kotlin.math.abs(vertical)).toInt().coerceAtMost(20)) { wheel(false, vertical > 0) }
                if (horizontal != 0f) repeat(kotlin.math.ceil(kotlin.math.abs(horizontal)).toInt().coerceAtMost(20)) { wheel(true, horizontal > 0) }
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> sendPointer(pointerX, pointerY, buttons)
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return false
        if (frameWidth <= 0 || frameHeight <= 0) return false
        if (destination.isEmpty) return false
        if (isMouse(event)) {
            requestFocus()
            absolutePointer(event)
            buttons = if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0 else mouseButtons(event)
            sendPointer(pointerX, pointerY, buttons)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; lastY = event.y
                gestureStart = event.eventTime
                moved = false; multiTouch = false; scrollY = 0f; scrollX = 0f; gesture.reset()
                if (trackpadMode) postDelayed(startDrag, ViewConfiguration.getLongPressTimeout().toLong())
                else { absolutePointer(event); pressed = true; pointerButton(1, true) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                removeCallbacks(startDrag)
                if (pressed) { pointerButton(1, false); pressed = false }
                lastX = (event.getX(0) + event.getX(1)) / 2
                lastY = (event.getY(0) + event.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackpadMode && event.pointerCount >= 2) {
                    val x = (event.getX(0) + event.getX(1)) / 2
                    val y = (event.getY(0) + event.getY(1)) / 2
                    gesture.motion(x - lastX, y - lastY)
                    moved = gesture.moved
                    scrollX += x - lastX; scrollY += y - lastY
                    val step = 24f * resources.displayMetrics.density
                    while (kotlin.math.abs(scrollY) >= step) { moved = true; wheel(false, scrollY > 0); scrollY += if (scrollY > 0) -step else step }
                    while (kotlin.math.abs(scrollX) >= step) { moved = true; wheel(true, scrollX < 0); scrollX += if (scrollX > 0) -step else step }
                    lastX = x; lastY = y
                } else if (!multiTouch) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    gesture.motion(dx, dy)
                    if (gesture.moved) { moved = true; removeCallbacks(startDrag) }
                    if (trackpadMode) {
                        val scale = frameWidth / destination.width()
                        val next = gesture.relative(pointerX, pointerY, dx * scale, dy * scale, frameWidth - 1, frameHeight - 1)
                        pointerX = next.first; pointerY = next.second
                    } else absolutePointer(event)
                    sendPointer(pointerX, pointerY, buttons)
                    if (trackpadMode) invalidate()
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag)
                val tap = gesture.tapButton(multiTouch, event.actionMasked == MotionEvent.ACTION_CANCEL, pressed || moved, event.eventTime - gestureStart, ViewConfiguration.getLongPressTimeout().toLong())
                if (trackpadMode && tap != 0) pointerButton(tap, true)
                buttons = 0; pressed = false
                sendPointer(pointerX, pointerY, 0)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
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
        KeyEvent.KEYCODE_META_LEFT -> 0xffeb
        KeyEvent.KEYCODE_META_RIGHT -> 0xffec
        KeyEvent.KEYCODE_CAPS_LOCK -> 0xffe5
        KeyEvent.KEYCODE_NUM_LOCK -> 0xff7f
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0xff14
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_INSERT -> 0xff63
        KeyEvent.KEYCODE_SYSRQ -> 0xff61
        KeyEvent.KEYCODE_BREAK -> 0xff13
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff8d
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0xffaf
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0xffaa
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0xffad
        KeyEvent.KEYCODE_NUMPAD_ADD -> 0xffab
        KeyEvent.KEYCODE_NUMPAD_EQUALS -> 0xffbd
        KeyEvent.KEYCODE_NUMPAD_DOT -> DesktopInput.keypadDecimal(event.isNumLockOn != event.isShiftPressed)
        KeyEvent.KEYCODE_NUMPAD_COMMA -> if (event.isNumLockOn != event.isShiftPressed) 0xffac else 0xff9f
        KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2,
        KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5,
        KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_NUMPAD_8,
        KeyEvent.KEYCODE_NUMPAD_9 -> DesktopInput.keypadDigit(event.keyCode - KeyEvent.KEYCODE_NUMPAD_0, event.isNumLockOn != event.isShiftPressed)
        KeyEvent.KEYCODE_MENU -> 0xff67
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12 -> 0xffbe + event.keyCode - KeyEvent.KEYCODE_F1
        // Android navigation/media keys stay with the host rather than becoming guest text.
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER -> 0
        else -> DesktopInput.characterSymbol(event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_META_MASK.inv()))
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyDown(keyCode, event)
        nativeView?.let { surface ->
            if (!NativeDesktopInput.shouldForwardKey(keyCode)) return super.onKeyDown(keyCode, event)
            val sent = surface.key(event.scanCode, keyCode, true)
            if (sent) nativeKeys[keyCode] = event.scanCode
            return sent || super.onKeyDown(keyCode, event)
        }
        val key = heldKeys[keyCode] ?: symbol(event)
        if (key == 0) return super.onKeyDown(keyCode, event)
        heldKeys[keyCode] = key
        client?.key(key, true)
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        nativeKeys.remove(keyCode)?.let { scan -> return nativeView?.key(scan, keyCode, false) == true }
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
                if (!inputEnabled) return true
                text?.toString()?.let { value ->
                    if (value.codePointCount(0, value.length) > 4096) {
                        android.widget.Toast.makeText(context, "Paste up to 4,096 characters at a time", android.widget.Toast.LENGTH_LONG).show()
                    } else sendText(value)
                }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!inputEnabled) return true
                val surface = nativeView
                if (surface == null) client?.deleteText(beforeLength, afterLength)
                else {
                    repeat(beforeLength.coerceIn(0, 100)) { surface.key(0, KeyEvent.KEYCODE_DEL, true); surface.key(0, KeyEvent.KEYCODE_DEL, false) }
                    repeat(afterLength.coerceIn(0, 100)) { surface.key(0, KeyEvent.KEYCODE_FORWARD_DEL, true); surface.key(0, KeyEvent.KEYCODE_FORWARD_DEL, false) }
                }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)
        }
    }
}
