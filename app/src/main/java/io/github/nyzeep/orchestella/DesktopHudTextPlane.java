package io.github.nyzeep.orchestella;

import java.util.Objects;

/**
 * HUD text-plane pure logic (G-10): the pre-G-10 500 ms tick did
 * {@code setText(badge) + append(statusSuffix)} every beat, rebuilding the suffix
 * string each time. This class pre-assembles the invariant suffix segments once,
 * caches the composed suffix while the status is unchanged, and gates the view
 * update on a full-text diff — the controller applies one {@code setText} only
 * when badge or status actually changed. The copy stays byte-identical
 * ({@link DesktopHudController#composeStatusSuffix} delegates here).
 */
public final class DesktopHudTextPlane {

    /** Status suffix invariant head; W1-03 corrected the stale Wayland wording to the RFB data plane. */
    static final String SUFFIX_HEAD = "\nRFB CPU 拷贝呈现通路 · ";

    /** Status suffix invariant tail; byte-identical to the pre-G-10 copy. */
    static final String SUFFIX_TAIL =
            "\n触摸使用鼠标回退；Unicode 输入未验证。帧流连接不代表 GPU 加速或设备验收";

    private String cachedSuffixStatus;
    private String cachedSuffix;
    private String lastAppliedText;

    /** The status suffix composed from the pre-assembled invariant segments. */
    public static String composeStatusSuffix(String displayStatus) {
        return SUFFIX_HEAD + displayStatus + SUFFIX_TAIL;
    }

    /**
     * The suffix for {@code displayStatus}. While the status is unchanged (the
     * per-tick steady state) the cached composition instance is reused, so no
     * string is rebuilt on the main thread.
     */
    public String statusSuffixFor(String displayStatus) {
        if (cachedSuffix != null && Objects.equals(cachedSuffixStatus, displayStatus)) {
            return cachedSuffix;
        }
        String composed = composeStatusSuffix(displayStatus);
        cachedSuffixStatus = displayStatus;
        cachedSuffix = composed;
        return composed;
    }

    /**
     * Full HUD text for one 500 ms tick (badge line + status suffix). Returns
     * {@code null} when the text is unchanged — the caller skips {@code setText}.
     */
    public String composeTickText(String badgeText, String displayStatus) {
        String full = (badgeText != null ? badgeText : "") + statusSuffixFor(displayStatus);
        if (full.equals(lastAppliedText)) {
            return null;
        }
        lastAppliedText = full;
        return full;
    }

    /**
     * The view text was replaced out-of-band (negotiation failure line); the next
     * tick must re-apply the full text even if it matches the cached value.
     */
    public void invalidate() {
        lastAppliedText = null;
    }
}
