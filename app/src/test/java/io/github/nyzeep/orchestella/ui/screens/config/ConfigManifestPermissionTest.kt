package io.github.nyzeep.orchestella.ui.screens.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero-new-permission gate for UI3-04.
 *
 * The config page moves existing tools around; it must not grow the permission
 * surface. The expected set is the pre-UI3 manifest, frozen here so an
 * accidental `READ_EXTERNAL_STORAGE` / `QUERY_ALL_PACKAGES` addition fails
 * before review.
 */
class ConfigManifestPermissionTest {

    private val expected = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.MANAGE_VIRTUAL_MACHINE",
        "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    )

    @Test
    fun configCenterAddsNoAndroidPermission() {
        val manifest = File("src/main/AndroidManifest.xml")
        val file = if (manifest.exists()) manifest else File("app/src/main/AndroidManifest.xml")
        assertTrue("missing AndroidManifest.xml at ${file.absolutePath}", file.isFile)

        val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "UI3-04 must not change the manifest permission surface",
            expected,
            declared,
        )
    }
}
