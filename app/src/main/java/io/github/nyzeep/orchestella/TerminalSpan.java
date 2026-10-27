package io.github.nyzeep.orchestella;

import java.util.Objects;

/**
 * Immutable styled span of text parsed from an ANSI stream.
 */
public final class TerminalSpan {

    private final String text;
    private final Integer foregroundColor;
    private final Integer backgroundColor;
    private final boolean bold;
    private final boolean italic;
    private final boolean underline;
    private final boolean inverse;
    private final boolean clearScreen;

    public TerminalSpan(String text, Integer foregroundColor, Integer backgroundColor,
                        boolean bold, boolean italic, boolean underline, boolean inverse) {
        this(text, foregroundColor, backgroundColor, bold, italic, underline, inverse, false);
    }

    public TerminalSpan(String text, Integer foregroundColor, Integer backgroundColor,
                        boolean bold, boolean italic, boolean underline, boolean inverse,
                        boolean clearScreen) {
        this.text = Objects.requireNonNull(text, "text");
        this.foregroundColor = foregroundColor;
        this.backgroundColor = backgroundColor;
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
        this.inverse = inverse;
        this.clearScreen = clearScreen;
    }

    public static TerminalSpan clearScreen() {
        return new TerminalSpan("", null, null, false, false, false, false, true);
    }

    public boolean isClearScreen() {
        return clearScreen;
    }

    public String getText() {
        return text;
    }

    public Integer getForegroundColor() {
        return foregroundColor;
    }

    public Integer getBackgroundColor() {
        return backgroundColor;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isItalic() {
        return italic;
    }

    public boolean isUnderline() {
        return underline;
    }

    public boolean isInverse() {
        return inverse;
    }

    /**
     * Resolves the effective foreground color, taking Dracula theme defaults and inverse into account.
     *
     * <p>Bold runs with no explicit color (typical of systemd/boot logs) are softened 25%
     * toward the terminal background: the bold font weight already emphasizes them, and the
     * raw near-white foreground made such logs read as glaring light blocks on dark themes
     * (user-reported, session 17). Explicit colors and plain text keep the exact palette.
     */
    public int getEffectiveForegroundColor() {
        if (inverse) {
            return backgroundColor != null ? backgroundColor : DraculaPalette.themedBackground();
        }
        if (foregroundColor != null) {
            return foregroundColor;
        }
        int themed = DraculaPalette.themedForeground();
        return bold ? DraculaPalette.softenForeground(themed) : themed;
    }

    /**
     * Resolves the effective background color, taking Dracula theme defaults and inverse into account.
     * Returns null if not explicitly set and not inversed.
     */
    public Integer getEffectiveBackgroundColor() {
        if (inverse) {
            return foregroundColor != null ? foregroundColor : DraculaPalette.themedForeground();
        }
        return backgroundColor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TerminalSpan that = (TerminalSpan) o;
        return bold == that.bold &&
                italic == that.italic &&
                underline == that.underline &&
                inverse == that.inverse &&
                clearScreen == that.clearScreen &&
                Objects.equals(text, that.text) &&
                Objects.equals(foregroundColor, that.foregroundColor) &&
                Objects.equals(backgroundColor, that.backgroundColor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, foregroundColor, backgroundColor, bold, italic, underline, inverse, clearScreen);
    }

    @Override
    public String toString() {
        return "TerminalSpan{" +
                "text='" + text + '\'' +
                ", fg=" + (foregroundColor != null ? String.format("#%08X", foregroundColor) : "null") +
                ", bg=" + (backgroundColor != null ? String.format("#%08X", backgroundColor) : "null") +
                ", bold=" + bold +
                ", italic=" + italic +
                ", underline=" + underline +
                ", inverse=" + inverse +
                ", clearScreen=" + clearScreen +
                '}';
    }
}
