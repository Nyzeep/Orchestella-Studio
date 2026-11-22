package io.github.nyzeep.orchestella

import android.app.Activity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * W2-02: the terminal keep-screen-on preference must mount and remove the window
 * flag through one idempotent static entry point, so both mount and removal are
 * directly assertable without launching the full terminal UI.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TerminalActivityKeepScreenOnTest {

    @Test
    fun keepScreenOnFlagMountsAndRemovesIdempotently() {
        val activity = Robolectric.setupActivity(Activity::class.java)
        val window = activity.window
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

        TerminalActivity.applyTerminalKeepScreenOn(window, true)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        // Repeated application must stay idempotent.
        TerminalActivity.applyTerminalKeepScreenOn(window, true)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

        TerminalActivity.applyTerminalKeepScreenOn(window, false)
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        TerminalActivity.applyTerminalKeepScreenOn(window, false)
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
}
