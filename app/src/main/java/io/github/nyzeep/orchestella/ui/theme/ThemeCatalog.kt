package io.github.nyzeep.orchestella.ui.theme

/**
 * Built-in theme catalog: flagship Constructivist industrial controls, Claude,
 * Moonshot, and the 15 modern developer suites (Catppuccin, GitHub, Linear, Raycast,
 * Rose Pine, Vercel, VS Code Plus, Xcode, etc.). The ANSI arrays follow the standard
 * 16-color order: black, red, green, yellow, blue, magenta, cyan, white, then the
 * bright eight.
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

    // --- Strictly Preserved First 6 Themes ---

    val constructivistWorkstation = AppTheme(
        id = "constructivist-workstation",
        displayName = "Constructivist (构成主义工控 · 红)",
        isDark = true,
        background = 0xFF0D0E11.toInt(),
        surface = 0xFF16171D.toInt(),
        surfaceTranslucent = 0xE616171D.toInt(),
        primary = 0xFFD62828.toInt(),
        accent = 0xFFFDBB24.toInt(),
        text = 0xFFF5F4F0.toInt(),
        textDim = 0xFF8E929B.toInt(),
        glow = 0xFFD62828.toInt(),
        terminalBackground = 0xFF07080A.toInt(),
        terminalForeground = 0xFFF1F2F5.toInt(),
        ansi = ansi(
            0xFF0D0E11.toInt(), 0xFFD62828.toInt(), 0xFF52B788.toInt(), 0xFFFDBB24.toInt(),
            0xFF0047AB.toInt(), 0xFFE056FD.toInt(), 0xFF00E5FF.toInt(), 0xFFF5F4F0.toInt(),
            0xFF2E323B.toInt(), 0xFFFF5A5A.toInt(), 0xFF74C69D.toInt(), 0xFFFFD166.toInt(),
            0xFF3B82F6.toInt(), 0xFFF772FA.toInt(), 0xFF48CAE4.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val constructivistCobalt = AppTheme(
        id = "constructivist-cobalt",
        displayName = "Constructivist (钴蓝张力 · 蓝)",
        isDark = true,
        background = 0xFF0D0E11.toInt(),
        surface = 0xFF16171D.toInt(),
        surfaceTranslucent = 0xE616171D.toInt(),
        primary = 0xFF0047AB.toInt(),
        accent = 0xFFD62828.toInt(),
        text = 0xFFF5F4F0.toInt(),
        textDim = 0xFF8E929B.toInt(),
        glow = 0xFF0047AB.toInt(),
        terminalBackground = 0xFF07080A.toInt(),
        terminalForeground = 0xFFF1F2F5.toInt(),
        ansi = ansi(
            0xFF0D0E11.toInt(), 0xFF0047AB.toInt(), 0xFF52B788.toInt(), 0xFFFDBB24.toInt(),
            0xFFD62828.toInt(), 0xFFE056FD.toInt(), 0xFF00E5FF.toInt(), 0xFFF5F4F0.toInt(),
            0xFF2E323B.toInt(), 0xFF3B82F6.toInt(), 0xFF74C69D.toInt(), 0xFFFFD166.toInt(),
            0xFFFF5A5A.toInt(), 0xFFF772FA.toInt(), 0xFF48CAE4.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val constructivistAmber = AppTheme(
        id = "constructivist-amber",
        displayName = "Constructivist (镉黄警示 · 黄)",
        isDark = true,
        background = 0xFF0D0E11.toInt(),
        surface = 0xFF16171D.toInt(),
        surfaceTranslucent = 0xE616171D.toInt(),
        primary = 0xFFFDBB24.toInt(),
        accent = 0xFFD62828.toInt(),
        text = 0xFFF5F4F0.toInt(),
        textDim = 0xFF8E929B.toInt(),
        glow = 0xFFFDBB24.toInt(),
        terminalBackground = 0xFF07080A.toInt(),
        terminalForeground = 0xFFF1F2F5.toInt(),
        ansi = ansi(
            0xFF0D0E11.toInt(), 0xFFD62828.toInt(), 0xFF52B788.toInt(), 0xFFFDBB24.toInt(),
            0xFF0047AB.toInt(), 0xFFE056FD.toInt(), 0xFF00E5FF.toInt(), 0xFFF5F4F0.toInt(),
            0xFF2E323B.toInt(), 0xFFFF5A5A.toInt(), 0xFF74C69D.toInt(), 0xFFFFD166.toInt(),
            0xFF3B82F6.toInt(), 0xFFF772FA.toInt(), 0xFF48CAE4.toInt(), 0xFFFFFFFF.toInt(),
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

    // --- 15 New Themes (in user specified dropdown order) ---

    val absolutely = AppTheme(
        id = "absolutely",
        displayName = "Absolutely",
        isDark = true,
        background = 0xFF1E1C1A.toInt(),
        surface = 0xFF2B2724.toInt(),
        surfaceTranslucent = 0xE62B2724.toInt(),
        primary = 0xFFE06C43.toInt(),
        accent = 0xFFE5533D.toInt(),
        text = 0xFFF5EBE1.toInt(),
        textDim = 0xFFA89F91.toInt(),
        glow = 0xFFE06C43.toInt(),
        terminalBackground = 0xFF181615.toInt(),
        terminalForeground = 0xFFF5EBE1.toInt(),
        ansi = ansi(
            0xFF2B2724.toInt(), 0xFFE06C43.toInt(), 0xFF87A96B.toInt(), 0xFFE5A65D.toInt(),
            0xFF6D94B8.toInt(), 0xFFC27BA0.toInt(), 0xFF73A9AD.toInt(), 0xFFE8DFD5.toInt(),
            0xFF544E47.toInt(), 0xFFE5533D.toInt(), 0xFF9BC27E.toInt(), 0xFFF2BE74.toInt(),
            0xFF8CB2D6.toInt(), 0xFFD896BA.toInt(), 0xFF8FC0C4.toInt(), 0xFFFAF3EC.toInt(),
        ),
    )

    val catppuccin = AppTheme(
        id = "catppuccin",
        displayName = "Catppuccin",
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

    val everforest = AppTheme(
        id = "everforest",
        displayName = "Everforest",
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

    val github = AppTheme(
        id = "github",
        displayName = "GitHub",
        isDark = true,
        background = 0xFF0D1117.toInt(),
        surface = 0xFF161B22.toInt(),
        surfaceTranslucent = 0xE6161B22.toInt(),
        primary = 0xFF58A6FF.toInt(),
        accent = 0xFF238636.toInt(),
        text = 0xFFE6EDF3.toInt(),
        textDim = 0xFF8B949E.toInt(),
        glow = 0xFF58A6FF.toInt(),
        terminalBackground = 0xFF0D1117.toInt(),
        terminalForeground = 0xFFE6EDF3.toInt(),
        ansi = ansi(
            0xFF21262D.toInt(), 0xFFFA7970.toInt(), 0xFF7CE38B.toInt(), 0xFFFAA356.toInt(),
            0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
            0xFF484F58.toInt(), 0xFFFF938A.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
            0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFF0F6FC.toInt(),
        ),
    )

    val gruvbox = AppTheme(
        id = "gruvbox",
        displayName = "Gruvbox",
        isDark = true,
        background = 0xFF282828.toInt(),
        surface = 0xFF3C3836.toInt(),
        surfaceTranslucent = 0xE63C3836.toInt(),
        primary = 0xFFFABD2F.toInt(),
        accent = 0xFFFE8019.toInt(),
        text = 0xFFEBDBB2.toInt(),
        textDim = 0xFF928374.toInt(),
        glow = 0xFFFABD2F.toInt(),
        terminalBackground = 0xFF282828.toInt(),
        terminalForeground = 0xFFEBDBB2.toInt(),
        ansi = ansi(
            0xFF3C3836.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
        ),
    )

    val linear = AppTheme(
        id = "linear",
        displayName = "Linear",
        isDark = true,
        background = 0xFF0F1015.toInt(),
        surface = 0xFF181920.toInt(),
        surfaceTranslucent = 0xE6181920.toInt(),
        primary = 0xFF5E6AD2.toInt(),
        accent = 0xFF717FE0.toInt(),
        text = 0xFFEEEEF0.toInt(),
        textDim = 0xFF8A8F98.toInt(),
        glow = 0xFF5E6AD2.toInt(),
        terminalBackground = 0xFF0B0C10.toInt(),
        terminalForeground = 0xFFE2E4E9.toInt(),
        ansi = ansi(
            0xFF1C1D24.toInt(), 0xFFE54D2E.toInt(), 0xFF46A758.toInt(), 0xFFFFC53D.toInt(),
            0xFF5E6AD2.toInt(), 0xFFAB4ABA.toInt(), 0xFF00A2C7.toInt(), 0xFFC1C8CD.toInt(),
            0xFF484A54.toInt(), 0xFFFF6347.toInt(), 0xFF57C96E.toInt(), 0xFFFFD60A.toInt(),
            0xFF717FE0.toInt(), 0xFFC678DD.toInt(), 0xFF00C7E6.toInt(), 0xFFF1F3F5.toInt(),
        ),
    )

    val notion = AppTheme(
        id = "notion",
        displayName = "Notion",
        isDark = true,
        background = 0xFF191919.toInt(),
        surface = 0xFF252525.toInt(),
        surfaceTranslucent = 0xE6252525.toInt(),
        primary = 0xFF2EAADC.toInt(),
        accent = 0xFF45A5E5.toInt(),
        text = 0xFFE6E6E6.toInt(),
        textDim = 0xFF979797.toInt(),
        glow = 0xFF2EAADC.toInt(),
        terminalBackground = 0xFF141414.toInt(),
        terminalForeground = 0xFFE6E6E6.toInt(),
        ansi = ansi(
            0xFF2A2A2A.toInt(), 0xFFEB5757.toInt(), 0xFF27AE60.toInt(), 0xFFF2994A.toInt(),
            0xFF2EAADC.toInt(), 0xFF9B51E0.toInt(), 0xFF219653.toInt(), 0xFFCCCCCC.toInt(),
            0xFF5A5A5A.toInt(), 0xFFFF6B6B.toInt(), 0xFF2ECC71.toInt(), 0xFFF2C94C.toInt(),
            0xFF56CCF2.toInt(), 0xFFBB6BD9.toInt(), 0xFF6FCF97.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val one = AppTheme(
        id = "one",
        displayName = "One",
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

    val proof = AppTheme(
        id = "proof",
        displayName = "Proof",
        isDark = true,
        background = 0xFF131B16.toInt(),
        surface = 0xFF1C2821.toInt(),
        surfaceTranslucent = 0xE61C2821.toInt(),
        primary = 0xFF2D6A4F.toInt(),
        accent = 0xFF52B788.toInt(),
        text = 0xFFE8F1EC.toInt(),
        textDim = 0xFF8AA394.toInt(),
        glow = 0xFF52B788.toInt(),
        terminalBackground = 0xFF0E1511.toInt(),
        terminalForeground = 0xFFE8F1EC.toInt(),
        ansi = ansi(
            0xFF1C2821.toInt(), 0xFFD84A4A.toInt(), 0xFF52B788.toInt(), 0xFFDDA15E.toInt(),
            0xFF4A90E2.toInt(), 0xFFB388FF.toInt(), 0xFF40916C.toInt(), 0xFFC8D6CF.toInt(),
            0xFF3D5245.toInt(), 0xFFFF6B6B.toInt(), 0xFF74C69D.toInt(), 0xFFF4A261.toInt(),
            0xFF64B5F6.toInt(), 0xFFCE93D8.toInt(), 0xFF52B788.toInt(), 0xFFF0FDF4.toInt(),
        ),
    )

    val raycast = AppTheme(
        id = "raycast",
        displayName = "Raycast",
        isDark = true,
        background = 0xFF141416.toInt(),
        surface = 0xFF1F1F23.toInt(),
        surfaceTranslucent = 0xE61F1F23.toInt(),
        primary = 0xFFFF6363.toInt(),
        accent = 0xFFFF9066.toInt(),
        text = 0xFFF2F2F2.toInt(),
        textDim = 0xFF8E8E93.toInt(),
        glow = 0xFFFF6363.toInt(),
        terminalBackground = 0xFF0E0E10.toInt(),
        terminalForeground = 0xFFF2F2F2.toInt(),
        ansi = ansi(
            0xFF26262B.toInt(), 0xFFFF6363.toInt(), 0xFF5BD182.toInt(), 0xFFFFB224.toInt(),
            0xFF4A88FF.toInt(), 0xFFFF66CC.toInt(), 0xFF35D0BA.toInt(), 0xFFD1D1D6.toInt(),
            0xFF545458.toInt(), 0xFFFF7B7B.toInt(), 0xFF73E096.toInt(), 0xFFFFC857.toInt(),
            0xFF6EA5FF.toInt(), 0xFFFF85D6.toInt(), 0xFF5CE0CE.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val rosePine = AppTheme(
        id = "rose-pine",
        displayName = "Rose Pine",
        isDark = true,
        background = 0xFF191724.toInt(),
        surface = 0xFF1F1D2E.toInt(),
        surfaceTranslucent = 0xE61F1D2E.toInt(),
        primary = 0xFFEB6F92.toInt(),
        accent = 0xFF9CCFD8.toInt(),
        text = 0xFFE0DEF4.toInt(),
        textDim = 0xFF908CAA.toInt(),
        glow = 0xFFEB6F92.toInt(),
        terminalBackground = 0xFF191724.toInt(),
        terminalForeground = 0xFFE0DEF4.toInt(),
        ansi = ansi(
            0xFF26233A.toInt(), 0xFFEB6F92.toInt(), 0xFF31748F.toInt(), 0xFFF6C177.toInt(),
            0xFF9CCFD8.toInt(), 0xFFC4A7E7.toInt(), 0xFFEBBCBA.toInt(), 0xFFE0DEF4.toInt(),
            0xFF6E6A86.toInt(), 0xFFEB6F92.toInt(), 0xFF31748F.toInt(), 0xFFF6C177.toInt(),
            0xFF9CCFD8.toInt(), 0xFFC4A7E7.toInt(), 0xFFEBBCBA.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val solarized = AppTheme(
        id = "solarized",
        displayName = "Solarized",
        isDark = true,
        background = 0xFF002B36.toInt(),
        surface = 0xFF073642.toInt(),
        surfaceTranslucent = 0xE6073642.toInt(),
        primary = 0xFFB58900.toInt(),
        accent = 0xFF2AA198.toInt(),
        text = 0xFFEEE8D5.toInt(),
        textDim = 0xFF657B83.toInt(),
        glow = 0xFFB58900.toInt(),
        terminalBackground = 0xFF002B36.toInt(),
        terminalForeground = 0xFFEEE8D5.toInt(),
        ansi = ansi(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF586E75.toInt(), 0xFFCB4B16.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFFDF6E3.toInt(),
        ),
    )

    val vercel = AppTheme(
        id = "vercel",
        displayName = "Vercel",
        isDark = true,
        background = 0xFF000000.toInt(),
        surface = 0xFF111111.toInt(),
        surfaceTranslucent = 0xE6111111.toInt(),
        primary = 0xFF0070F3.toInt(),
        accent = 0xFF50E3C2.toInt(),
        text = 0xFFEDEDED.toInt(),
        textDim = 0xFF888888.toInt(),
        glow = 0xFF0070F3.toInt(),
        terminalBackground = 0xFF000000.toInt(),
        terminalForeground = 0xFFEDEDED.toInt(),
        ansi = ansi(
            0xFF1F1F1F.toInt(), 0xFFEE0000.toInt(), 0xFF0070F3.toInt(), 0xFFF5A623.toInt(),
            0xFF0070F3.toInt(), 0xFF7928CA.toInt(), 0xFF50E3C2.toInt(), 0xFFCCCCCC.toInt(),
            0xFF444444.toInt(), 0xFFFF3333.toInt(), 0xFF3291FF.toInt(), 0xFFF7B955.toInt(),
            0xFF3291FF.toInt(), 0xFF8A3FFC.toInt(), 0xFF79FFE1.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val vsCodePlus = AppTheme(
        id = "vscode-plus",
        displayName = "VS Code Plus",
        isDark = true,
        background = 0xFF1F1F1F.toInt(),
        surface = 0xFF252526.toInt(),
        surfaceTranslucent = 0xE6252526.toInt(),
        primary = 0xFF007ACC.toInt(),
        accent = 0xFF3794FF.toInt(),
        text = 0xFFCCCCCC.toInt(),
        textDim = 0xFF858585.toInt(),
        glow = 0xFF007ACC.toInt(),
        terminalBackground = 0xFF181818.toInt(),
        terminalForeground = 0xFFCCCCCC.toInt(),
        ansi = ansi(
            0xFF2D2D2D.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFE5E510.toInt(),
            0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF5A5A5A.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
            0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val xcode = AppTheme(
        id = "xcode",
        displayName = "Xcode",
        isDark = true,
        background = 0xFF1F2228.toInt(),
        surface = 0xFF292C34.toInt(),
        surfaceTranslucent = 0xE6292C34.toInt(),
        primary = 0xFF1488FC.toInt(),
        accent = 0xFFFC5657.toInt(),
        text = 0xFFDFE1E5.toInt(),
        textDim = 0xFF8C92A4.toInt(),
        glow = 0xFF1488FC.toInt(),
        terminalBackground = 0xFF1A1C22.toInt(),
        terminalForeground = 0xFFDFE1E5.toInt(),
        ansi = ansi(
            0xFF2E313A.toInt(), 0xFFFF5F56.toInt(), 0xFF28C840.toInt(), 0xFFFFBD2E.toInt(),
            0xFF1488FC.toInt(), 0xFFE056FD.toInt(), 0xFF00D1FF.toInt(), 0xFFD2D4D8.toInt(),
            0xFF595E6D.toInt(), 0xFFFF7B73.toInt(), 0xFF4BD761.toInt(), 0xFFFFC94D.toInt(),
            0xFF4BA6FF.toInt(), 0xFFE979FF.toInt(), 0xFF4DE0FF.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val all: List<AppTheme> = listOf(
        constructivistWorkstation, constructivistCobalt, constructivistAmber,
        moonshotDark, claudeDark, claudeLight,
        absolutely, catppuccin, everforest, github, gruvbox,
        linear, notion, one, proof, raycast,
        rosePine, solarized, vercel, vsCodePlus, xcode,
    )

    fun byId(id: String?): AppTheme? = all.firstOrNull { it.id == id }

    /** Resolves a stored theme id, falling back to the flagship Constructivist Workstation default. */
    fun resolve(id: String?): AppTheme = byId(id) ?: constructivistWorkstation
}
