package io.github.nyzeep.orchestella.ui.design

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import io.github.nyzeep.orchestella.ui.compose.OrchestellaComposeTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Debug-only host for the Oe v2 acceptance board ([OeBoard]).
 *
 * <p>Why this exists: the board was reachable only through Android Studio's
 * Compose preview panes, so a device build shipped the design system with no
 * way to look at it. This activity renders the exact same composition with the
 * user's current theme, corner radius and in-app text scale, so the board can
 * be judged on real hardware next to the pages it is meant to serve.
 *
 * <p>Lives in the debug source set and is declared only in
 * `src/debug/AndroidManifest.xml`; release builds neither ship nor expose it.
 */
class OeBoardActivity : AppCompatActivity() {

    private lateinit var store: ThemeStore
    private val themeId = mutableStateOf("")
    private val cornerDp = mutableIntStateOf(16)
    private val textScale = mutableFloatStateOf(1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ThemeStore(this)
        syncFromStore()
        val composeView = ComposeView(this)
        composeView.setContent {
            val theme = ThemeCatalog.resolve(themeId.value)
            OrchestellaComposeTheme(theme, cornerDp.intValue, textScale.floatValue) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // targetSdk 36 puts the window edge to edge on Android 15+, so the status
                    // bar and gesture inset have to be consumed here or the page title lands
                    // under the clock.
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        OeBoard(theme = theme, textScale = textScale.floatValue)
                    }
                }
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        // The appearance sub-page can move these while the board sits in the back stack.
        if (::store.isInitialized) syncFromStore()
    }

    private fun syncFromStore() {
        themeId.value = store.themeId()
        cornerDp.intValue = store.cornerRadiusDp()
        textScale.floatValue = store.textScaleFactor()
    }
}
