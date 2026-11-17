package io.github.nyzeep.orchestella.ui.screens.config

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.R

/**
 * Section keys shared by [ConfigCatalog] and `SettingsSubActivity`.
 *
 * The settings sub-activity used to be a string switch with the keys duplicated
 * at both ends. Both sides now point at these constants, and
 * `ConfigCatalogTest` fails if a catalog destination names a section that is
 * not listed in [all] — the single mapping the ticket asked for.
 */
object ConfigSections {
    const val APPEARANCE = "appearance"
    const val MIRROR = "mirror"
    const val ABOUT = "about"
    const val ADVANCED = "advanced"

    /** Engine/authorization page: AVF preflight, QEMU fallback, UML gate. */
    const val SYSTEM = "system"

    val all: List<String> = listOf(APPEARANCE, MIRROR, ABOUT, ADVANCED, SYSTEM)
}

/**
 * What a directory row does. The host activity switches on this sealed type
 * without an `else`, so adding a destination is a compile error until it is
 * routed.
 */
sealed interface ConfigDestination {
    /** Opens `SettingsSubActivity` with [section] (one of [ConfigSections]). */
    @Immutable
    data class SettingsSection(val section: String) : ConfigDestination

    /** UI3-09 owns the real screen; until then the host shows a 施工中 dialog. */
    data object ThemeStudio : ConfigDestination

    data object SshManager : ConfigDestination
    data object PortForwarding : ConfigDestination
    data object Logs : ConfigDestination

    /** Backup/restore chooser (export a cold backup or import an archive). */
    data object Backup : ConfigDestination
    data object Import : ConfigDestination
    data object Export : ConfigDestination

    /** About page carries the version card, the manual update check and notices. */
    data object UpdateCheck : ConfigDestination
    data object Licenses : ConfigDestination
    data object AboutPage : ConfigDestination
}

/**
 * One directory row. [route] is unique across the catalog; [titleRes] and
 * [subtitleRes] are resources so the screen stays previewable without a
 * Context-held model.
 */
@Immutable
data class ConfigEntry(
    val route: ConfigRoute,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int? = null,
    @DrawableRes val iconRes: Int,
)

/** The five groups from the ticket, in spec order. */
enum class ConfigGroup(
    @StringRes val titleRes: Int,
    /** Whether this group carries one of the persisted playability options. */
    val hasOptions: Boolean,
) {
    APPEARANCE(R.string.config_group_appearance, true),
    CONNECTION(R.string.config_group_connection, false),
    RUNTIME(R.string.config_group_runtime, true),
    DATA(R.string.config_group_data, false),
    ABOUT(R.string.config_group_about, true),
}

/** A group header plus its directory rows; the header sits outside the card. */
@Immutable
data class ConfigGroupSpec(
    val group: ConfigGroup,
    val entries: List<ConfigEntry>,
)

/**
 * The configuration directory. This table is the single source of truth for
 * "every tool is reachable from 配置"; tests assert route uniqueness and the
 * five-group shape, so a missed migration fails the build instead of hiding
 * behind a scroll position.
 */
enum class ConfigRoute(
    val id: String,
    val destination: ConfigDestination,
) {
    THEME_STUDIO("appearance.theme_studio", ConfigDestination.ThemeStudio),
    WALLPAPER(
        "appearance.wallpaper",
        ConfigDestination.SettingsSection(ConfigSections.APPEARANCE),
    ),
    SSH("connection.ssh", ConfigDestination.SshManager),
    PORT_FORWARD("connection.port_forward", ConfigDestination.PortForwarding),
    ENGINE(
        "runtime.engine",
        ConfigDestination.SettingsSection(ConfigSections.SYSTEM),
    ),
    MIRROR(
        "runtime.mirror",
        ConfigDestination.SettingsSection(ConfigSections.MIRROR),
    ),
    ADVANCED(
        "runtime.advanced",
        ConfigDestination.SettingsSection(ConfigSections.ADVANCED),
    ),
    LOGS("runtime.logs", ConfigDestination.Logs),
    BACKUP("data.backup", ConfigDestination.Backup),
    IMPORT("data.import", ConfigDestination.Import),
    EXPORT("data.export", ConfigDestination.Export),
    VERSION(
        "about.version",
        ConfigDestination.SettingsSection(ConfigSections.ABOUT),
    ),
    UPDATE("about.update", ConfigDestination.UpdateCheck),
    LICENSES("about.licenses", ConfigDestination.Licenses),
    ABOUT("about.about", ConfigDestination.AboutPage),
}

