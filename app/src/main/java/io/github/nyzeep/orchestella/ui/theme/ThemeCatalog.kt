package io.github.nyzeep.orchestella.ui.theme

/**
 * Built-in theme catalog: well-known dark and light palettes (Catppuccin family,
 * Dracula, Nord, Tokyo Night, Gruvbox, Solarized, One Dark, Everforest). The ANSI
 * arrays follow the standard 16-color order: black, red, green, yellow, blue,
 * magenta, cyan, white, then the bright eight.
 */
object ThemeCatalog {

    private fun ansi(
        black: Int, red: Int, green: Int, yellow: Int, blue: Int, magenta: Int, cyan: Int, white: Int,
        brightBlack: Int, brightRed: Int, brightGreen: Int, brightYellow: Int, brightBlue: Int,
        brightMagenta: Int, brightCyan: Int, brightWhite: Int,
    ): List<Int> = listOf(
        black, red, green, yellow, blue, magenta, cyan, white,
        brightBlack, brightRed, brightGreen, brightYellow, brightBlue, brightMagenta, brightCyan, brightWhite,
    )

    val catppuccinMocha = AppTheme(
        id = "catppuccin-mocha",
        displayName = "Catppuccin Mocha",
        isDark = true,
        background = 0xFF1E1E2E.toInt(),
        surface = 0xFF313244.toInt(),
        surfaceTranslucent = 0xE6313244.toInt(),
        primary = 0xFFCBA6F7.toInt(),
        accent = 0xFF89B4FA.toInt(),
        text = 0xFFCDD6F4.toInt(),
        textDim = 0xFF9399B2.toInt(),
        glow = 0xFFCBA6F7.toInt(),
        terminalBackground = 0xFF1E1E2E.toInt(),
        terminalForeground = 0xFFCDD6F4.toInt(),
        ansi = ansi(
            0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
            0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFBAC2DE.toInt(),
            0xFF585B70.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
            0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt(),
        ),
    )

    val catppuccinLatte = AppTheme(
        id = "catppuccin-latte",
        displayName = "Catppuccin Latte",
        isDark = false,
        background = 0xFFEFF1F5.toInt(),
        surface = 0xFFE6E9EF.toInt(),
        surfaceTranslucent = 0xE6E6E9EF.toInt(),
        primary = 0xFF8839EF.toInt(),
        accent = 0xFF1E66F5.toInt(),
        text = 0xFF4C4F69.toInt(),
        textDim = 0xFF8C8FA1.toInt(),
        glow = 0xFF8839EF.toInt(),
        terminalBackground = 0xFFEFF1F5.toInt(),
        terminalForeground = 0xFF4C4F69.toInt(),
        ansi = ansi(
            0xFF5C5F77.toInt(), 0xFFD20F39.toInt(), 0xFF40A02B.toInt(), 0xFFDF8E1D.toInt(),
            0xFF1E66F5.toInt(), 0xFFEA76CB.toInt(), 0xFF179299.toInt(), 0xFFACB0BE.toInt(),
            0xFF6C6F85.toInt(), 0xFFD20F39.toInt(), 0xFF40A02B.toInt(), 0xFFDF8E1D.toInt(),
            0xFF1E66F5.toInt(), 0xFFEA76CB.toInt(), 0xFF179299.toInt(), 0xFF4C4F69.toInt(),
        ),
    )

    val catppuccinFrappe = AppTheme(
        id = "catppuccin-frappe",
        displayName = "Catppuccin Frappé",
        isDark = true,
        background = 0xFF232634.toInt(),
        surface = 0xFF414559.toInt(),
        surfaceTranslucent = 0xE6414559.toInt(),
        primary = 0xFFCA9EE6.toInt(),
        accent = 0xFF8CAAEE.toInt(),
        text = 0xFFC6D0F5.toInt(),
        textDim = 0xFF838BA7.toInt(),
        glow = 0xFFCA9EE6.toInt(),
        terminalBackground = 0xFF232634.toInt(),
        terminalForeground = 0xFFC6D0F5.toInt(),
        ansi = ansi(
            0xFF51576D.toInt(), 0xFFE78284.toInt(), 0xFFA6D189.toInt(), 0xFFE5C890.toInt(),
            0xFF8CAAEE.toInt(), 0xFFF4B8E4.toInt(), 0xFF81C8BE.toInt(), 0xFFB5BFE2.toInt(),
            0xFF626880.toInt(), 0xFFE78284.toInt(), 0xFFA6D189.toInt(), 0xFFE5C890.toInt(),
            0xFF8CAAEE.toInt(), 0xFFF4B8E4.toInt(), 0xFF81C8BE.toInt(), 0xFFA5ADCE.toInt(),
        ),
    )

