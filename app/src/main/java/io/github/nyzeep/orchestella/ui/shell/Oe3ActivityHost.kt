package io.github.nyzeep.orchestella.ui.shell

import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

private data class HostAppearance(
    val theme: AppTheme,
    val pureBlack: Boolean,
    val monoBody: Boolean,
    val textScale: Float,
    val reduceMotion: Boolean,
    val cornerRadiusDp: Int?,
    val surfaceAlpha: Float,
    val backgroundUri: String?,
)

private fun readAppearance(store: ThemeStore): HostAppearance {
    val theme = store.resolve()
    return HostAppearance(
        theme = theme,
        pureBlack = theme.isDark && store.uiPureBlack(),
        monoBody = store.uiFontChoice() == "mono",
        textScale = store.textScaleFactor(),
        reduceMotion = !ValueAnimator.areAnimatorsEnabled(),
        cornerRadiusDp = store.cornerRadiusDpOrNull(),
        surfaceAlpha = OeAppBackgroundSpec.surfaceAlpha(store.uiSurfaceOpacityPercent()),
        backgroundUri = store.backgroundUri(),
    )
}

/**
 * Installs edge-to-edge transparent system bars and mounts a standalone
 * Compose screen inside [Oe3Theme] + [OeAppBackground], keeping live
 * [ThemeStore] updates in sync without repeating boilerplate per Activity.
 */
internal fun ComponentActivity.setOe3HostContent(
    content: @Composable () -> Unit,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    @Suppress("DEPRECATION")
    window.statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    window.navigationBarColor = Color.TRANSPARENT

    val store = ThemeStore(this)
    setContent {
        var appearance by remember { mutableStateOf(readAppearance(store)) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(store, lifecycleOwner) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                appearance = readAppearance(store)
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        store.addOnThemeChangeListener(listener)
                        appearance = readAppearance(store)
                    }
                    Lifecycle.Event.ON_STOP -> store.removeOnThemeChangeListener(listener)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                store.removeOnThemeChangeListener(listener)
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        val current = appearance
        Oe3Theme(
            theme = current.theme,
            pureBlack = current.pureBlack,
            monoBody = current.monoBody,
            textScale = current.textScale,
            reduceMotion = current.reduceMotion,
            cornerRadiusDp = current.cornerRadiusDp,
        ) {
            val isDark = current.theme.isDark
            DisposableEffect(isDark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
                onDispose {}
            }
            CompositionLocalProvider(
                LocalOe3PageBottomPadding provides 0.dp,
                LocalOeSurfaceAlpha provides current.surfaceAlpha,
            ) {
                Box(Modifier.fillMaxSize()) {
                    OeAppBackground(
                        mode = OeAppBackgroundSpec.resolve(current.backgroundUri, current.pureBlack),
                    )
                    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                        content()
                    }
                }
            }
        }
    }
}
