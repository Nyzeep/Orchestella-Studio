package io.github.nyzeep.orchestella;

/**
 * Standard palette definition for the Dracula terminal theme.
 * Includes default background/foreground, standard ANSI 16 colors, and 256-color lookup.
 */
public final class DraculaPalette {

    public static final int BACKGROUND = 0xFF282A36;
    public static final int FOREGROUND = 0xFFF8F8F2;
    public static final int SELECTION = 0xFF44475A;
    public static final int COMMENT = 0xFF6272A4;

    // Standard 8 colors (0-7)
    public static final int COLOR_BLACK = 0xFF21222C;
    public static final int COLOR_RED = 0xFFFF5555;
    public static final int COLOR_GREEN = 0xFF50FA7B;
    public static final int COLOR_YELLOW = 0xFFF1FA8C;
    public static final int COLOR_BLUE = 0xFFBD93F9;
    public static final int COLOR_MAGENTA = 0xFFFF79C6;
    public static final int COLOR_CYAN = 0xFF8BE9FD;
    public static final int COLOR_WHITE = 0xFFF8F8F2;

    // Bright 8 colors (8-15)
    public static final int COLOR_BRIGHT_BLACK = 0xFF6272A4;
    public static final int COLOR_BRIGHT_RED = 0xFFFF6E6E;
    public static final int COLOR_BRIGHT_GREEN = 0xFF69FF94;
    public static final int COLOR_BRIGHT_YELLOW = 0xFFFFFFA5;
    public static final int COLOR_BRIGHT_BLUE = 0xFFD6ACFF;
    public static final int COLOR_BRIGHT_MAGENTA = 0xFFFF92DF;
    public static final int COLOR_BRIGHT_CYAN = 0xFFA4FFFF;
    public static final int COLOR_BRIGHT_WHITE = 0xFFFFFFFF;

    // Glow (泛光) accents for hero surfaces and rings; mirrored in res/values/colors.xml
    public static final int GLOW_PURPLE = 0xFFBD93F9;
    public static final int GLOW_CYAN = 0xFF8BE9FD;
    public static final int GLOW_PINK = 0xFFFF79C6;

    private static final int[] ANSI_16 = {
            COLOR_BLACK,
            COLOR_RED,
            COLOR_GREEN,
            COLOR_YELLOW,
            COLOR_BLUE,
            COLOR_MAGENTA,
            COLOR_CYAN,
            COLOR_WHITE,
            COLOR_BRIGHT_BLACK,
            COLOR_BRIGHT_RED,
            COLOR_BRIGHT_GREEN,
            COLOR_BRIGHT_YELLOW,
            COLOR_BRIGHT_BLUE,
            COLOR_BRIGHT_MAGENTA,
            COLOR_BRIGHT_CYAN,
            COLOR_BRIGHT_WHITE
    };

    private static final int[] CUBE_STEPS = {0, 95, 135, 175, 215, 255};

    private DraculaPalette() {
    }

    // Theme override: when the user selects a palette (ThemeCatalog), the ANSI 16 and
    // default fg/bg resolve from here so every consumer (parser, cursor, view) follows.
    private static volatile int[] ansiOverride;
    private static volatile int fgOverride;
    private static volatile int bgOverride;
    private static volatile boolean overridesActive;

    /** Installs a theme palette override; pass null to restore the built-in Dracula set. */
    public static void setPaletteOverride(int[] ansi16, int foreground, int background) {
        if (ansi16 == null || ansi16.length != 16) {
            overridesActive = false;
            return;
        }
        ansiOverride = ansi16.clone();
        fgOverride = foreground;
        bgOverride = background;
        overridesActive = true;
    }

    public static int themedBackground() {
        return overridesActive ? bgOverride : BACKGROUND;
    }

    public static int themedForeground() {
        return overridesActive ? fgOverride : FOREGROUND;
    }

    /**
     * Resolves ANSI 16 color index (0-15).
     */
    public static int getAnsi16(int index) {
        int[] palette = overridesActive ? ansiOverride : ANSI_16;
        if (index < 0 || index >= palette.length) {
            return overridesActive ? fgOverride : FOREGROUND;
        }
        return palette[index];
    }

    /**
     * Resolves 256-color palette index (0-255).
     * 0-15: ANSI 16 palette
     * 16-231: 6x6x6 color cube
     * 232-255: 24 grayscale levels
     */
    public static int get256(int index) {
        if (index < 0 || index > 255) {
            return themedForeground();
        }
        if (index < 16) {
            return getAnsi16(index);
        }
        if (index < 232) {
            int cubeIndex = index - 16;
            int r = cubeIndex / 36;
            int g = (cubeIndex / 6) % 6;
            int b = cubeIndex % 6;
            return 0xFF000000 | (CUBE_STEPS[r] << 16) | (CUBE_STEPS[g] << 8) | CUBE_STEPS[b];
        }
        int grayLevel = 8 + (index - 232) * 10;
        return 0xFF000000 | (grayLevel << 16) | (grayLevel << 8) | grayLevel;
    }

    /**
     * Creates 24-bit TrueColor ARGB integer from RGB values.
     */
    public static int getTrueColor(int r, int g, int b) {
        int red = Math.max(0, Math.min(255, r));
        int green = Math.max(0, Math.min(255, g));
        int blue = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