    val catppuccinMacchiato = AppTheme(
        id = "catppuccin-macchiato",
        displayName = "Catppuccin Macchiato",
        isDark = true,
        background = 0xFF24273A.toInt(),
        surface = 0xFF363A4F.toInt(),
        surfaceTranslucent = 0xE6363A4F.toInt(),
        primary = 0xFFC6A0F6.toInt(),
        accent = 0xFF8AADF4.toInt(),
        text = 0xFFCAD3F5.toInt(),
        textDim = 0xFF939AB7.toInt(),
        glow = 0xFFC6A0F6.toInt(),
        terminalBackground = 0xFF24273A.toInt(),
        terminalForeground = 0xFFCAD3F5.toInt(),
        ansi = ansi(
            0xFF494D64.toInt(), 0xFFED8796.toInt(), 0xFFA6DA95.toInt(), 0xFFEED49F.toInt(),
            0xFF8AADF4.toInt(), 0xFFF5BDE6.toInt(), 0xFF8BD5CA.toInt(), 0xFFB8C0E0.toInt(),
            0xFF5B6078.toInt(), 0xFFED8796.toInt(), 0xFFA6DA95.toInt(), 0xFFEED49F.toInt(),
            0xFF8AADF4.toInt(), 0xFFF5BDE6.toInt(), 0xFF8BD5CA.toInt(), 0xFFA5ADCB.toInt(),
        ),
    )

