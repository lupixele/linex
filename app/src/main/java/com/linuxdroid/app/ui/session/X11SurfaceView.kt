package com.linuxdroid.app.ui.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.linuxdroid.app.core.InputBridge
import com.linuxdroid.app.core.X11InputListener

class X11SurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, X11InputListener {

    companion object {
        private const val TAG = "X11SurfaceView"

        init {
            try {
                System.loadLibrary("linuxdroid_engine")
                Log.i(TAG, "Native linuxdroid_engine library loaded for X11SurfaceView.")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native linuxdroid_engine library not loaded yet; JNI stubs active", e)
            }
        }
    }

    private var inputBridge: InputBridge = InputBridge()
    private val paint = Paint().apply {
        color = Color.DKGRAY
        isAntiAlias = true
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        inputBridge.setInputListener(this)
    }

    fun bindInputBridge(bridge: InputBridge) {
        this.inputBridge = bridge
        bridge.setInputListener(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawPlaceholder(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Here the native JNI xserver attaches to the ANativeWindow
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Native X11 surface detached
    }

    private fun drawPlaceholder(holder: SurfaceHolder) {
        val canvas: Canvas? = holder.lockCanvas()
        if (canvas != null) {
            canvas.drawColor(Color.parseColor("#18181B"))
            paint.color = Color.WHITE
            paint.textSize = 36f
            canvas.drawText("Linux X11 Display Surface Active", 60f, 120f, paint)
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return inputBridge.handleMotionEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputBridge.handleMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputBridge.handleKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputBridge.handleKeyEvent(event)
    }

    // X11InputListener interface implementation
    override fun onPointerMotion(x: Float, y: Float, isRelative: Boolean) {
        try {
            nativePointerMotion(x, y, isRelative)
        } catch (e: UnsatisfiedLinkError) {
            // Native JNI stub fallback
        }
    }

    override fun onPointerButton(buttonIndex: Int, isDown: Boolean) {
        try {
            nativePointerButton(buttonIndex, isDown)
        } catch (e: UnsatisfiedLinkError) {
            // Native JNI stub fallback
        }
    }

    override fun onPointerScroll(distanceY: Float) {
        try {
            nativePointerScroll(distanceY)
        } catch (e: UnsatisfiedLinkError) {
            // Native JNI stub fallback
        }
    }

    override fun onKey(keyCode: Int, isDown: Boolean, metaState: Int) {
        try {
            nativeKeyEvent(keyCode, isDown, metaState)
        } catch (e: UnsatisfiedLinkError) {
            // Native JNI stub fallback
        }
    }

    // Native JNI method declarations
    private external fun nativePointerMotion(x: Float, y: Float, isRelative: Boolean)
    private external fun nativePointerButton(buttonIndex: Int, isDown: Boolean)
    private external fun nativePointerScroll(distanceY: Float)
    private external fun nativeKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int)
}
