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

    public TerminalSpan(String text, Integer foregroundColor, Integer backgroundColor,
                        boolean bold, boolean italic, boolean underline, boolean inverse) {
        this.text = Objects.requireNonNull(text, "text");
        this.foregroundColor = foregroundColor;
        this.backgroundColor = backgroundColor;
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
        this.inverse = inverse;
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
     */
    public int getEffectiveForegroundColor() {
        if (inverse) {
            return backgroundColor != null ? backgroundColor : DraculaPalette.BACKGROUND;
        }
        return foregroundColor != null ? foregroundColor : DraculaPalette.FOREGROUND;
    }

    /**
     * Resolves the effective background color, taking Dracula theme defaults and inverse into account.
     * Returns null if not explicitly set and not inversed.
     */
    public Integer getEffectiveBackgroundColor() {
        if (inverse) {
            return foregroundColor != null ? foregroundColor : DraculaPalette.FOREGROUND;
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
                Objects.equals(text, that.text) &&
                Objects.equals(foregroundColor, that.foregroundColor) &&
                Objects.equals(backgroundColor, that.backgroundColor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, foregroundColor, backgroundColor, bold, italic, underline, inverse);
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
                '}';
    }
}
