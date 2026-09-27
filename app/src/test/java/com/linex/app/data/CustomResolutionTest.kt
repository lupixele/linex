package com.linex.app.data

import org.junit.Assert.*
import org.junit.Test

class CustomResolutionTest {
    @Test fun acceptsPortraitLandscapeAndBoundarySizes() {
        assertNull(CustomResolution.error("1080", "2400"))
        assertNull(CustomResolution.error("2560", "1440"))
        assertNull(CustomResolution.error("4000", "2000"))
        assertNull(CustomResolution.error("4096", "1"))
    }
    @Test fun rejectsInvalidAndOversizedInputs() {
        for ((w, h) in listOf("" to "720", "abc" to "720", "0" to "720",
            "1280" to "-1", "4097" to "720", "4000" to "2001", "2147483648" to "1")) {
            assertNotNull("$w x $h", CustomResolution.error(w, h))
        }
    }
}
