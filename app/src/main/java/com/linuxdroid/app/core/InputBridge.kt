package com.linuxdroid.app.core

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

enum class TouchInputMode {
    TRACKPAD_EMULATION,
    DIRECT_TOUCH
}

interface X11InputListener {
    fun onPointerMotion(x: Float, y: Float, isRelative: Boolean)
    fun onPointerButton(buttonIndex: Int, isDown: Boolean)
    fun onPointerScroll(distanceY: Float)
    fun onKey(keyCode: Int, isDown: Boolean, metaState: Int)
}

class InputBridge(private var listener: X11InputListener? = null) {

    var touchMode: TouchInputMode = TouchInputMode.TRACKPAD_EMULATION
    var isPointerCaptureActive: Boolean = false
        private set

    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var isDragging: Boolean = false

    fun setInputListener(listener: X11InputListener) {
        this.listener = listener
    }

    /**
     * Called when hardware mouse is plugged in (USB-C OTG or Bluetooth).
     * Locks pointer inside Android window and passes raw relative coordinates.
     */
    fun attachPointerCapture(view: View) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.requestPointerCapture()
            isPointerCaptureActive = true
        }
    }

    fun releasePointerCapture(view: View) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.releasePointerCapture()
            isPointerCaptureActive = false
        }
    }

    /**
     * Intercepts Android MotionEvents (Touch, Stylus, or Hardware Mouse)
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // Check if event is from a hardware mouse
        val isMouse = event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
        if (isMouse && isPointerCaptureActive) {
            val relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            listener?.onPointerMotion(relX, relY, isRelative = true)

            // Handle Mouse Buttons
            val buttonState = event.buttonState
            val isLeftDown = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0
            val isRightDown = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            val isMiddleDown = (buttonState and MotionEvent.BUTTON_TERTIARY) != 0

            listener?.onPointerButton(1, isLeftDown)
            listener?.onPointerButton(3, isRightDown)
            listener?.onPointerButton(2, isMiddleDown)

            // Scroll wheel
            if (action == MotionEvent.ACTION_SCROLL) {
                val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                listener?.onPointerScroll(scroll)
            }
            return true
        }

        // Handle Touchscreen Inputs
        when (touchMode) {
            TouchInputMode.DIRECT_TOUCH -> {
                // Absolute 1:1 coordinates (Phosh / Ubuntu Touch)
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        listener?.onPointerMotion(event.x, event.y, isRelative = false)
                        listener?.onPointerButton(1, true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        listener?.onPointerMotion(event.x, event.y, isRelative = false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        listener?.onPointerButton(1, false)
                    }
                }
                return true
            }

            TouchInputMode.TRACKPAD_EMULATION -> {
                // Virtual Trackpad: Relative drag moves cursor, taps trigger clicks
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                            isDragging = true
                            listener?.onPointerMotion(dx, dy, isRelative = true)
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Single tap = Left click
                            listener?.onPointerButton(1, true)
                            listener?.onPointerButton(1, false)
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.pointerCount == 2) {
                            // Two-finger tap = Right click
                            listener?.onPointerButton(3, true)
                            listener?.onPointerButton(3, false)
                        }
                    }
                }
                return true
            }
        }
    }

    /**
     * Intercepts hardware keys before Android consumes them (Super/Windows key, Alt+Tab, Ctrl shortcuts).
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        listener?.onKey(event.keyCode, isDown, event.metaState)
        return true
    }
}
