package io.github.nyzeep.orchestella.ui.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private lateinit var tempImageA: File
    private lateinit var tempImageB: File

    @Before
    fun setUp() {
        clearWallpaperCacheForTest()
        WallpaperStore.resetForTest()
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
        WallpaperStore.resetForTest()
        FakeWallpaperProvider.fileA = null
        FakeWallpaperProvider.fileB = null
        tempImageA.delete()
        tempImageB.delete()
    }

    private fun writeSamplePng(target: File) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    private fun showsWallpaper(): Boolean = compose.runOnIdle {
        val root = compose.activity.window.decorView
        val sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sample)
            canvas.translate(-root.width / 2f, -root.height / 2f)
            root.draw(canvas)
            val pixel = sample.getPixel(0, 0)
            Color.green(pixel) - Color.red(pixel) > 24
        } finally {
            sample.recycle()
        }
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
        compose.waitUntil(timeoutMillis = 5_000) { showsWallpaper() }
        val afterFirstDecode = FakeWallpaperProvider.openCount.get()

        // Repainting a theme does not touch the provider. Reattachment checks availability without decoding.
        compose.runOnIdle { Snapshot.withMutableSnapshot { theme = ThemeCatalog.mascotLight } }
        compose.waitForIdle()
        assertEquals(afterFirstDecode, FakeWallpaperProvider.openCount.get())
        compose.runOnIdle { Snapshot.withMutableSnapshot { mode = OeAppBackgroundMode.Gradient } }
        compose.waitUntil(timeoutMillis = 5_000) { !showsWallpaper() }
        compose.runOnIdle { Snapshot.withMutableSnapshot { mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a") } }
        compose.waitUntil(timeoutMillis = 5_000) { FakeWallpaperProvider.openCount.get() > afterFirstDecode }

        assertEquals(afterFirstDecode + 1, FakeWallpaperProvider.openCount.get())
        assertTrue(showsWallpaper())

        compose.runOnIdle { Snapshot.withMutableSnapshot { mode = OeAppBackgroundMode.Gradient } }
        compose.waitUntil(timeoutMillis = 5_000) { !showsWallpaper() }
        assertTrue(tempImageA.delete())
        compose.runOnIdle { Snapshot.withMutableSnapshot { mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a") } }
        compose.waitUntil(timeoutMillis = 5_000) {
            FakeWallpaperProvider.openCount.get() >= afterFirstDecode + 2 && !showsWallpaper()
        }
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
    fun concurrentMountsShareOneDecodeAndOneProbePerAttachWave() {
        // W2-03 壁纸单源：壳层最多同时挂 3 个 OeAppBackground（根背景 + 栈
        // Under/Moving 底片）。同一挂载波次内：缓存未命中只解码一次（mutex
        // 收敛），缓存命中只探活一次（挂载代计数器：进锁前快照 vs 锁内当前，
        // 与锁序无关地保证同波次恰一次探活）。
        var mode by mutableStateOf<OeAppBackgroundMode>(
            OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a"),
        )
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.mascotDark, reduceMotion = true) {
                Column {
                    OeAppBackground(mode = mode)
                    OeAppBackground(mode = mode)
                    OeAppBackground(mode = mode)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { showsWallpaper() }
        compose.waitForIdle()
        // 并发 3 实例首挂 = 一次解码（bounds + decode 共两次流打开），不重复。
        assertEquals("并发首挂必须单次解码", 2, FakeWallpaperProvider.openCount.get())

        // 缓存命中路径：模式切走再切回（同一新波次）→ 恰好一次探活。
        compose.runOnIdle { mode = OeAppBackgroundMode.Gradient }
        compose.waitForIdle()
        compose.runOnIdle { mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a") }
        compose.waitUntil(timeoutMillis = 5_000) { showsWallpaper() }
        compose.waitForIdle()
        assertEquals("缓存命中重挂必须恰好一次探活", 3, FakeWallpaperProvider.openCount.get())

        // 新波次再挂 → 再探一次；两波之间不得共享探活结论。
        compose.runOnIdle { mode = OeAppBackgroundMode.Gradient }
        compose.waitForIdle()
        compose.runOnIdle { mode = OeAppBackgroundMode.Wallpaper("content://test.wallpaper/a") }
        compose.waitUntil(timeoutMillis = 5_000) { showsWallpaper() }
        compose.waitForIdle()
        assertEquals("每个新挂载波次恰好一次探活", 4, FakeWallpaperProvider.openCount.get())
    }

    @Test
    fun pureBlackInfoInOe3AppRequiresDarkTheme() {
        var isDark by mutableStateOf(false)
        var resolvedPureBlack = true
        var resolvedMode: OeAppBackgroundMode? = null
        compose.setContent {
            val theme = if (isDark) ThemeCatalog.mascotDark else ThemeCatalog.mascotLight
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = theme,
                pureBlack = true,
                reduceMotion = true,
                pages = Oe3Pages(workstation = {
                    resolvedPureBlack = Oe3ThemeValues.info.pureBlack
                    resolvedMode = OeAppBackgroundSpec.resolve(null, resolvedPureBlack)
                }),
            )
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
