package io.github.nyzeep.orchestella.terminal;

/** Local, uncommitted IME text. It never mirrors or stores the guest's command line. */
public final class ImeCompositionBuffer {
    private final StringBuilder text = new StringBuilder();
    private int cursor;

    public void replace(CharSequence value, int newCursorPosition) {
        text.setLength(0);
        if (value != null) text.append(value);
        cursor = clamp(newCursorPosition > 0
                ? text.length() + newCursorPosition - 1 : newCursorPosition);
    }

    public boolean select(int start, int end) {
        if (start != end || start < 0 || end > text.length()) return false;
        cursor = end;
        return true;
    }

    public String before(int count) {
        return text.substring(Math.max(0, cursor - Math.max(0, count)), cursor);
    }

    public String after(int count) {
        return text.substring(cursor, (int) Math.min(text.length(), (long) cursor + Math.max(0, count)));
    }

    /** Removes composition first and returns only the remaining guest deletion counts. */
    public int[] delete(int before, int after, boolean codePoints) {
        int left = codePoints ? text.codePointCount(0, cursor) : cursor;
        int right = codePoints ? text.codePointCount(cursor, text.length()) : text.length() - cursor;
        int consumedBefore = Math.min(before, left);
        int consumedAfter = Math.min(after, right);
        int start = codePoints ? text.offsetByCodePoints(cursor, -consumedBefore) : cursor - consumedBefore;
        int end = codePoints ? text.offsetByCodePoints(cursor, consumedAfter) : cursor + consumedAfter;
        // A UTF-16 deletion must not leave half of a supplementary character behind.
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) start--;
        if (end > 0 && end < text.length() && Character.isLowSurrogate(text.charAt(end))) end++;
        text.delete(start, end);
        cursor = start;
        return new int[]{before - consumedBefore, after - consumedAfter};
    }

    public String value() { return text.toString(); }
    public void clear() { text.setLength(0); cursor = 0; }
    private int clamp(int position) { return Math.max(0, Math.min(position, text.length())); }
}
