package com.linex.app.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Typed private serial commands; credentials are never part of diagnostic text. */
object VmGuestControl {
    fun launch(session: String, password: String, width: Int, height: Int, fps: Int,
               epochSeconds: Long = System.currentTimeMillis() / 1000): String {
        require(session.matches(Regex("[0-9a-f]{32}"))) { "Invalid VM session" }
        require(password.matches(Regex("[A-Za-z0-9_-]{8}"))) { "Invalid console credential" }
        require(width in 640..4096 && height in 480..4096 && width.toLong() * height <= 8_000_000) {
            "Choose a desktop size from 640×480 up to 4096 pixels per side and 8 megapixels"
        }
        require(fps in DesktopFrameRate.options) { "Unsupported desktop FPS" }
        require(epochSeconds in 1_700_000_000L..4_102_444_800L) { "Android date is outside the supported guest clock range" }
        return buildJsonObject {
            put("command", "launch"); put("session", session); put("password", password)
            put("width", width); put("height", height); put("fps", fps)
            put("epochSeconds", epochSeconds)
        }.toString() + "\n"
    }

    fun stop(session: String): String {
        require(session.matches(Regex("[0-9a-f]{32}"))) { "Invalid VM session" }
        return buildJsonObject { put("command", "stop"); put("session", session) }.toString() + "\n"
    }
}
