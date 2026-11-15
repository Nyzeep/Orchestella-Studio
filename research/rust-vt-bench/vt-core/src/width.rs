//! Character width approximation of the Java implementation.
//!
//! Java uses `Character.getType` to detect NON_SPACING_MARK / ENCLOSING_MARK /
//! FORMAT. Rust has no Unicode tables in std and this prototype is dependency-free,
//! so the exact same East-Asian wide ranges are copied verbatim and zero width is
//! decided by a hand-written table (see `is_zero_width`). This is an approximation:
//! a handful of assorted combining marks outside the listed blocks will be treated
//! as width 1, unlike Java.

/// Display width in terminal cells: 0 (combining/format), 1, or 2 (East Asian wide).
pub fn character_width(cp: u32) -> u8 {
    if is_zero_width(cp) {
        return 0;
    }
    if is_wide(cp) {
        2
    } else {
        1
    }
}

/// East Asian Wide / Fullwidth ranges, copied verbatim from
/// `TerminalScreenModel.characterWidth`.
fn is_wide(cp: u32) -> bool {
    cp >= 0x1100
        && (cp <= 0x115F
            || cp == 0x2329
            || cp == 0x232A
            || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
            || (cp >= 0xAC00 && cp <= 0xD7A3)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE10 && cp <= 0xFE19)
            || (cp >= 0xFE30 && cp <= 0xFE6F)
            || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6)
            || (cp >= 0x1F300 && cp <= 0x1FAFF)
            || (cp >= 0x20000 && cp <= 0x3FFFD))
}

/// Approximates `Character.getType(cp) in {NSM, ENCLOSING_MARK, FORMAT}`.
fn is_zero_width(cp: u32) -> bool {
    // Combining diacritical marks and the blocks Java classifies as NON_SPACING_MARK
    // in the ranges that matter for real terminal traffic.
    matches!(cp,
        0x0300..=0x036F
        | 0x0488..=0x0489 // enclosing marks
        | 0x1AB0..=0x1AFF
        | 0x1DC0..=0x1DFF
        | 0x20D0..=0x20FF // includes enclosing marks 20DD-20E4
        | 0xFE20..=0xFE2F
        | 0xA670..=0xA672 // enclosing marks
    )
    // Variation selectors behave as zero width.
    || matches!(cp, 0xFE00..=0xFE0F | 0xE0100..=0xE01EF)
    // ZWJ / ZWNJ.
    || cp == 0x200C
    || cp == 0x200D
    // FORMAT characters commonly emitted by terminals.
    || matches!(cp,
        0x00AD
        | 0x061C
        | 0x180E
        | 0x200B..=0x200F
        | 0x202A..=0x202E
        | 0x2060..=0x2064
        | 0x2066..=0x206F
        | 0xFEFF
        | 0xFFF9..=0xFFFB
        | 0x1D173..=0x1D17A
    )
}
