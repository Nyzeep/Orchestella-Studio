package io.github.nyzeep.orchestella.ui.screens.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-table contract for UI3-04.
 *
 * The ticket's acceptance is "every directory item has a route, and no route is
 * duplicated". A migration that drops a tool therefore fails here instead of
 * leaving a dead end buried in the page.
 */
class ConfigCatalogTest {

    @Test
    fun configHasExactlyFiveGroupsInSpecOrder() {
        val groups = ConfigCatalog.groups()
        assertEquals(
            listOf(
                ConfigGroup.APPEARANCE,
                ConfigGroup.CONNECTION,
                ConfigGroup.RUNTIME,
                ConfigGroup.DATA,
                ConfigGroup.ABOUT,
            ),
            groups.map { it.group },
        )
        assertEquals(5, groups.size)
        groups.forEach { spec ->
            assertFalse("group ${spec.group} must not be empty", spec.entries.isEmpty())
        }
    }

    @Test
    fun appearanceGroupHasExactlyOneEntry() {
        // v4 §2.2：删掉第二个外观入口，外观组只剩「外观与主题」。
        val appearance = ConfigCatalog.groups().first { it.group == ConfigGroup.APPEARANCE }
        assertEquals(listOf(ConfigRoute.THEME_STUDIO), appearance.entries.map { it.route })
    }

    @Test
    fun everyDirectoryEntryHasAUniqueRoute() {
        val entries = ConfigCatalog.groups().flatMap { it.entries }
        val routes = entries.map { it.route }
        assertEquals(
            "duplicate route in the directory: $routes",
            entries.size,
            routes.distinct().size,
        )
    }

    @Test
    fun routeIdsAreUniqueAndNamespaced() {
        val ids = ConfigCatalog.routes().map { it.id }
        assertEquals("duplicate route id: $ids", ids.size, ids.distinct().size)
        ids.forEach { id ->
            assertTrue("route id must be namespaced: $id", id.contains('.'))
            assertFalse("route id must not be blank", id.isBlank())
        }
    }

    @Test
    fun catalogCarriesEveryTicketEntry() {
        val expected = setOf(
            ConfigRoute.THEME_STUDIO,
            ConfigRoute.SSH,
            ConfigRoute.PORT_FORWARD,
            ConfigRoute.ENGINE,
            ConfigRoute.MIRROR,
            ConfigRoute.ADVANCED,
            ConfigRoute.LOGS,
            ConfigRoute.BACKUP,
            ConfigRoute.IMPORT,
            ConfigRoute.EXPORT,
            ConfigRoute.VERSION,
            ConfigRoute.UPDATE,
            ConfigRoute.LICENSES,
            ConfigRoute.ABOUT,
        )
        assertEquals(expected, ConfigCatalog.routes().toSet())
    }

    @Test
    fun everySettingsDestinationNamesAKnownSection() {
        ConfigCatalog.routes().forEach { route ->
            val destination = route.destination
            if (destination is ConfigDestination.SettingsSection) {
                assertTrue(
                    "route ${route.id} points at unknown section ${destination.section}",
                    destination.section in ConfigSections.all,
                )
            }
        }
    }

    @Test
    fun everySettingsSectionIsReachableFromTheDirectory() {
        val reachable = ConfigCatalog.routes()
            .mapNotNull { (it.destination as? ConfigDestination.SettingsSection)?.section }
            .toSet()
        assertEquals(ConfigSections.all.toSet(), reachable)
    }

    @Test
    fun everyRouteIdYieldsAUniqueTestTag() {
        val tags = ConfigCatalog.routes().map { "config:route:${it.id}" }
        assertEquals("duplicate testTag: $tags", tags.size, tags.distinct().size)
    }

    @Test
    fun everyEntryHasATitleAndAnIcon() {
        ConfigCatalog.groups().flatMap { it.entries }.forEach { entry ->
            assertTrue("route ${entry.route.id} is missing a title", entry.titleRes != 0)
            assertTrue("route ${entry.route.id} is missing an icon", entry.iconRes != 0)
        }
    }
}
