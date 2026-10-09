package com.linex.vm.console

import org.junit.Assert.*
import org.junit.Test

class DesktopFrameContentTest {
    @Test fun blackAndCursorOnlyFramesDoNotHideStartupProgress() {
        val pixels = IntArray(1920 * 1080) { 0xff000000.toInt() }
        assertFalse(DesktopFrameContent.visible(pixels, 1920, 1080))
        for (y in 530..550) for (x in 950..970) pixels[y * 1920 + x] = -1
        assertFalse(DesktopFrameContent.visible(pixels, 1920, 1080))
    }
    @Test fun darkDesktopPanelCountsAsContentAt720And1080() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080)) {
            val pixels = IntArray(width * height) { 0xff000000.toInt() }
            for (index in 0 until width * 24) pixels[index] = 0xff101010.toInt()
            assertTrue(DesktopFrameContent.visible(pixels, width, height))
        }
    }
    @Test fun invalidFrameDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DesktopFrameContent.visible(IntArray(4), 3, 3) }
    }
}
