package dev.pokemog.android

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class RetroUiTest {
    @Test fun nativeShadowBindingIsSilentAndEachActivationChangesOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (dark in listOf(false, true)) {
                var changes = 0
                val button = RetroShadowButton(instrumentation.targetContext, true, true, warmPalette(dark)) { changes++ }
                assertTrue(button.isChecked)
                assertEquals(0, changes)
                button.isChecked = false
                assertEquals(0, changes)
                button.performClick()
                assertTrue(button.isChecked)
                assertEquals(1, changes)
                val info = AccessibilityNodeInfo.obtain()
                button.onInitializeAccessibilityNodeInfo(info)
                assertEquals("android.widget.Switch", info.className)
                assertEquals("Shadow", info.contentDescription)
                assertTrue(info.isCheckable)
                assertTrue(info.isChecked)
                if (Build.VERSION.SDK_INT >= 30) assertEquals("On", info.stateDescription)
                button.performClick()
                assertFalse(button.isChecked)
                assertEquals(2, changes)
                button.isEnabled = false
                button.performClick()
                assertFalse(button.isChecked)
                assertEquals(2, changes)
                info.recycle()
            }
        }
    }

    @Test fun bundledFontLoadsOfflineAndRetainsItsLicense() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSame(retroTypeface(context), retroTypeface(context))
        val license = context.assets.open("Silkscreen-OFL.txt").bufferedReader().use { it.readText() }
        assertTrue(license.contains("Copyright 2001 The Silkscreen Project Authors"))
        assertTrue(license.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(license.contains("PERMISSION & CONDITIONS"))
    }
}
