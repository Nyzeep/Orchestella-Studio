package io.github.nyzeep.orchestella.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 5（UI-15）的机械验收：把六项「扫描零命中」固化成测试，防止后续波次
 * 把平台弹窗、直角、原生滑块悄悄带回来。
 *
 * 为什么用源码扫描而不是 UI 测试：这些约束是「全仓不存在」的否定命题，
 * 没有页面可以渲染出「不存在」。源码扫描是唯一能覆盖全仓的形态。
 */
class ConsistencySweepTest {

    private fun sourceRoot(): File {
        val direct = File("src/main")
        return if (direct.exists()) direct else File("app/src/main")
    }

    private fun files(relative: String, suffix: String): List<File> {
        val root = File(sourceRoot(), relative)
        assertTrue("missing source dir: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(suffix) }
            .toList()
    }

    private fun offenders(files: List<File>, predicate: (String) -> Boolean): List<String> =
        files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> predicate(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }

    @Test
    fun noZeroCornerRadiusInLayouts() {
        val hits = offenders(files("res/layout", ".xml")) {
            it.contains("cornerRadius=\"0dp\"") || it.contains("cardCornerRadius=\"0dp\"")
        }
        assertTrue("0dp corners must be gone (hairline dividers excepted): $hits", hits.isEmpty())
    }

    @Test
    fun noPlatformDialogApisOutsideExemptActivities() {
        // 工单豁免：About / InstanceDetail / Desktop 只做圆角与配色对齐，不重写。
        val exempt = setOf("AboutActivity.kt", "DesktopActivity.java", "InstanceDetailActivity.kt")
        val hits = (files("java", ".kt") + files("java", ".java"))
            .filter { it.name !in exempt }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        line.contains("AlertDialog.Builder") ||
                            line.contains("MaterialAlertDialogBuilder") ||
                            line.contains("BottomSheetDialog")
                    }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
        assertTrue("platform dialog APIs must be gone outside the 3 exempt activities: $hits", hits.isEmpty())
    }

    @Test
    fun noPlatformSliderWidgets() {
        val hits = offenders(files("java", ".kt") + files("java", ".java")) { line ->
            line.trimStart().startsWith("import android.widget.SeekBar") ||
                line.trimStart().startsWith("import com.google.android.material.slider.Slider")
        }
        // OeSliderView 的 a11y className（android.widget.SeekBar::class.java.name）是
        // 无障碍元数据，不是控件使用；这里只禁 import 与实例化。
        assertTrue("SeekBar / Material Slider must not be used: $hits", hits.isEmpty())
    }

    @Test
    fun noShadowOrBorderInsideUiPackage() {
        val hits = offenders(files("java/io/github/nyzeep/orchestella/ui", ".kt")) {
            it.contains(".shadow(") || it.contains(".border(") || it.contains("BorderStroke")
        }
        assertTrue("shadow/border are only allowed on dock, overlays and error states: $hits", hits.isEmpty())
    }

    @Test
    fun legacySettingsActivityAndItsLayoutAreGone() {
        val main = sourceRoot()
        listOf(
            File(main, "java/io/github/nyzeep/orchestella/SettingsActivity.kt"),
            File(main, "res/layout/activity_settings.xml"),
        ).forEach { file ->
            assertFalse("UI-15 deleted ${file.relativeTo(main)}", file.exists())
        }
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertFalse("manifest must not register SettingsActivity", manifest.contains(".SettingsActivity"))
    }

    @Test
    fun legacyLayoutTokensAreTrimmed() {
        val tokens = File(sourceRoot(), "res/values/oe_design_tokens.xml").readText()
        assertFalse("oe_space_xxxl had zero consumers", tokens.contains("<dimen name=\"oe_space_xxxl\""))
        assertTrue("card radius token must exist for View layouts", tokens.contains("<dimen name=\"oe_corner_card\""))
    }
}
