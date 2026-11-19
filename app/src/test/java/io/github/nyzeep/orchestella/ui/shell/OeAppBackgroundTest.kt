package io.github.nyzeep.orchestella.ui.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class OeAppBackgroundTest {

    class FakeWallpaperProvider : ContentProvider() {
        companion object {
            var fileA: File? = null
            var fileB: File? = null
            val openCount = AtomicInteger(0)
        }

        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            openCount.incrementAndGet()
            val file = when (uri.lastPathSegment) {
                "a" -> fileA
                "b" -> fileB
                else -> null
            } ?: throw FileNotFoundException("missing $uri")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    @get:Rule
    val compose = createComposeRule()

    private lateinit var tempImageA: File
    private lateinit var tempImageB: File

    @Before
    fun setUp() {
        clearWallpaperCacheForTest()
        FakeWallpaperProvider.openCount.set(0)
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempImageA = File(context.cacheDir, "wallpaper_a.png").also { writeSamplePng(it) }
        tempImageB = File(context.cacheDir, "wallpaper_b.png").also { writeSamplePng(it) }
        FakeWallpaperProvider.fileA = tempImageA
        FakeWallpaperProvider.fileB = tempImageB
        Robolectric.setupContentProvider(FakeWallpaperProvider::class.java, "test.wallpaper")
    }

    @After
    fun tearDown() {
        clearWallpaperCacheForTest()
        FakeWallpaperProvider.fileA = null
        FakeWallpaperProvider.fileB = null
        tempImageA.delete()
        tempImageB.delete()
    }

    private fun writeSamplePng(target: File) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    @Test
    fun wallpaperImageBitmapIsCachedByUriAcrossThemeAndModeRecompositions() {
        var theme by mutableStateOf(ThemeCatalog.mascotDark)
        var mode by mutableStateOf<OeAppBackgroundMode>(
            OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a"),
        )
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                OeAppBackground(mode = mode)
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { FakeWallpaperProvider.openCount.get() >= 2 }
        compose.waitForIdle()
        val afterFirstDecode = FakeWallpaperProvider.openCount.get()

        // Switch themes & temporarily toggle away and back to the same URI: must hit cache without re-decoding.
        theme = ThemeCatalog.mascotLight
        compose.waitForIdle()
        mode = OeAppBackgroundMode.Gradient
        compose.waitForIdle()
        mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a")
        compose.waitForIdle()

        assertEquals(afterFirstDecode, FakeWallpaperProvider.openCount.get())
    }

    @Test
    fun differentWallpaperUriDecodesOnceAndInvalidUriFallsBackGracefully() {
        var mode by mutableStateOf<OeAppBackgroundMode>(
            OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a"),
        )
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                OeAppBackground(mode = mode)
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { FakeWallpaperProvider.openCount.get() >= 2 }
        compose.waitForIdle()
        val countAfterA = FakeWallpaperProvider.openCount.get()

        mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/b")
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) { FakeWallpaperProvider.openCount.get() > countAfterA }
        compose.waitForIdle()
        assertTrue(FakeWallpaperProvider.openCount.get() > countAfterA)

        // Invalid/revoked URI falls back without throwing.
        mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/missing")
        compose.waitForIdle()
    }

    @Test
    fun pureBlackInfoInOe3AppRequiresDarkTheme() {
        var isDark by mutableStateOf(false)
        var resolvedPureBlack = true
        var resolvedMode: OeAppBackgroundMode? = null
        compose.setContent {
            val theme = if (isDark) ThemeCatalog.mascotDark else ThemeCatalog.mascotLight
            Oe3Theme(theme = theme, pureBlack = theme.isDark && true, reduceMotion = true) {
                resolvedPureBlack = Oe3ThemeValues.info.pureBlack
                resolvedMode = OeAppBackgroundSpec.resolve(null, resolvedPureBlack)
            }
        }
        compose.waitForIdle()
        assertEquals(false, resolvedPureBlack)
        assertEquals(OeAppBackgroundMode.Gradient, resolvedMode)

        isDark = true
        compose.waitForIdle()
        assertEquals(true, resolvedPureBlack)
        assertEquals(OeAppBackgroundMode.Solid, resolvedMode)
    }
}
