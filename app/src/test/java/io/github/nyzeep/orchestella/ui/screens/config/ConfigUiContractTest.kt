package io.github.nyzeep.orchestella.ui.screens.config

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level gates for the parts a plain JVM test cannot render (Robolectric
 * is owned by UI3-11). They encode the ticket's structural acceptance:
 * group headers live outside cards, rows keep the 48dp floor, chevrons carry a
 * description, and both section dispatch tables stay complete.
 */
class ConfigUiContractTest {

    private fun source(relative: String): String {
        val direct = File(relative)
        val file = if (direct.exists()) direct else File("app/$relative")
        assertTrue("missing source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun groupHeaderIsASeparateLazyItemBeforeTheCard() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        val headerItem = text.indexOf("item(key = \"config-header:")
        val sectionHeader = text.indexOf("Oe3SectionHeader(")
        val cardItem = text.indexOf("item(key = \"config-card:")
        assertTrue("missing group header item", headerItem >= 0)
        assertTrue("missing Oe3SectionHeader call", sectionHeader >= 0)
        assertTrue("missing group card item", cardItem >= 0)
        assertTrue(
            "section header must sit between its own item and the card item (outside the card)",
            headerItem < sectionHeader && sectionHeader < cardItem,
        )
    }

    @Test
    fun rowsUseTheOe3PrimitivesWithTheir48DpFloor() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        assertTrue(text.contains("Oe3ListRow("))
        assertTrue(text.contains("Oe3SwitchRow("))
        assertEquals(48.dp, Oe3Sizes.minTouchTarget)
        assertTrue(Oe3Sizes.interactiveMinHeight(Oe3Spacing.rowHeight) >= 48.dp)
    }

    @Test
    fun chevronCarriesAContentDescription() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        assertTrue(text.contains("contentDescription = description"))
        assertTrue(text.contains("config_chevron_desc"))
    }

    @Test
    fun everyDirectoryRowCarriesAUniqueTestTag() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        assertTrue(
            "directory rows must expose a stable testTag for semantics tests",
            text.contains("testTag(\"config:route:\${entry.route.id}\")"),
        )
        assertTrue(text.contains("testTag(\"config:option:"))
    }

    @Test
    fun metaInfoTextUsesTheThirteenEighteenPageLocalStyle() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        assertTrue(
            "MetaTextStyle must be the 13/18 page-local style (spec §2.3), not the mono role",
            text.contains("private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)"),
        )
        assertEquals(
            "four meta positions pinned to 13/18: page meta, update badge, options label, choice row value",
            4,
            Regex("style = MetaTextStyle").findAll(text).count(),
        )
        assertFalse(
            "ConfigScreen keeps no Caption: all four Caption usages were meta info and got upgraded",
            text.contains("Oe3TextRole.Caption"),
        )
    }

    @Test
    fun choiceDialogPlaysTheOe3DialogEnterMotion() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        assertTrue(
            "ChoiceDialog content layer must be wrapped in Oe3DialogEnterMotion (spec §2.5)",
            text.contains("Oe3DialogEnterMotion {"),
        )
    }

    @Test
    fun newSettingsPageUsesNoCardOutlineOrDepth() {
        val layout = source("src/main/res/layout/layout_settings_section_system.xml")
        listOf("MaterialCardView", "strokeWidth", "cardElevation").forEach { token ->
            assertFalse("system section must not use $token", layout.contains(token))
        }
    }

    @Test
    fun configScreenUsesToneNotOutlineOrDepth() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/config/ConfigScreen.kt")
        listOf("MaterialCardView", "shadowElevation", "CardDefaults").forEach { token ->
            assertFalse("ConfigScreen must not use $token", text.contains(token))
        }
        // Icon paths may stroke (spec §6); card surfaces may not.
        text.lineSequence()
            .filter { it.contains("strokeWidth") && !it.contains("drawLine") }
            .forEach { line ->
                throw AssertionError("card/surface stroke is forbidden: $line")
            }
        assertFalse("ConfigScreen must not draw card borders", text.contains("Modifier.border("))
    }

    @Test
    fun settingsActivityDispatchesEveryDestination() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/SettingsActivity.kt")
        val destinations = listOf(
            "SettingsSection",
            "ThemeStudio",
            "SshManager",
            "PortForwarding",
            "Logs",
            "Backup",
            "Import",
            "Export",
            "UpdateCheck",
            "Licenses",
            "AboutPage",
        )
        destinations.forEach { name ->
            assertTrue(
                "SettingsActivity.navigate must route ConfigDestination.$name",
                text.contains("ConfigDestination.$name"),
            )
        }
    }

    @Test
    fun settingsSubActivityHandlesEverySectionKey() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/SettingsSubActivity.kt")
        val bindings = mapOf(
            "SECTION_MIRROR" to "bindMirror",
            "SECTION_ABOUT" to "bindAbout",
            "SECTION_ADVANCED" to "bindAdvanced",
            "SECTION_SYSTEM" to "bindSystem",
        )
        bindings.forEach { (constant, binder) ->
            assertTrue(
                "SettingsSubActivity is missing `$constant -> $binder`",
                text.contains("$constant -> $binder"),
            )
        }
        // One mapping: the constants delegate to ConfigSections instead of
        // duplicating string literals at both ends of the switch.
        val delegations = mapOf(
            "SECTION_MIRROR" to "ConfigSections.MIRROR",
            "SECTION_ABOUT" to "ConfigSections.ABOUT",
            "SECTION_ADVANCED" to "ConfigSections.ADVANCED",
            "SECTION_SYSTEM" to "ConfigSections.SYSTEM",
        )
        delegations.forEach { (constant, sectionSource) ->
            assertTrue(
                "$constant must delegate to $sectionSource",
                text.contains("const val $constant = $sectionSource"),
            )
        }
        // v4 UI-10：旧外观子页连同分支一起删除，不再有双入口。
        assertFalse(
            "the legacy appearance section must be gone",
            text.contains("SECTION_APPEARANCE") || text.contains("bindAppearance"),
        )
    }

    @Test
    fun oldFourRowDirectoryIsGoneAndComposeHostIsPresent() {
        val layout = source("src/main/res/layout/activity_settings.xml")
        assertFalse("old directory rows must be removed", layout.contains("row_settings_"))
        assertTrue("activity_settings.xml must host ConfigScreen", layout.contains("settings_compose_host"))
    }
}
