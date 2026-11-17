package io.github.nyzeep.orchestella.ui.screens.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded terminal background for the Theme Studio preview. */
internal sealed interface TerminalBackgroundState {
    /** No image chosen: the view keeps the theme's solid terminal color. */
    data object None : TerminalBackgroundState
    data object Loading : TerminalBackgroundState
    data class Loaded(val bitmap: Bitmap) : TerminalBackgroundState
    /** The URI cannot be opened or decoded (revoked grant, deleted file, corrupt image). */
    data object Failed : TerminalBackgroundState
}

/**
 * Decodes [uri] off the main thread, once per URI. Recomposing with the same URI
 * reuses the decoded bitmap; changing the URI restarts the producer; leaving the
 * composition cancels it, so a late decode can never paint a stale image.
 */
@Composable
internal fun rememberTerminalBackground(uri: String?): State<TerminalBackgroundState> {
    val context = LocalContext.current
    return produceState<TerminalBackgroundState>(
        initialValue = if (uri == null) TerminalBackgroundState.None else TerminalBackgroundState.Loading,
        key1 = uri,
    ) {
        if (uri == null) {
            value = TerminalBackgroundState.None
            return@produceState
        }
        value = TerminalBackgroundState.Loading
        val bitmap = withContext(Dispatchers.IO) { decodeTerminalBackground(context, uri) }
        value = if (bitmap != null) {
            TerminalBackgroundState.Loaded(bitmap)
        } else {
            TerminalBackgroundState.Failed
        }
    }
}

/**
 * Bounded decode with a power-of-two sample size: the preview is a crop, so a
 * full-resolution photo would only cost memory. Returns null instead of throwing
 * when the grant is gone or the bytes are not an image.
 */
internal fun decodeTerminalBackground(
    context: Context,
    uri: String,
    maxDimensionPx: Int = 1440,
): Bitmap? {
    return try {
        if (uri.isBlank()) return null
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimensionPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(parsed)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
    } catch (e: Exception) {
        // Revoked grant, deleted file, non-image bytes: the caller falls back to solid colour.
        null
    }
}
