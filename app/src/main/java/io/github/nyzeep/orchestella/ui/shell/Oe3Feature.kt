package io.github.nyzeep.orchestella.ui.shell

/**
 * Shell switch (ticket UI3-12): the Compose shell is the product's only
 * interface. The debug-only `--ez oe3` intent extra and the saved-state
 * suppression are removed together with the legacy XML shell — there is no
 * old shell left to fall back to, so a "rollback" extra would be dead weight.
 *
 * `enabled` stays as a named seam instead of scattered `true` literals so the
 * one authoritative answer has a symbol to grep for. A launcher that still
 * sends `--ez oe3 true` (the documented smoke command from earlier tickets)
 * keeps working: the extra is simply ignored, never read.
 */
object Oe3Feature {

    /** The Compose shell is unconditional since UI3-12 flipped the default. */
    fun enabled(): Boolean = true
}
