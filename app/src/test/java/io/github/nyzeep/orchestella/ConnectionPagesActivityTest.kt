package io.github.nyzeep.orchestella

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * UI-13 host contract: in the legacy Activity world the visible back button and
 * the system back key both finish the page. The page receives one `onBack`
 * lambda; this Activity passes `closePage()` (finish), the shell passes
 * `OeBackStack.pop()`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConnectionPagesActivityTest {

    private fun launchSshPage(): ConnectionPagesActivity {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ConnectionPagesActivity.sshIntent(context)
        return Robolectric.buildActivity(ConnectionPagesActivity::class.java, intent).setup().get()
    }

    @Test
    fun visibleBackActionFinishesTheHostActivity() {
        val activity = launchSshPage()
        activity.closePage()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun systemBackFinishesTheHostActivity() {
        val activity = launchSshPage()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }
}
