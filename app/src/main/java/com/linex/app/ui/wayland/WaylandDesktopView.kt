package com.linex.app.ui.wayland

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor

/**
 * Fullscreen hardware-accelerated Wayland surface view.
 * Presents Wayland client surfaces directly onto an Android Surface via Vulkan/EGL zero-copy.
 */
class WaylandDesktopView(
    context: Context,
    private val xdgRuntimeDir: String,
    private val outputWidth: Int = 1920,
    private val outputHeight: Int = 1080,
    private val targetFps: Int = 60
) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "WaylandDesktopView"
    }

    var onFirstFrame: () -> Unit = {}
    var inputEnabled: Boolean = true
    private var surfaceReady = false

    private val clipboardSync = SessionClipboard(context) { hasWindowFocus() && isShown }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated: ${holder.surface}")
        val refreshHz = try {
            display?.refreshRate ?: 60f
        } catch (_: Throwable) {
            60f
        }

        WaylandCompositor.setFirstFrameListener {
            post {
                Log.i(TAG, "Wayland first frame presented")
                onFirstFrame()
            }
        }

        CompositorHost.startOrAttach(
            context = context,
            surface = holder.surface,
            xdgRuntimeDir = xdgRuntimeDir,
            outputWidth = if (outputWidth > 0) outputWidth else width.coerceAtLeast(1280),
            outputHeight = if (outputHeight > 0) outputHeight else height.coerceAtLeast(720),
            refreshHz = refreshHz,
            fpsLimit = targetFps
        )

        clipboardSync.start()
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        Log.i(TAG, "surfaceChanged: ${w}x$h")
        if (surfaceReady && w > 0 && h > 0) {
            CompositorHost.resize(holder.surface)
            WaylandCompositor.nativeResizeOutput(w, h)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")
        surfaceReady = false
        clipboardSync.stop()
        WaylandCompositor.clearFirstFrameListener(null)
        CompositorHost.detach(holder.surface)
    }

    fun release() {
        clipboardSync.stop()
        WaylandCompositor.clearFirstFrameListener(null)
        if (surfaceReady) {
            CompositorHost.detach(holder.surface)
            surfaceReady = false
        }
    }

    private fun isMouse(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || width <= 0 || height <= 0) return super.onGenericMotionEvent(event)

        if (isMouse(event)) {
            val x = InputSpace.x(event.x, width)
            val y = InputSpace.y(event.y, height)

            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    if (v != 0f) {
                        WaylandCompositor.nativeSendSceneInput(
                            WaylandCompositor.SCENE_WHEEL,
                            0,
                            (v * WaylandCompositor.WHEEL_STEP_UNITS).toInt()
                        )
                    }
                    if (h != 0f) {
                        WaylandCompositor.nativeSendSceneInput(
                            WaylandCompositor.SCENE_WHEEL,
                            1,
                            (h * WaylandCompositor.WHEEL_STEP_UNITS).toInt()
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    WaylandCompositor.nativeSendPointer(1, x, y)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || width <= 0 || height <= 0) return false
        requestFocus()

        if (isMouse(event)) {
            val x = InputSpace.x(event.x, width)
            val y = InputSpace.y(event.y, height)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> WaylandCompositor.nativeSendPointer(0, x, y)
                MotionEvent.ACTION_MOVE -> WaylandCompositor.nativeSendPointer(1, x, y)
                MotionEvent.ACTION_UP -> WaylandCompositor.nativeSendPointer(2, x, y)
                MotionEvent.ACTION_CANCEL -> WaylandCompositor.nativeSendPointer(2, x, y)
            }
            return true
        }

        // Touch event dispatch
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 2
            MotionEvent.ACTION_CANCEL -> 3
            else -> return super.onTouchEvent(event)
        }

        val index = event.actionIndex
        if (action == 1) { // Motion applies to all active pointers
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                val x = InputSpace.x(event.getX(i), width)
                val y = InputSpace.y(event.getY(i), height)
                WaylandCompositor.nativeSendTouch(1, pid, x, y)
            }
        } else {
            val pid = event.getPointerId(index)
            val x = InputSpace.x(event.getX(index), width)
            val y = InputSpace.y(event.getY(index), height)
            WaylandCompositor.nativeSendTouch(action, pid, x, y)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyDown(keyCode, event)
        val evdev = EvdevKeys.fromKeyCode(keyCode)
        if (evdev > 0) {
            WaylandCompositor.nativeSendKey(evdev, 1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyUp(keyCode, event)
        val evdev = EvdevKeys.fromKeyCode(keyCode)
        if (evdev > 0) {
            WaylandCompositor.nativeSendKey(evdev, 0)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!inputEnabled) return true
                text?.toString()?.let { str ->
                    WaylandCompositor.textInputCommit(str)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!inputEnabled) return true
                WaylandCompositor.textInputDelete(beforeLength, afterLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)
        }
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
}
