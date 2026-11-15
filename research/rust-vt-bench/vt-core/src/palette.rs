//! Dracula ANSI palette, numerically identical to
//! `app/src/main/java/io/github/nyzeep/orchestella/DraculaPalette.java`.
//!
//! Colors are stored as `0xFFRRGGBB`. The Java class also supports runtime theme
//! overrides via `setPaletteOverride`; the prototype deliberately keeps the built-in
//! Dracula set only (the override is a UI concern, not a VT semantic).

pub const BACKGROUND: u32 = 0xFF28_2A36;
pub const FOREGROUND: u32 = 0xFFF8_F8F2;

pub const COLOR_BLACK: u32 = 0xFF21_222C;
pub const COLOR_RED: u32 = 0xFFFF_5555;
pub const COLOR_GREEN: u32 = 0xFF50_FA7B;
pub const COLOR_YELLOW: u32 = 0xFFF1_FA8C;
pub const COLOR_BLUE: u32 = 0xFFBD_93F9;
pub const COLOR_MAGENTA: u32 = 0xFFFF_79C6;
pub const COLOR_CYAN: u32 = 0xFF8B_E9FD;
pub const COLOR_WHITE: u32 = 0xFFF8_F8F2;

pub const COLOR_BRIGHT_BLACK: u32 = 0xFF62_72A4;
pub const COLOR_BRIGHT_RED: u32 = 0xFFFF_6E6E;
pub const COLOR_BRIGHT_GREEN: u32 = 0xFF69_FF94;
pub const COLOR_BRIGHT_YELLOW: u32 = 0xFFFF_FFA5;
pub const COLOR_BRIGHT_BLUE: u32 = 0xFFD6_ACFF;
pub const COLOR_BRIGHT_MAGENTA: u32 = 0xFFFF_92DF;
pub const COLOR_BRIGHT_CYAN: u32 = 0xFFA4_FFFF;
pub const COLOR_BRIGHT_WHITE: u32 = 0xFFFF_FFFF;

const ANSI_16: [u32; 16] = [
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
    COLOR_BRIGHT_WHITE,
];

const CUBE_STEPS: [u32; 6] = [0, 95, 135, 175, 215, 255];

/// Resolves an ANSI 16 color index (0-15). Out-of-range indices fall back to the
/// default foreground, exactly like the Java implementation.
pub fn ansi16(index: u32) -> u32 {
    ANSI_16.get(index as usize).copied().unwrap_or(FOREGROUND)
}

/// Resolves a 256-color palette index: 0-15 ANSI, 16-231 6x6x6 cube, 232-255 grayscale.
pub fn color256(index: u32) -> u32 {
    if index > 255 {
        return FOREGROUND;
    }
    if index < 16 {
        return ansi16(index);
    }
    if index < 232 {
        let cube = index - 16;
        let r = cube / 36;
        let g = (cube / 6) % 6;
        let b = cube % 6;
        return 0xFF00_0000 | (CUBE_STEPS[r as usize] << 16) | (CUBE_STEPS[g as usize] << 8) | CUBE_STEPS[b as usize];
    }
    let gray = 8 + (index - 232) * 10;
    0xFF00_0000 | (gray << 16) | (gray << 8) | gray
}

/// Builds a 24-bit color, clamping each channel to 0..=255 like `getTrueColor`.
pub fn truecolor(r: i32, g: i32, b: i32) -> u32 {
    0xFF00_0000
        | ((r.clamp(0, 255) as u32) << 16)
        | ((g.clamp(0, 255) as u32) << 8)
        | (b.clamp(0, 255) as u32)
}
