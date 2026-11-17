package io.github.nyzeep.orchestella.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI-07 reduced-motion contract for the View half of the app.
 *
 * Compose already collapses every token to 0 ms through `Oe3Motion.reduced()`,
 * but the View helpers used to start from `alpha = 0f` and rely on the animator
 * to bring the content back. With `ANIMATOR_DURATION_SCALE = 0` the animator
 * still resolves, yet any path that skips its end frame — and every caller that
 * assumes "animation will show it" — leaves the content invisible. The rule
 * here is behavioural: with animators disabled every entry helper must land on
 * the final state synchronously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrchestellaMotionReducedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun disableAnimators() {
        setDurationScale(0f)
        assertTrue("precondition: animators disabled", !ValueAnimator.areAnimatorsEnabled())
    }

    @After
    fun restoreAnimators() {
        setDurationScale(1f)
    }

    private fun setDurationScale(scale: Float) {
        // Hidden platform API: the same switch Settings.Global.ANIMATOR_DURATION_SCALE
        // drives. Reflected so the test compiles against the public SDK.
        val method = ValueAnimator::class.java
            .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(null, scale)
    }

    @Test
    fun stateChangeEnterLandsVisible() {
        val view = View(context)
        view.alpha = 0.3f
        view.translationY = 42f

        OrchestellaMotion.stateChangeEnter(view)

        assertEquals(1f, view.alpha, 0f)
        assertEquals(0f, view.translationY, 0f)
    }

    @Test
    fun expandSectionLandsVisibleAndResetsTheRise() {
        val view = View(context)
        view.visibility = View.GONE
        view.alpha = 0f
        view.translationY = 24f

        OrchestellaMotion.expandSection(view)

        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(1f, view.alpha, 0f)
        assertEquals(0f, view.translationY, 0f)
    }

    @Test
    fun collapseSectionStillRunsItsEndAction() {
        val view = View(context)
        var ended = false

        OrchestellaMotion.collapseSection(view) { ended = true }

        assertEquals(0f, view.alpha, 0f)
        assertTrue("collapse must hand control back even without animation", ended)
    }

    @Test
    fun listEntryStaggerLandsEveryChildVisible() {
        val group = LinearLayout(context)
        repeat(3) {
            val child = View(context)
            child.alpha = 0f
            child.translationY = 12f
            group.addView(child)
        }

        OrchestellaMotion.playListEntryStagger(group)

        group.children().forEach { child ->
            assertEquals(1f, child.alpha, 0f)
            assertEquals(0f, child.translationY, 0f)
        }
    }

    @Test
    fun dashboardEntryLandsAtFullScale() {
        val group = LinearLayout(context)
        val child = View(context)
        child.scaleX = 0.5f
        child.scaleY = 0.5f
        group.addView(child)

        OrchestellaMotion.playDashboardEntry(group)

        assertEquals(1f, child.alpha, 0f)
        assertEquals(1f, child.scaleX, 0f)
        assertEquals(1f, child.scaleY, 0f)
    }

    @Test
    fun sectionStaggerLandsChildrenVisibleWithoutALayoutAnimation() {
        val group = LinearLayout(context)
        repeat(2) { group.addView(View(context).apply { alpha = 0f }) }

        OrchestellaMotion.playSectionStagger(group)

        group.children().forEach { child -> assertEquals(1f, child.alpha, 0f) }
    }

    private fun LinearLayout.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }
}
