package io.github.nyzeep.orchestella.terminal;

/** User-owned viewport intent, independent of Android's focus/layout scroll callbacks. */
public final class TerminalScrollState {
    public enum Mode { FOLLOWING, READING }
    private Mode mode = Mode.FOLLOWING;
    private boolean touching;
    private boolean selecting;
    private int anchorY;
    private long version;

    public Mode mode() { return mode; }
    public long version() { return version; }
    public boolean isTouching() { return touching; }
    public boolean isSelecting() { return selecting; }
    public boolean isFollowing() { return mode == Mode.FOLLOWING; }

    public void beginTouch(int scrollY) {
        touching = true;
        anchorY = Math.max(0, scrollY);
        version++;
    }

    public void userScrolled(int scrollY, int maxScrollY, int threshold) {
        anchorY = Math.max(0, scrollY);
        mode = !selecting && maxScrollY - anchorY <= Math.max(0, threshold) ? Mode.FOLLOWING : Mode.READING;
        version++;
    }

    public void endTouch(int scrollY, int maxScrollY, int threshold) {
        userScrolled(scrollY, maxScrollY, threshold);
        touching = false;
    }

    public void selectionChanged(boolean selected, int scrollY) {
        selecting = selected;
        anchorY = Math.max(0, scrollY);
        if (selected) mode = Mode.READING;
        version++;
    }

    public void follow() {
        mode = Mode.FOLLOWING;
        version++;
    }

    /** Capture once before a text replacement; incidental focus scrolls must not recapture it. */
    public void captureAnchor(int scrollY) {
        if (!touching && !selecting) anchorY = Math.max(0, scrollY);
    }

    public void rowsEvicted(long rows, int lineHeight) {
        long pixels = Math.max(0L, rows) * Math.max(1, lineHeight);
        anchorY = (int) Math.max(0L, (long) anchorY - pixels);
    }

    public int targetY(int maxScrollY, boolean alternateScreen) {
        if (touching || selecting) return -1;
        return alternateScreen || isFollowing() ? Math.max(0, maxScrollY)
                : Math.min(anchorY, Math.max(0, maxScrollY));
    }

    /** A user's clear starts a fresh viewport, not a return to old boot history. */
    public void displayCleared() {
        mode = Mode.FOLLOWING;
        anchorY = 0;
        version++;
    }
}
