package com.linex.app.ui.wayland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.wayland.WaylandCompositor

class SessionClipboard(context: Context, private val hasFocus: () -> Boolean) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var shared: String? = null
    private var echo: String? = null

    private val androidListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!active || !hasFocus()) return@OnPrimaryClipChangedListener
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.text?.toString() ?: return@OnPrimaryClipChangedListener
        if (text == echo) return@OnPrimaryClipChangedListener
        shared = text
        WaylandCompositor.setClipboardText(text)
    }

    private val guestListener = object : WaylandCompositor.ClipboardListener {
        override fun onGuestClipboardText(text: String) {
            handler.post {
                if (active && hasFocus() && WaylandCompositor.isClipboardListener(this)) {
                    val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    shared = text
                    if (current != text) {
                        echo = text
                        clipboard.setPrimaryClip(ClipData.newPlainText("Linex", text))
                    }
                }
            }
        }
    }

    fun start() {
        if (active) return
        active = true
        clipboard.addPrimaryClipChangedListener(androidListener)
        WaylandCompositor.setClipboardListener(guestListener)
        // Push initial clipboard text if present
        val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (!current.isNullOrEmpty()) {
            shared = current
            WaylandCompositor.setClipboardText(current)
        }
    }

    fun stop() {
        if (!active) return
        active = false
        clipboard.removePrimaryClipChangedListener(androidListener)
        WaylandCompositor.clearClipboardListener(guestListener)
    }
}