object ConfigCatalog {

    private val groups: List<ConfigGroupSpec> = listOf(
        ConfigGroupSpec(
            ConfigGroup.APPEARANCE,
            listOf(
                ConfigEntry(
                    ConfigRoute.THEME_STUDIO,
                    R.string.config_entry_theme_studio,
                    R.string.config_entry_theme_studio_desc,
                    R.drawable.ic_oe_theme,
                ),
                ConfigEntry(
                    ConfigRoute.WALLPAPER,
                    R.string.config_entry_wallpaper,
                    R.string.config_entry_wallpaper_desc,
                    R.drawable.ic_oe_palette,
                ),
            ),
        ),
        ConfigGroupSpec(
            ConfigGroup.CONNECTION,
            listOf(
                ConfigEntry(
                    ConfigRoute.SSH,
                    R.string.config_entry_ssh,
                    R.string.config_entry_ssh_desc,
                    R.drawable.ic_oe_ssh,
                ),
                ConfigEntry(
                    ConfigRoute.PORT_FORWARD,
                    R.string.config_entry_port_forward,
                    R.string.config_entry_port_forward_desc,
                    R.drawable.ic_oe_port,
                ),
            ),
        ),
        ConfigGroupSpec(
            ConfigGroup.RUNTIME,
            listOf(
                ConfigEntry(
                    ConfigRoute.ENGINE,
                    R.string.config_entry_engine,
                    // Subtitle is the live AVF/QEMU verdict, supplied by ConfigScreenState.
                    null,
                    R.drawable.ic_oe_engine,
                ),
                ConfigEntry(
                    ConfigRoute.MIRROR,
                    R.string.config_entry_mirror,
                    R.string.config_entry_mirror_desc,
                    R.drawable.ic_oe_download,
                ),
                ConfigEntry(
                    ConfigRoute.ADVANCED,
                    R.string.config_entry_advanced,
                    R.string.config_entry_advanced_desc,
                    R.drawable.ic_oe_tools,
                ),
                ConfigEntry(
                    ConfigRoute.LOGS,
                    R.string.config_entry_logs,
                    R.string.config_entry_logs_desc,
                    R.drawable.ic_oe_log,
                ),
            ),
        ),
        ConfigGroupSpec(
            ConfigGroup.DATA,
            listOf(
                ConfigEntry(
                    ConfigRoute.BACKUP,
                    R.string.config_entry_backup,
                    R.string.config_entry_backup_desc,
                    R.drawable.ic_oe_backup,
                ),
                ConfigEntry(
                    ConfigRoute.IMPORT,
                    R.string.config_entry_import,
                    R.string.config_entry_import_desc,
                    R.drawable.ic_oe_import,
                ),
                ConfigEntry(
                    ConfigRoute.EXPORT,
                    R.string.config_entry_export,
                    R.string.config_entry_export_desc,
                    R.drawable.ic_oe_export,
                ),
            ),
        ),
        ConfigGroupSpec(
            ConfigGroup.ABOUT,
            listOf(
                ConfigEntry(
                    ConfigRoute.VERSION,
                    R.string.config_entry_version,
                    null,
                    R.drawable.ic_oe_shield,
                ),
                ConfigEntry(
                    ConfigRoute.UPDATE,
                    R.string.config_entry_update,
                    // Subtitle is the cached release note, supplied by ConfigScreenState.
                    null,
                    R.drawable.ic_oe_refresh,
                ),
                ConfigEntry(
                    ConfigRoute.LICENSES,
                    R.string.config_entry_licenses,
                    R.string.config_entry_licenses_desc,
                    R.drawable.ic_oe_lock,
                ),
                ConfigEntry(
                    ConfigRoute.ABOUT,
                    R.string.config_entry_about,
                    R.string.config_entry_about_desc,
                    R.drawable.ic_oe_theme,
                ),
            ),
        ),
    )

    /** Group specs in display order; exactly five per the ticket. */
    fun groups(): List<ConfigGroupSpec> = groups

    /** Flat route list, used by the uniqueness/reachability tests. */
    fun routes(): List<ConfigRoute> = groups.flatMap { spec -> spec.entries.map { it.route } }
}
