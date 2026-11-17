package io.github.nyzeep.orchestella.ui.oe3.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * Oe3 line glyphs: five navigation destinations and nine actions.
 *
 * Every vector is one path on the [Oe3IconStyle.viewport] grid, stroked at
 * [Oe3IconStyle.strokeWidth] with round caps and joins. No icon carries its own
 * colour: the path is painted black and a tint (see [Oe3Icon]) replaces it, so
 * the same vector works in light, dark, pure-black and every theme preset.
 *
 * These glyphs are drawn for Oe3. They are not derived from the Oe v2 family
 * (which stays at 1.8dp stroke for the XML shell) nor from a third-party icon
 * set, so they need no licence entry in NOTICE-icons.md.
 */
object Oe3Icons {

    // --- Navigation -------------------------------------------------------

    /** Workstation: monitor on a stand. */
    val Workstation: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Workstation",
            pathData = "M5.5 4.5h13a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2Z " +
                "M9 20.5h6 M12 17v3.5",
        )
    }

    /** Instances: two stacked cards. */
    val Instances: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Instances",
            pathData = "M7.5 3.5h11a1.5 1.5 0 0 1 1.5 1.5v11 " +
                "M4 8.5h10.5A1.5 1.5 0 0 1 16 10v9.5a1.5 1.5 0 0 1-1.5 1.5H4a1.5 1.5 0 0 1-1.5-1.5V10" +
                "A1.5 1.5 0 0 1 4 8.5Z",
        )
    }

    /** Hardware: processor die with pins. */
    val Hardware: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Hardware",
            pathData = "M8 8h8v8H8Z M10 3.5V8 M14 3.5V8 M10 16v4.5 M14 16v4.5 " +
                "M3.5 10H8 M3.5 14H8 M16 10h4.5 M16 14h4.5",
        )
    }

    /** Distros: a two-by-two grid of tiles. */
    val Distros: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Distros",
            pathData = "M4 5.5A1.5 1.5 0 0 1 5.5 4h4A1.5 1.5 0 0 1 11 5.5v4A1.5 1.5 0 0 1 9.5 11h-4" +
                "A1.5 1.5 0 0 1 4 9.5Z " +
                "M13 5.5A1.5 1.5 0 0 1 14.5 4h4A1.5 1.5 0 0 1 20 5.5v4a1.5 1.5 0 0 1-1.5 1.5h-4" +
                "A1.5 1.5 0 0 1 13 9.5Z " +
                "M4 14.5A1.5 1.5 0 0 1 5.5 13h4A1.5 1.5 0 0 1 11 14.5v4A1.5 1.5 0 0 1 9.5 20h-4" +
                "A1.5 1.5 0 0 1 4 18.5Z " +
                "M13 14.5A1.5 1.5 0 0 1 14.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4" +
                "A1.5 1.5 0 0 1 13 18.5Z",
        )
    }

    /** Config: three sliders with knobs. */
    val Config: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Config",
            pathData = "M4 6.5h3 M11 6.5h9 M9 4.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4 " +
                "M4 12h9 M17 12h3 M15 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4 " +
                "M4 17.5h2 M10 17.5h10 M8 15.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4",
        )
    }

    // --- Actions ----------------------------------------------------------

    /** Terminal: prompt window. */
    val Terminal: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Terminal",
            pathData = "M5.5 3.5h13A2.5 2.5 0 0 1 21 6v12a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 18V6" +
                "a2.5 2.5 0 0 1 2.5-2.5Z M7 9.5l3 2.5-3 2.5 M12.5 15h4.5",
        )
    }

    /** Power: standby ring with a gap at the top. */
    val Power: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Power",
            pathData = "M12 3.5v8 M7.05 7.05a7 7 0 1 0 9.9 0",
        )
    }

    /** More: three dots. */
    val More: ImageVector by lazy {
        lineIcon(
            name = "Oe3.More",
            pathData = "M6 11.15a0.85 0.85 0 1 0 0 1.7 0.85 0.85 0 0 0 0-1.7 " +
                "M12 11.15a0.85 0.85 0 1 0 0 1.7 0.85 0.85 0 0 0 0-1.7 " +
                "M18 11.15a0.85 0.85 0 1 0 0 1.7 0.85 0.85 0 0 0 0-1.7",
        )
    }

    /** Create: plus in a circle. */
    val Create: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Create",
            pathData = "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17 M12 8v8 M8 12h8",
        )
    }

    /** Import: arrow into a tray. */
    val Import: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Import",
            pathData = "M12 3.5v10 M8 9.5l4 4 4-4 M4 15.5v3A2 2 0 0 0 6 20.5h12a2 2 0 0 0 2-2v-3",
        )
    }

    /** SSH: key. */
    val Ssh: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Ssh",
            pathData = "M15.5 4.5a5 5 0 1 0 0 10 5 5 0 0 0 0-10 M10.5 9.5H3.5 M6.5 9.5v3 M3.5 9.5v2.5",
        )
    }

    /** Theme: sun. */
    val Theme: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Theme",
            pathData = "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7 M12 2.5v2.2 M12 19.3v2.2 " +
                "M2.5 12h2.2 M19.3 12h2.2 M5.4 5.4l1.55 1.55 M16.85 16.85l1.55 1.55 " +
                "M18.6 5.4l-1.55 1.55 M7.15 16.85l-1.55 1.55",
        )
    }

    /** About: information mark. */
    val About: ImageVector by lazy {
        lineIcon(
            name = "Oe3.About",
            pathData = "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17 M12 11v5.5 M12 7.6v0.6",
        )
    }

    /** Update: clockwise circular arrow. */
    val Update: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Update",
            pathData = "M20 12a8 8 0 1 1-2.35-5.65L21.5 9.5 M21.5 4.5v5h-5",
        )
    }

    // --- Edit-mode glyphs (v4 / UI-09, dashboard action slot & badges) ------
    // Standalone vals, deliberately not members of [actions]/[all]: the icon
    // sheet golden and the pinned action order stay untouched, and the
    // dashboard contract test pins these three instead.

    /** Edit: pencil — enters the dashboard edit mode. */
    val Edit: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Edit",
            pathData = "M4.5 19.5h4l11-11a2 2 0 0 0 0-2.9l-1.1-1.1a2 2 0 0 0-2.9 0l-11 11v4Z " +
                "M13.5 6.5l4 4",
        )
    }

    /** Check: confirmation tick — saves and leaves the edit mode. */
    val Check: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Check",
            pathData = "M4.5 12.5l5 5 10-11",
        )
    }

    /** Remove: minus in a circle — hides a dashboard card while editing. */
    val Remove: ImageVector by lazy {
        lineIcon(
            name = "Oe3.Remove",
            pathData = "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17 M8 12h8",
        )
    }

    /** Destinations in bottom-bar order. */
    val navigation: List<ImageVector> by lazy {
        listOf(Workstation, Instances, Hardware, Distros, Config)
    }

    /** Row and toolbar actions. */
    val actions: List<ImageVector> by lazy {
        listOf(Terminal, Power, More, Create, Import, Ssh, Theme, About, Update)
    }

    /** Every line glyph in this package, for the one-path contract test. */
    val all: List<ImageVector> by lazy { navigation + actions }
}

private fun lineIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = Oe3IconStyle.size,
        defaultHeight = Oe3IconStyle.size,
        viewportWidth = Oe3IconStyle.viewport,
        viewportHeight = Oe3IconStyle.viewport,
    ).addPath(
        pathData = addPathNodes(pathData),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = Oe3IconStyle.strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
