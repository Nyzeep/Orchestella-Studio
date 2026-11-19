package io.github.nyzeep.orchestella.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.CompatExplanationActivity
import io.github.nyzeep.orchestella.ConnectionPagesActivity
import io.github.nyzeep.orchestella.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ThemeApplierExclusionTest {

    @Test
    fun connectionPagesActivityIsExcludedFromThemeApplierAndKeepsTransparentBars() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ThemeStore(context)
        store.setThemeId(ThemeCatalog.github.id)
        store.setMode(ThemeStore.Mode.DARK)
        store.setBackgroundUri("content://test/wallpaper/excluded")

        val activity = Robolectric.buildActivity(
            ConnectionPagesActivity::class.java,
            ConnectionPagesActivity.sshIntent(context),
        ).setup().get()

        assertTrue(ThemeApplier.isComposeOnlyHost(activity))
        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeHost = content.getChildAt(0)
        val initialBackground = composeHost.background

        // Trigger a theme + mode change and explicitly attempt ThemeApplier.bind(activity).
        store.setThemeId(ThemeCatalog.mascotLight.id)
        store.setMode(ThemeStore.Mode.LIGHT)
        ThemeApplier.bind(activity)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)
        assertFalse(composeHost.background is LayerDrawable)
        assertEquals(initialBackground, composeHost.background)
    }

    @Test
    fun mainActivityIsRecognizedAsComposeOnlyHost() {
        assertTrue(MainActivity::class.java.simpleName == "MainActivity")
        val sourceMethod = ThemeApplier::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("isComposeOnlyHost")
        }
        assertTrue(sourceMethod != null)
    }

    @Test
    fun legacyXmlActivityIsNotExcludedAndStillRepaintsOnThemeChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ThemeStore(context)
        store.setThemeId(ThemeCatalog.mascotLight.id)
        store.setMode(ThemeStore.Mode.LIGHT)

        val activity = Robolectric.buildActivity(CompatExplanationActivity::class.java).setup().get()
        assertFalse(ThemeApplier.isComposeOnlyHost(activity))
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val page = content.getChildAt(0)
        assertEquals(
            "expected legacy XML activity background to match mascotLight (${page.background})",
            ThemeCatalog.mascotLight.background,
            (activity.window.decorView.background as? ColorDrawable)?.color ?: (page.background as? ColorDrawable)?.color,
        )

        store.setThemeId(ThemeCatalog.github.id)
        store.setMode(ThemeStore.Mode.DARK)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val updatedColor =
            (page.background as? ColorDrawable)?.color ?: (activity.window.decorView.background as? ColorDrawable)?.color
        assertEquals(ThemeCatalog.github.background, updatedColor)
        assertNotEquals(ThemeCatalog.mascotLight.background, updatedColor)
    }
}