    val dracula = AppTheme(
        id = "dracula",
        displayName = "Dracula",
        isDark = true,
        background = 0xFF282A36.toInt(),
        surface = 0xFF343746.toInt(),
        surfaceTranslucent = 0xE6343746.toInt(),
        primary = 0xFFBD93F9.toInt(),
        accent = 0xFF8BE9FD.toInt(),
        text = 0xFFF8F8F2.toInt(),
        textDim = 0xFF6272A4.toInt(),
        glow = 0xFFBD93F9.toInt(),
        terminalBackground = 0xFF282A36.toInt(),
        terminalForeground = 0xFFF8F8F2.toInt(),
        ansi = ansi(
            0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
            0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
            0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val nord = AppTheme(
        id = "nord",
        displayName = "Nord",
        isDark = true,
        background = 0xFF2E3440.toInt(),
        surface = 0xFF3B4252.toInt(),
        surfaceTranslucent = 0xE63B4252.toInt(),
        primary = 0xFF88C0D0.toInt(),
        accent = 0xFF81A1C1.toInt(),
        text = 0xFFECEFF4.toInt(),
        textDim = 0xFF7B88A1.toInt(),
        glow = 0xFF88C0D0.toInt(),
        terminalBackground = 0xFF2E3440.toInt(),
        terminalForeground = 0xFFECEFF4.toInt(),
        ansi = ansi(
            0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt(),
        ),
    )

    val tokyoNight = AppTheme(
        id = "tokyo-night",
        displayName = "Tokyo Night",
        isDark = true,
        background = 0xFF1A1B26.toInt(),
        surface = 0xFF24283B.toInt(),
        surfaceTranslucent = 0xE624283B.toInt(),
        primary = 0xFF7AA2F7.toInt(),
        accent = 0xFFBB9AF7.toInt(),
        text = 0xFFC0CAF5.toInt(),
        textDim = 0xFF565F89.toInt(),
        glow = 0xFF7AA2F7.toInt(),
        terminalBackground = 0xFF1A1B26.toInt(),
        terminalForeground = 0xFFC0CAF5.toInt(),
        ansi = ansi(
            0xFF32344A.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
            0xFF444B6A.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
        ),
    )

    val gruvboxDark = AppTheme(
        id = "gruvbox-dark",
        displayName = "Gruvbox Dark",
        isDark = true,
        background = 0xFF282828.toInt(),
        surface = 0xFF3C3836.toInt(),
        surfaceTranslucent = 0xE63C3836.toInt(),
        primary = 0xFFB8BB26.toInt(),
        accent = 0xFFFE8019.toInt(),
        text = 0xFFEBDBB2.toInt(),
        textDim = 0xFF928374.toInt(),
        glow = 0xFFB8BB26.toInt(),
        terminalBackground = 0xFF282828.toInt(),
        terminalForeground = 0xFFEBDBB2.toInt(),
        ansi = ansi(
            0xFF3C3836.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
        ),
    )

    val gruvboxLight = AppTheme(
        id = "gruvbox-light",
        displayName = "Gruvbox Light",
        isDark = false,
        background = 0xFFFBF1C7.toInt(),
        surface = 0xFFEBDBB2.toInt(),
        surfaceTranslucent = 0xE6EBDBB2.toInt(),
        primary = 0xFF79740E.toInt(),
        accent = 0xFFAF3A03.toInt(),
        text = 0xFF3C3836.toInt(),
        textDim = 0xFF928374.toInt(),
        glow = 0xFF79740E.toInt(),
        terminalBackground = 0xFFFBF1C7.toInt(),
        terminalForeground = 0xFF3C3836.toInt(),
        ansi = ansi(
            0xFFBDAE93.toInt(), 0xFF9D0006.toInt(), 0xFF79740E.toInt(), 0xFFB57614.toInt(),
            0xFF076678.toInt(), 0xFF8F3F71.toInt(), 0xFF427B58.toInt(), 0xFF7C6F64.toInt(),
            0xFF928374.toInt(), 0xFF9D0006.toInt(), 0xFF79740E.toInt(), 0xFFB57614.toInt(),
            0xFF076678.toInt(), 0xFF8F3F71.toInt(), 0xFF427B58.toInt(), 0xFF3C3836.toInt(),
        ),
    )

    val solarizedDark = AppTheme(
        id = "solarized-dark",
        displayName = "Solarized Dark",
        isDark = true,
        background = 0xFF002B36.toInt(),
        surface = 0xFF073642.toInt(),
        surfaceTranslucent = 0xE6073642.toInt(),
        primary = 0xFF268BD2.toInt(),
        accent = 0xFF2AA198.toInt(),
        text = 0xFFEEE8D5.toInt(),
        textDim = 0xFF657B83.toInt(),
        glow = 0xFF268BD2.toInt(),
        terminalBackground = 0xFF002B36.toInt(),
        terminalForeground = 0xFFEEE8D5.toInt(),
        ansi = ansi(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF586E75.toInt(), 0xFFCB4B16.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFFDF6E3.toInt(),
        ),
    )

    val solarizedLight = AppTheme(
        id = "solarized-light",
        displayName = "Solarized Light",
        isDark = false,
        background = 0xFFFDF6E3.toInt(),
        surface = 0xFFEEE8D5.toInt(),
        surfaceTranslucent = 0xE6EEE8D5.toInt(),
        primary = 0xFF268BD2.toInt(),
        accent = 0xFF2AA198.toInt(),
        text = 0xFF586E75.toInt(),
        textDim = 0xFF93A1A1.toInt(),
        glow = 0xFF268BD2.toInt(),
        terminalBackground = 0xFFFDF6E3.toInt(),
        terminalForeground = 0xFF586E75.toInt(),
        ansi = ansi(
            0xFFEEE8D5.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFF073642.toInt(),
            0xFF93A1A1.toInt(), 0xFFCB4B16.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFF002B36.toInt(),
        ),
    )

    val oneDark = AppTheme(
        id = "one-dark",
        displayName = "One Dark",
        isDark = true,
        background = 0xFF282C34.toInt(),
        surface = 0xFF3E4451.toInt(),
        surfaceTranslucent = 0xE63E4451.toInt(),
        primary = 0xFF61AFEF.toInt(),
        accent = 0xFFC678DD.toInt(),
        text = 0xFFABB2BF.toInt(),
        textDim = 0xFF5C6370.toInt(),
        glow = 0xFF61AFEF.toInt(),
        terminalBackground = 0xFF282C34.toInt(),
        terminalForeground = 0xFFABB2BF.toInt(),
        ansi = ansi(
            0xFF3E4451.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
            0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val everforestDark = AppTheme(
        id = "everforest-dark",
        displayName = "Everforest Dark",
        isDark = true,
        background = 0xFF2D353B.toInt(),
        surface = 0xFF343F44.toInt(),
        surfaceTranslucent = 0xE6343F44.toInt(),
        primary = 0xFFA7C080.toInt(),
        accent = 0xFF7FBBB3.toInt(),
        text = 0xFFD3C6AA.toInt(),
        textDim = 0xFF859289.toInt(),
        glow = 0xFFA7C080.toInt(),
        terminalBackground = 0xFF2D353B.toInt(),
        terminalForeground = 0xFFD3C6AA.toInt(),
        ansi = ansi(
            0xFF475258.toInt(), 0xFFE67E80.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(),
            0xFF7FBBB3.toInt(), 0xFFD699B6.toInt(), 0xFF83C092.toInt(), 0xFFD3C6AA.toInt(),
            0xFF4F585E.toInt(), 0xFFE67E80.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(),
            0xFF7FBBB3.toInt(), 0xFFD699B6.toInt(), 0xFF83C092.toInt(), 0xFFD3C6AA.toInt(),
        ),
    )

    val moonshotDark = AppTheme(
        id = "moonshot-dark",
        displayName = "Moonshot (暗面深空)",
        isDark = true,
        background = 0xFF0C1017.toInt(),
        surface = 0xFF161B22.toInt(),
        surfaceTranslucent = 0xD9161B22.toInt(),
        primary = 0xFF007CFF.toInt(),
        accent = 0xFF00F6FF.toInt(),
        text = 0xFFF0F6FC.toInt(),
        textDim = 0xFF8B949E.toInt(),
        glow = 0xFF00A1FF.toInt(),
        terminalBackground = 0xFF0C1017.toInt(),
        terminalForeground = 0xFFE6EDF3.toInt(),
        ansi = ansi(
            0xFF21262D.toInt(), 0xFFFA7970.toInt(), 0xFF7CE38B.toInt(), 0xFFFAA356.toInt(),
            0xFF77BDFB.toInt(), 0xFFCEA5FB.toInt(), 0xFF00F6FF.toInt(), 0xFFC9D1D9.toInt(),
            0xFF484F58.toInt(), 0xFFFA7970.toInt(), 0xFF7CE38B.toInt(), 0xFFFAA356.toInt(),
            0xFF77BDFB.toInt(), 0xFFCEA5FB.toInt(), 0xFF00F6FF.toInt(), 0xFFF0F6FC.toInt(),
        ),
    )

    val claudeDark = AppTheme(
        id = "claude-dark",
        displayName = "Claude (岩板碳黑)",
        isDark = true,
        background = 0xFF18181B.toInt(),
        surface = 0xFF222226.toInt(),
        surfaceTranslucent = 0xD9222226.toInt(),
        primary = 0xFFD97706.toInt(),
        accent = 0xFFE07A5F.toInt(),
        text = 0xFFF4EFEA.toInt(),
        textDim = 0xFFA19E9A.toInt(),
        glow = 0xFFE07A5F.toInt(),
        terminalBackground = 0xFF141416.toInt(),
        terminalForeground = 0xFFF4EFEA.toInt(),
        ansi = ansi(
            0xFF27272A.toInt(), 0xFFE07A5F.toInt(), 0xFF81B29A.toInt(), 0xFFF4A261.toInt(),
            0xFF3D5A80.toInt(), 0xFF9B5DE5.toInt(), 0xFF00B4D8.toInt(), 0xFFE4E4E7.toInt(),
            0xFF52525B.toInt(), 0xFFE07A5F.toInt(), 0xFF81B29A.toInt(), 0xFFF4A261.toInt(),
            0xFF3D5A80.toInt(), 0xFF9B5DE5.toInt(), 0xFF00B4D8.toInt(), 0xFFFAFAFA.toInt(),
        ),
    )

    val claudeLight = AppTheme(
        id = "claude-light",
        displayName = "Claude (温暖浅沙)",
        isDark = false,
        background = 0xFFFAF8F5.toInt(),
        surface = 0xFFF3EFEA.toInt(),
        surfaceTranslucent = 0xEBF3EFEA.toInt(),
        primary = 0xFFC05621.toInt(),
        accent = 0xFF9C4221.toInt(),
        text = 0xFF2D2825.toInt(),
        textDim = 0xFF78716C.toInt(),
        glow = 0xFFC05621.toInt(),
        terminalBackground = 0xFFFAF8F5.toInt(),
        terminalForeground = 0xFF2D2825.toInt(),
        ansi = ansi(
            0xFF44403C.toInt(), 0xFFC05621.toInt(), 0xFF2E7D32.toInt(), 0xFFD97706.toInt(),
            0xFF1D4ED8.toInt(), 0xFF7C3AED.toInt(), 0xFF0284C7.toInt(), 0xFFE7E5E4.toInt(),
            0xFF78716C.toInt(), 0xFFC05621.toInt(), 0xFF2E7D32.toInt(), 0xFFD97706.toInt(),
            0xFF1D4ED8.toInt(), 0xFF7C3AED.toInt(), 0xFF0284C7.toInt(), 0xFF2D2825.toInt(),
        ),
    )

    val all: List<AppTheme> = listOf(
        moonshotDark, claudeDark, claudeLight,
        catppuccinMocha, catppuccinLatte, catppuccinFrappe, catppuccinMacchiato,
        dracula, nord, tokyoNight, gruvboxDark, gruvboxLight,
        solarizedDark, solarizedLight, oneDark, everforestDark,
    )

    fun byId(id: String?): AppTheme? = all.firstOrNull { it.id == id }

    /** Resolves a stored theme id, falling back to the Dracula default. */
    fun resolve(id: String?): AppTheme = byId(id) ?: moonshotDark
}
