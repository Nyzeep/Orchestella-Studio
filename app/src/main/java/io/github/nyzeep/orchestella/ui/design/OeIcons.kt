package io.github.nyzeep.orchestella.ui.design

import io.github.nyzeep.orchestella.R

/**
 * Vector drawable ids for the Oe icon family. One family, one geometry:
 * 24x24 viewport, 1.8dp round stroke, no fill, tinted by the caller.
 *
 * Access is deliberately `Int` (not `ImageVector`): call sites use
 * `painterResource(OeIcons.Terminal)` and pick the tint from the theme, which
 * keeps the assets compatible with the XML pages as well.
 */
object OeIcons {
    // Destinations
    val Workbench = R.drawable.ic_oe_workbench
    val Instances = R.drawable.ic_oe_instances
    val Engine = R.drawable.ic_oe_engine
    val Tools = R.drawable.ic_oe_tools

    // Workbench actions
    val Terminal = R.drawable.ic_oe_terminal
    val Port = R.drawable.ic_oe_port
    val Log = R.drawable.ic_oe_log
    val Backup = R.drawable.ic_oe_backup

    // Telemetry
    val Cpu = R.drawable.ic_oe_cpu
    val Memory = R.drawable.ic_oe_memory
    val Storage = R.drawable.ic_oe_storage
    val Battery = R.drawable.ic_oe_battery
    val Network = R.drawable.ic_oe_network

    // Connection and appearance
    val Ssh = R.drawable.ic_oe_ssh
    val Theme = R.drawable.ic_oe_theme
    val Palette = R.drawable.ic_oe_palette
    val Glass = R.drawable.ic_oe_glass
    val Font = R.drawable.ic_oe_font
    val Animation = R.drawable.ic_oe_animation

    // State
    val Lock = R.drawable.ic_oe_lock
    val Shield = R.drawable.ic_oe_shield
    val Warning = R.drawable.ic_oe_warning

    // Actions
    val Refresh = R.drawable.ic_oe_refresh
    val Search = R.drawable.ic_oe_search
    val More = R.drawable.ic_oe_more
    val Back = R.drawable.ic_oe_back
    val Close = R.drawable.ic_oe_close
    val Check = R.drawable.ic_oe_check
    val Plus = R.drawable.ic_oe_plus
    val Minus = R.drawable.ic_oe_minus
    val External = R.drawable.ic_oe_external
    val Import = R.drawable.ic_oe_import
    val Export = R.drawable.ic_oe_export
    val Delete = R.drawable.ic_oe_delete

    // Data movement and media
    val Clock = R.drawable.ic_oe_clock
    val Download = R.drawable.ic_oe_download
    val Upload = R.drawable.ic_oe_upload
    val Play = R.drawable.ic_oe_play
    val Pause = R.drawable.ic_oe_pause
    val Stop = R.drawable.ic_oe_stop
}
