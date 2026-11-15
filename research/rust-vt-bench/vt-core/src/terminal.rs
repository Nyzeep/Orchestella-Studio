//! Bounded VT screen model, semantically modelled on the Java
//! `TerminalScreenModel` / `AnsiEscapeParser` pair used by Orchestella.
//!
//! The prototype intentionally mirrors the Java behaviour (including its quirks)
//! rather than the "correct" VT spec: the goal is to answer whether a Rust core
//! can replace the Kotlin/Java path without changing what users see.
//!
//! v2 uses a compact value cell (32 bytes, no per-cell heap allocation) so the
//! hot path never allocates per character. Grapheme continuations are the only
//! side allocation and only happen for combining marks.

use std::collections::VecDeque;

use crate::palette::{ansi16, color256, truecolor};
use crate::width::character_width;

const MAX_CONTROL_CHARS: usize = 4096;
const MAX_HISTORY_CELLS: usize = 100_000;
const MAX_HISTORY_ROWS: usize = 2000;
const MAX_OSC_UTF16: usize = 12_000;

/// Sentinel used for "default foreground/background" in [`Cell`] and `copy_rows`.
pub const COLOR_SENTINEL: u32 = 0xFFFF_FFFF;

pub const ATTR_BOLD: u8 = 1 << 0;
pub const ATTR_ITALIC: u8 = 1 << 1;
pub const ATTR_UNDERLINE: u8 = 1 << 2;
pub const ATTR_INVERSE: u8 = 1 << 3;

const STATE_GROUND: u8 = 0;
const STATE_ESCAPE: u8 = 1;
const STATE_CSI: u8 = 2;
const STATE_STRING: u8 = 3;
const STATE_STRING_ESC: u8 = 4;
const STATE_CHARSET: u8 = 5;

const DEC_GRAPHICS: [char; 15] = [
    '┘', '┐', '┌', '└', '┼', '⎺', '⎻', '─', '⎼', '⎽', '├', '┤', '┴', '┬', '│',
];

/// Graphic rendition state. Colors are resolved `0xFFRRGGBB` values; `None` means
/// "terminal default" and is stored as [`COLOR_SENTINEL`] in a [`Cell`].
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Style {
    pub fg: Option<u32>,
    pub bg: Option<u32>,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
}

impl Style {
    fn is_default(&self) -> bool {
        self.fg.is_none()
            && self.bg.is_none()
            && !self.bold
            && !self.italic
            && !self.underline
            && !self.inverse
    }

    fn attrs(&self) -> u8 {
        (self.bold as u8) * ATTR_BOLD
            | (self.italic as u8) * ATTR_ITALIC
            | (self.underline as u8) * ATTR_UNDERLINE
            | (self.inverse as u8) * ATTR_INVERSE
    }
}

/// One terminal cell. `cp` is the base code point (`' '` for blanks); `width` is
/// 0 for the right half of a wide character. Combining marks live in `extra`,
/// which stays `None` unless a mark was actually attached.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Cell {
    pub cp: u32,
    pub fg: u32,
    pub bg: u32,
    pub attrs: u8,
    pub width: u8,
    pub extra: Option<Box<[u32]>>,
}

impl Cell {
    /// Default blank cell: space, default colors, no attributes, width 1.
    pub const BLANK: Cell = Cell {
        cp: 0x20,
        fg: COLOR_SENTINEL,
        bg: COLOR_SENTINEL,
        attrs: 0,
        width: 1,
        extra: None,
    };

    /// Never-written cell (Java `null`): renders as a default blank, but a
    /// combining mark targeted at it is dropped instead of attaching.
    pub const UNWRITTEN: Cell = Cell {
        cp: 0,
        fg: COLOR_SENTINEL,
        bg: COLOR_SENTINEL,
        attrs: 0,
        width: 1,
        extra: None,
    };

    /// Blank cell carrying the current SGR colors/attributes (Java `blank()`).
    pub fn blank(style: Style) -> Self {
        Cell {
            cp: 0x20,
            fg: style.fg.unwrap_or(COLOR_SENTINEL),
            bg: style.bg.unwrap_or(COLOR_SENTINEL),
            attrs: style.attrs(),
            width: 1,
            extra: None,
        }
    }

    fn ch(cp: u32, style: Style, width: u8) -> Self {
        Cell {
            cp,
            fg: style.fg.unwrap_or(COLOR_SENTINEL),
            bg: style.bg.unwrap_or(COLOR_SENTINEL),
            attrs: style.attrs(),
            width,
            extra: None,
        }
    }

    /// Right half of a wide character: no printable text, width 0.
    fn right_half(style: Style) -> Self {
        Cell {
            cp: 0,
            fg: style.fg.unwrap_or(COLOR_SENTINEL),
            bg: style.bg.unwrap_or(COLOR_SENTINEL),
            attrs: style.attrs(),
            width: 0,
            extra: None,
        }
    }

    /// Rebuilds the cell text (`cp` plus combining marks). Test/projection only.
    pub fn text(&self) -> String {
        if self.width == 0 {
            return String::new();
        }
        if self.cp == 0 {
            return " ".to_string();
        }
        let mut out = String::new();
        if let Some(ch) = char::from_u32(self.cp) {
            out.push(ch);
        }
        if let Some(extra) = &self.extra {
            for cp in extra.iter() {
                if let Some(ch) = char::from_u32(*cp) {
                    out.push(ch);
                }
            }
        }
        out
    }

    pub fn bold(&self) -> bool {
        self.attrs & ATTR_BOLD != 0
    }

    pub fn italic(&self) -> bool {
        self.attrs & ATTR_ITALIC != 0
    }

    pub fn underline(&self) -> bool {
        self.attrs & ATTR_UNDERLINE != 0
    }

    pub fn inverse(&self) -> bool {
        self.attrs & ATTR_INVERSE != 0
    }

    fn push_extra(&mut self, cp: u32) {
        match &mut self.extra {
            Some(extra) => {
                let mut values = std::mem::take(extra).into_vec();
                values.push(cp);
                self.extra = Some(values.into_boxed_slice());
            }
            None => {
                self.extra = Some(vec![cp].into_boxed_slice());
            }
        }
    }
}

struct Screen {
    lines: Vec<Vec<Cell>>,
    row: usize,
    column: usize,
    saved_row: usize,
    saved_column: usize,
    saved_style: Option<Style>,
    top: usize,
    bottom: usize,
    wrap_pending: bool,
}

impl Screen {
    fn new(cols: usize, rows: usize) -> Self {
        Screen {
            lines: vec![vec![Cell::UNWRITTEN; cols]; rows],
            row: 0,
            column: 0,
            saved_row: 0,
            saved_column: 0,
            saved_style: None,
            top: 0,
            bottom: rows - 1,
            wrap_pending: false,
        }
    }
}

/// Prototype terminal. `feed` consumes raw bytes (incremental UTF-8), `copy_rows`
/// flattens history + current screen into code points for the renderer.
pub struct Terminal {
    columns: usize,
    rows: usize,
    primary: Screen,
    alternate: Screen,
    alternate_active: bool,
    history: VecDeque<Vec<Cell>>,
    history_cells: usize,
    evicted_history_rows: u64,
    control: String,
    state: u8,
    pending_utf8: Vec<u8>,
    style: Style,
    application_cursor_keys: bool,
    bracketed_paste: bool,
    cursor_visible: bool,
    auto_wrap: bool,
    origin_mode: bool,
    insert_mode: bool,
    dec_graphics: bool,
    osc: String,
    osc_utf16_len: usize,
    osc_string: bool,
    osc_overflow: bool,
    responses: Vec<String>,
    spare_row: Option<Vec<Cell>>,
}

impl Terminal {
    /// Creates a screen; dimensions are clamped to 2..=512 x 2..=256 like the Java model.
    pub fn new(cols: usize, rows: usize) -> Self {
        let columns = cols.clamp(2, 512);
        let rows = rows.clamp(2, 256);
        Terminal {
            columns,
            rows,
            primary: Screen::new(columns, rows),
            alternate: Screen::new(columns, rows),
            alternate_active: false,
            history: VecDeque::new(),
            history_cells: 0,
            evicted_history_rows: 0,
            control: String::new(),
            state: STATE_GROUND,
            pending_utf8: Vec::with_capacity(4),
            style: Style::default(),
            application_cursor_keys: false,
            bracketed_paste: false,
            cursor_visible: true,
            auto_wrap: true,
            origin_mode: false,
            insert_mode: false,
            dec_graphics: false,
            osc: String::new(),
            osc_utf16_len: 0,
            osc_string: false,
            osc_overflow: false,
            responses: Vec::new(),
            spare_row: None,
        }
    }

    pub fn cols(&self) -> usize {
        self.columns
    }

    pub fn rows(&self) -> usize {
        self.rows
    }

    pub fn cursor_row(&self) -> usize {
        self.active().row
    }

    pub fn cursor_column(&self) -> usize {
        self.active().column
    }

    pub fn is_alternate_screen(&self) -> bool {
        self.alternate_active
    }

    pub fn is_application_cursor_keys(&self) -> bool {
        self.application_cursor_keys
    }

    pub fn is_bracketed_paste(&self) -> bool {
        self.bracketed_paste
    }

    pub fn is_cursor_visible(&self) -> bool {
        self.cursor_visible
    }

    pub fn auto_wrap(&self) -> bool {
        self.auto_wrap
    }

    pub fn origin_mode(&self) -> bool {
        self.origin_mode
    }

    pub fn insert_mode(&self) -> bool {
        self.insert_mode
    }

    /// Number of rows currently in scrollback (primary screen only).
    pub fn history_rows(&self) -> usize {
        self.history.len()
    }

    pub fn history_cells(&self) -> usize {
        self.history_cells
    }

    pub fn evicted_history_rows(&self) -> u64 {
        self.evicted_history_rows
    }

    /// First row of the visible screen in `copy_rows` index space.
    pub fn screen_start_row(&self) -> usize {
        if self.alternate_active {
            0
        } else {
            self.history.len()
        }
    }

    /// Rows a Kotlin-style full snapshot would rebuild: history plus the visible
    /// screen up to the last non-empty row (Java `snapshot()`).
    pub fn display_row_count(&self) -> usize {
        if self.alternate_active {
            return self.rows;
        }
        let screen = self.active();
        let mut last = screen.row;
        for row in (last + 1..self.rows).rev() {
            if line_length(&screen.lines[row]) > 0 {
                last = row;
                break;
            }
        }
        self.history.len() + last + 1
    }

    pub fn style(&self) -> Style {
        self.style
    }

    /// Current cell of the visible screen.
    pub fn cell(&self, row: usize, col: usize) -> Option<&Cell> {
        self.active().lines.get(row)?.get(col)
    }

    /// Visible row as text: wide-character right halves are skipped and trailing
    /// spaces are trimmed (mirrors the Java test helper `line()`).
    pub fn line_text(&self, row: usize) -> String {
        let mut out = String::new();
        for cell in &self.active().lines[row] {
            if cell.width != 0 {
                out.push_str(&cell.text());
            }
        }
        out.trim_end().to_string()
    }

    /// History row as text (index 0 = oldest retained row).
    pub fn history_line_text(&self, row: usize) -> String {
        let mut out = String::new();
        if let Some(line) = self.history.get(row) {
            for cell in line {
                if cell.width != 0 {
                    out.push_str(&cell.text());
                }
            }
        }
        out.trim_end().to_string()
    }

    /// Drains queued terminal responses (`CSI 6n`, `CSI 5n`, `CSI c`).
    pub fn take_responses(&mut self) -> Vec<String> {
        std::mem::take(&mut self.responses)
    }

    fn active(&self) -> &Screen {
        if self.alternate_active {
            &self.alternate
        } else {
            &self.primary
        }
    }

    fn active_mut(&mut self) -> &mut Screen {
        if self.alternate_active {
            &mut self.alternate
        } else {
            &mut self.primary
        }
    }

    fn fill_row(&mut self, row: usize) {
        let style = self.style;
        let line = &mut self.active_mut().lines[row];
        if style.is_default() {
            line.fill(Cell::BLANK);
        } else {
            line.fill(Cell::blank(style));
        }
    }

    /// Full reset (RIS / `ESC c`). Scrollback is discarded.
    pub fn reset(&mut self) {
        self.primary = Screen::new(self.columns, self.rows);
        self.alternate = Screen::new(self.columns, self.rows);
        self.alternate_active = false;
        self.history.clear();
        self.history_cells = 0;
        self.evicted_history_rows = 0;
        self.style = Style::default();
        self.state = STATE_GROUND;
        self.control.clear();
        self.pending_utf8.clear();
        self.application_cursor_keys = false;
        self.bracketed_paste = false;
        self.origin_mode = false;
        self.insert_mode = false;
        self.dec_graphics = false;
        self.cursor_visible = true;
        self.auto_wrap = true;
        self.osc.clear();
        self.osc_utf16_len = 0;
        self.osc_string = false;
        self.osc_overflow = false;
        self.spare_row = None;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /// Feeds raw bytes. A multi-byte UTF-8 character may span calls; incomplete
    /// sequences are buffered, invalid ones become U+FFFD.
    pub fn feed(&mut self, bytes: &[u8]) {
        if self.pending_utf8.is_empty() {
            self.decode(bytes);
            return;
        }

        let lead = self.pending_utf8[0];
        let need = utf8_sequence_len(lead).max(1);
        let take = need.saturating_sub(self.pending_utf8.len()).min(bytes.len());
        let mut buf = std::mem::take(&mut self.pending_utf8);
        buf.extend_from_slice(&bytes[..take]);
        self.decode(&buf);
        self.decode(&bytes[take..]);
    }

    fn decode(&mut self, buf: &[u8]) {
        let mut i = 0;
        while i < buf.len() {
            match std::str::from_utf8(&buf[i..]) {
                Ok(text) => {
                    for ch in text.chars() {
                        self.accept(ch as u32);
                    }
                    return;
                }
                Err(err) => {
                    let valid = err.valid_up_to();
                    if valid > 0 {
                        if let Ok(text) = std::str::from_utf8(&buf[i..i + valid]) {
                            for ch in text.chars() {
                                self.accept(ch as u32);
                            }
                        }
                        i += valid;
                    }
                    match err.error_len() {
                        Some(len) => {
                            self.accept(0xFFFD);
                            i += len;
                        }
                        None => {
                            self.pending_utf8.clear();
                            self.pending_utf8.extend_from_slice(&buf[i..]);
                            return;
                        }
                    }
                }
            }
        }
    }

    fn accept(&mut self, cp: u32) {
        // Inside OSC/DCS/APC/PM everything is swallowed until BEL or ST.
        if self.state == STATE_STRING || self.state == STATE_STRING_ESC {
            if cp == 0x07 || (self.state == STATE_STRING_ESC && cp == 0x5C) {
                self.state = STATE_GROUND;
                self.osc.clear();
                self.osc_utf16_len = 0;
                self.osc_string = false;
                self.osc_overflow = false;
            } else if cp == 0x1B {
                self.state = STATE_STRING_ESC;
            } else {
                self.state = STATE_STRING;
                if self.osc_string && !self.osc_overflow {
                    let ch = char::from_u32(cp).unwrap_or('\u{FFFD}');
                    let units = ch.len_utf16();
                    if self.osc_utf16_len + units <= MAX_OSC_UTF16 {
                        self.osc.push(ch);
                        self.osc_utf16_len += units;
                    } else {
                        self.osc_overflow = true;
                        self.osc.clear();
                        self.osc_utf16_len = 0;
                    }
                }
            }
            return;
        }
        if cp == 24 || cp == 26 {
            // CAN / SUB abort any sequence in progress.
            self.state = STATE_GROUND;
            self.control.clear();
            return;
        }
        if cp == 0x1B {
            self.state = STATE_ESCAPE;
            self.control.clear();
            return;
        }
        if self.state == STATE_ESCAPE {
            self.state = STATE_GROUND;
            match cp {
                0x5B => self.state = STATE_CSI, // [
                0x5D => {
                    // ]
                    self.state = STATE_STRING;
                    self.osc_string = true;
                    self.osc_overflow = false;
                    self.osc.clear();
                    self.osc_utf16_len = 0;
                }
                0x50 | 0x5E | 0x5F => {
                    // P ^ _
                    self.state = STATE_STRING;
                    self.osc_string = false;
                }
                0x28 | 0x29 => self.state = STATE_CHARSET, // ( )
                0x37 => self.save_cursor(),                 // 7
                0x38 => self.restore_cursor(),              // 8
                0x44 => self.index(),                       // D
                0x45 => {
                    // E
                    self.active_mut().column = 0;
                    self.index();
                }
                0x4D => self.reverse_index(), // M
                0x63 => self.reset(),         // c
                _ => {}
            }
            return;
        }
        if self.state == STATE_CHARSET {
            self.dec_graphics = cp == 0x30; // '0'
            self.state = STATE_GROUND;
            return;
        }
        if self.state == STATE_CSI {
            if (0x40..=0x7E).contains(&cp) {
                let params = std::mem::take(&mut self.control);
                self.state = STATE_GROUND;
                self.csi(char::from_u32(cp).unwrap_or('\u{FFFD}'), &params);
                self.control = params;
                self.control.clear();
            } else if (0x20..=0x3F).contains(&cp) {
                if self.control.len() < MAX_CONTROL_CHARS {
                    if let Some(ch) = char::from_u32(cp) {
                        self.control.push(ch);
                    }
                }
            } else if cp < 0x20 {
                self.control_character(cp);
            } else {
                self.state = STATE_GROUND;
                self.control.clear();
            }
            return;
        }
        if cp < 0x20 || cp == 0x7F {
            self.control_character(cp);
        } else {
            self.put(cp);
        }
    }

    fn control_character(&mut self, cp: u32) {
        match cp {
            0x0D => {
                let screen = self.active_mut();
                screen.column = 0;
                screen.wrap_pending = false;
            }
            0x0A | 0x0B | 0x0C => self.index(),
            0x08 => {
                let screen = self.active_mut();
                screen.column = screen.column.saturating_sub(1);
                screen.wrap_pending = false;
            }
            0x09 => {
                let columns = self.columns;
                let screen = self.active_mut();
                screen.column = ((screen.column / 8 + 1) * 8).min(columns - 1);
                screen.wrap_pending = false;
            }
            14 => self.dec_graphics = true,
            15 => self.dec_graphics = false,
            _ => {}
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    fn put(&mut self, cp: u32) {
        let cp = if self.dec_graphics && (0x6A..=0x78).contains(&cp) {
            DEC_GRAPHICS[(cp - 0x6A) as usize] as u32
        } else {
            cp
        };
        let mut width = character_width(cp) as usize;

        if width == 0 {
            // Attach the combining mark to its host cell (skipping right halves).
            let wrap = self.active().wrap_pending;
            let column = self.active().column;
            let mut col = if wrap { column as isize } else { column as isize - 1 };
            while col > 0 {
                let cell = &self.active().lines[self.active().row][col as usize];
                if cell.width == 0 {
                    col -= 1;
                } else {
                    break;
                }
            }
            if col >= 0 {
                let row = self.active().row;
                let host = &mut self.active_mut().lines[row][col as usize];
                // Java drops marks aimed at a null cell; a blank/written cell accepts them.
                if host.cp != 0 || host.width != 1 {
                    host.push_extra(cp);
                }
            }
            return;
        }

        let columns = self.columns;
        let mut col = self.active().column;
        if (self.active().wrap_pending || (width == 2 && col == columns - 1)) && self.auto_wrap {
            self.active_mut().column = 0;
            self.index();
            col = 0;
        }
        self.active_mut().wrap_pending = false;
        if width == 2 && col == columns - 1 {
            width = 1;
        }
        if self.insert_mode {
            self.shift_characters(width, true);
        }

        let style = self.style;
        let row = self.active().row;
        {
            let line = &mut self.active_mut().lines[row];
            clear_wide_cell(line, col, columns);
            if width == 2 {
                clear_wide_cell(line, col + 1, columns);
            }
            line[col] = Cell::ch(cp, style, width as u8);
            if width == 2 {
                line[col + 1] = Cell::right_half(style);
            }
        }

        if col + width >= columns {
            let auto_wrap = self.auto_wrap;
            let screen = self.active_mut();
            screen.column = columns - 1;
            screen.wrap_pending = auto_wrap;
        } else {
            self.active_mut().column = col + width;
        }
    }

    fn index(&mut self) {
        self.active_mut().wrap_pending = false;
        let (row, bottom) = {
            let screen = self.active();
            (screen.row, screen.bottom)
        };
        if row == bottom {
            self.scroll(1, true);
        } else {
            let row = (row + 1).min(self.rows - 1);
            self.active_mut().row = row;
        }
    }

    fn reverse_index(&mut self) {
        self.active_mut().wrap_pending = false;
        let (row, top) = {
            let screen = self.active();
            (screen.row, screen.top)
        };
        if row == top {
            self.scroll(1, false);
        } else {
            let row = row.saturating_sub(1);
            self.active_mut().row = row;
        }
    }

    fn scroll(&mut self, count: usize, up: bool) {
        let (top, bottom) = {
            let screen = self.active();
            (screen.top, screen.bottom)
        };
        let count = count.min(bottom - top + 1);
        let full_primary = !self.alternate_active && top == 0 && bottom == self.rows - 1;
        for _ in 0..count {
            if up {
                let removed = self.active_mut().lines.remove(top);
                if full_primary {
                    self.add_history(removed);
                } else {
                    self.stash_spare(removed);
                }
                let blank = self.take_blank_row();
                self.active_mut().lines.insert(bottom, blank);
            } else {
                let removed = self.active_mut().lines.remove(bottom);
                self.stash_spare(removed);
                let blank = self.take_blank_row();
                self.active_mut().lines.insert(top, blank);
            }
        }
    }

    /// Recycles one scrolled-out row instead of allocating a fresh one per line.
    fn stash_spare(&mut self, row: Vec<Cell>) {
        if row.len() == self.columns && self.spare_row.is_none() {
            self.spare_row = Some(row);
        }
    }

    fn take_blank_row(&mut self) -> Vec<Cell> {
        let mut row = match self.spare_row.take() {
            Some(row) if row.len() == self.columns => row,
            _ => vec![Cell::BLANK; self.columns],
        };
        if self.style.is_default() {
            row.fill(Cell::BLANK);
        } else {
            row.fill(Cell::blank(self.style));
        }
        row
    }

    fn add_history(&mut self, line: Vec<Cell>) {
        self.history_cells += line.len();
        self.history.push_back(line);
        while self.history_cells > MAX_HISTORY_CELLS || self.history.len() > MAX_HISTORY_ROWS {
            match self.history.pop_front() {
                Some(old) => {
                    self.history_cells -= old.len();
                    self.evicted_history_rows += 1;
                    self.stash_spare(old);
                }
                None => break,
            }
        }
    }

    fn shift_characters(&mut self, count: usize, insert: bool) {
        let (row, column) = {
            let screen = self.active();
            (screen.row, screen.column)
        };
        let columns = self.columns;
        let count = count.min(columns - column);
        let blank = Cell::blank(self.style);
        {
            let line = &mut self.active_mut().lines[row];
            if insert {
                line[column..columns].rotate_right(count);
                line[column..column + count].fill(blank.clone());
            } else {
                line[column..columns].rotate_left(count);
                line[columns - count..columns].fill(blank.clone());
            }
        }
        // Remove orphan wide-cell halves after shifting at a boundary.
        let line = &mut self.active_mut().lines[row];
        for col in 0..columns {
            if line[col].width == 0 && (col == 0 || line[col - 1].width != 2) {
                line[col] = blank.clone();
            }
            if line[col].width == 2 && (col + 1 == columns || line[col + 1].width != 0) {
                line[col] = blank.clone();
            }
        }
    }

    // ------------------------------------------------------------------
    // CSI
    // ------------------------------------------------------------------

    fn csi(&mut self, command: char, raw: &str) {
        let private_mode = raw.starts_with('?');
        let clean = if private_mode || raw.starts_with('>') {
            &raw[1..]
        } else {
            raw
        };

        if command == 'm' && !private_mode && !raw.starts_with('>') {
            self.apply_sgr(raw);
            return;
        }
        if command == 'h' || command == 'l' {
            for value in clean.split(';') {
                self.mode(parse(value, 0), private_mode, command == 'h');
            }
            return;
        }

        let n = parameter(clean, 0, 1);
        let top = if self.origin_mode { self.active().top } else { 0 };
        let bottom = if self.origin_mode { self.active().bottom } else { self.rows - 1 };
        let columns = self.columns;

        match command {
            'A' => {
                let row = (self.active().row as i64 - n).max(top as i64) as usize;
                self.active_mut().row = row;
            }
            'B' | 'e' => {
                let row = (self.active().row as i64 + n).min(bottom as i64) as usize;
                self.active_mut().row = row;
            }
            'C' | 'a' => {
                let column = (self.active().column as i64 + n).min(columns as i64 - 1) as usize;
                self.active_mut().column = column;
            }
            'D' => {
                let column = (self.active().column as i64 - n).max(0) as usize;
                self.active_mut().column = column;
            }
            'E' => {
                let row = (self.active().row as i64 + n).min(bottom as i64) as usize;
                let screen = self.active_mut();
                screen.row = row;
                screen.column = 0;
            }
            'F' => {
                let row = (self.active().row as i64 - n).max(top as i64) as usize;
                let screen = self.active_mut();
                screen.row = row;
                screen.column = 0;
            }
            'G' | '`' => {
                let column = (n - 1).clamp(0, columns as i64 - 1) as usize;
                self.active_mut().column = column;
            }
            'd' => {
                let row = (top as i64 + n - 1).clamp(top as i64, bottom as i64) as usize;
                self.active_mut().row = row;
            }
            'H' | 'f' => {
                let row = (top as i64 + n - 1).clamp(top as i64, bottom as i64) as usize;
                let column = (parameter(clean, 1, 1) - 1).clamp(0, columns as i64 - 1) as usize;
                let screen = self.active_mut();
                screen.row = row;
                screen.column = column;
            }
            'J' => self.erase_display(parse(first_param(clean), 0)),
            'K' => self.erase_line(parse(first_param(clean), 0)),
            'X' => {
                let row = self.active().row;
                let column = self.active().column;
                let to = ((column as i64) + n).min(columns as i64) as usize;
                let style = self.style;
                let line = &mut self.active_mut().lines[row];
                erase(line, column, to, style);
            }
            '@' => self.shift_characters(n as usize, true),
            'P' => self.shift_characters(n as usize, false),
            'L' | 'M' => {
                let (row, top, bottom) = {
                    let screen = self.active();
                    (screen.row, screen.top, screen.bottom)
                };
                if row >= top && row <= bottom {
                    let saved_top = top;
                    self.active_mut().top = row;
                    self.scroll(n as usize, command == 'M');
                    self.active_mut().top = saved_top;
                }
            }
            'S' => self.scroll(n as usize, true),
            'T' => self.scroll(n as usize, false),
            'r' => {
                let first = (n - 1).clamp(0, self.rows as i64 - 1) as usize;
                let last = (parameter(clean, 1, self.rows as i64) - 1)
                    .clamp(0, self.rows as i64 - 1) as usize;
                if !private_mode && first < last {
                    let origin = self.origin_mode;
                    let screen = self.active_mut();
                    screen.top = first;
                    screen.bottom = last;
                    screen.row = if origin { first } else { 0 };
                    screen.column = 0;
                }
            }
            's' => self.save_cursor(),
            'u' => self.restore_cursor(),
            'n' => {
                if raw == "5" {
                    self.responses.push("\u{1b}[0n".to_string());
                }
                if raw == "6" {
                    let response =
                        format!("\u{1b}[{};{}R", self.active().row + 1, self.active().column + 1);
                    self.responses.push(response);
                }
            }
            'c' => {
                if raw.is_empty() || raw == "0" {
                    self.responses.push("\u{1b}[?1;2c".to_string());
                }
            }
            _ => {}
        }
        self.active_mut().wrap_pending = false;
    }

    fn mode(&mut self, mode: i64, private_mode: bool, enabled: bool) {
        if !private_mode {
            if mode == 4 {
                self.insert_mode = enabled;
            }
            return;
        }
        match mode {
            1 => self.application_cursor_keys = enabled,
            6 => {
                let top = self.active().top;
                self.origin_mode = enabled;
                let screen = self.active_mut();
                screen.row = if enabled { top } else { 0 };
                screen.column = 0;
            }
            7 => self.auto_wrap = enabled,
            25 => self.cursor_visible = enabled,
            47 | 1047 | 1049 => {
                if enabled && !self.alternate_active {
                    if mode == 1049 {
                        self.save_cursor();
                    }
                    if mode != 47 {
                        self.alternate = Screen::new(self.columns, self.rows);
                    }
                    self.alternate_active = true;
                } else if !enabled && self.alternate_active {
                    self.alternate_active = false;
                    if mode == 1049 {
                        self.restore_cursor();
                    }
                }
            }
            1048 => {
                if enabled {
                    self.save_cursor();
                } else {
                    self.restore_cursor();
                }
            }
            2004 => self.bracketed_paste = enabled,
            _ => {}
        }
    }

    fn save_cursor(&mut self) {
        let style = self.style;
        let screen = self.active_mut();
        screen.saved_row = screen.row;
        screen.saved_column = screen.column;
        screen.saved_style = Some(style);
    }

    fn restore_cursor(&mut self) {
        let (saved_row, saved_column, saved_style) = {
            let screen = self.active();
            (screen.saved_row, screen.saved_column, screen.saved_style)
        };
        let rows = self.rows;
        let columns = self.columns;
        let screen = self.active_mut();
        screen.row = saved_row.min(rows - 1);
        screen.column = saved_column.min(columns - 1);
        screen.wrap_pending = false;
        if let Some(style) = saved_style {
            self.style = style;
        }
    }

    fn erase_line(&mut self, mode: i64) {
        let (row, column) = {
            let screen = self.active();
            (screen.row, screen.column)
        };
        let columns = self.columns;
        let style = self.style;
        let line = &mut self.active_mut().lines[row];
        match mode {
            0 => erase(line, column, columns, style),
            1 => erase(line, 0, column + 1, style),
            2 => erase(line, 0, columns, style),
            _ => {}
        }
    }

    fn erase_display(&mut self, mode: i64) {
        let row = self.active().row;
        let rows = self.rows;
        match mode {
            3 => {
                self.evicted_history_rows += self.history.len() as u64;
                self.history.clear();
                self.history_cells = 0;
            }
            2 => {
                for r in 0..rows {
                    self.fill_row(r);
                }
            }
            0 => {
                self.erase_line(0);
                for r in row + 1..rows {
                    self.fill_row(r);
                }
            }
            1 => {
                self.erase_line(1);
                for r in 0..row {
                    self.fill_row(r);
                }
            }
            _ => {}
        }
    }

    // ------------------------------------------------------------------
    // SGR
    // ------------------------------------------------------------------

    fn apply_sgr(&mut self, params: &str) {
        if sgr_token(params, 0).is_none() {
            self.style = Style::default();
            return;
        }
        let mut i = 0;
        while let Some(token) = sgr_token(params, i) {
            let code = parse_sgr_int(token, -1);
            if code == -1 {
                i += 1;
                continue;
            }
            match code {
                0 => self.style = Style::default(),
                1 => self.style.bold = true,
                3 => self.style.italic = true,
                4 => self.style.underline = true,
                7 => self.style.inverse = true,
                21 | 22 => self.style.bold = false,
                23 => self.style.italic = false,
                24 => self.style.underline = false,
                27 => self.style.inverse = false,
                30..=37 => self.style.fg = Some(ansi16((code - 30) as u32)),
                38 => {
                    if let Some(kind_token) = sgr_token(params, i + 1) {
                        let kind = parse_sgr_int(kind_token, -1);
                        if kind == 5 {
                            if let Some(index_token) = sgr_token(params, i + 2) {
                                let index = parse_sgr_int(index_token, -1);
                                if (0..=255).contains(&index) {
                                    self.style.fg = Some(color256(index as u32));
                                }
                                i += 2;
                            }
                        } else if kind == 2 {
                            if let (Some(r), Some(g), Some(b)) = (
                                sgr_token(params, i + 2),
                                sgr_token(params, i + 3),
                                sgr_token(params, i + 4),
                            ) {
                                self.style.fg = Some(truecolor(
                                    parse_sgr_int(r, 0),
                                    parse_sgr_int(g, 0),
                                    parse_sgr_int(b, 0),
                                ));
                                i += 4;
                            }
                        }
                    }
                }
                39 => self.style.fg = None,
                40..=47 => self.style.bg = Some(ansi16((code - 40) as u32)),
                48 => {
                    if let Some(kind_token) = sgr_token(params, i + 1) {
                        let kind = parse_sgr_int(kind_token, -1);
                        if kind == 5 {
                            if let Some(index_token) = sgr_token(params, i + 2) {
                                let index = parse_sgr_int(index_token, -1);
                                if (0..=255).contains(&index) {
                                    self.style.bg = Some(color256(index as u32));
                                }
                                i += 2;
                            }
                        } else if kind == 2 {
                            if let (Some(r), Some(g), Some(b)) = (
                                sgr_token(params, i + 2),
                                sgr_token(params, i + 3),
                                sgr_token(params, i + 4),
                            ) {
                                self.style.bg = Some(truecolor(
                                    parse_sgr_int(r, 0),
                                    parse_sgr_int(g, 0),
                                    parse_sgr_int(b, 0),
                                ));
                                i += 4;
                            }
                        }
                    }
                }
                49 => self.style.bg = None,
                90..=97 => self.style.fg = Some(ansi16(8 + (code - 90) as u32)),
                100..=107 => self.style.bg = Some(ansi16(8 + (code - 100) as u32)),
                _ => {}
            }
            i += 1;
        }
    }

    // ------------------------------------------------------------------
    // Resize
    // ------------------------------------------------------------------

    /// Preserves screen contents around the cursor; primary overflow becomes history.
    pub fn resize(&mut self, new_columns: usize, new_rows: usize) {
        let width = new_columns.clamp(2, 512);
        let height = new_rows.clamp(2, 256);
        if width == self.columns && height == self.rows {
            return;
        }
        self.spare_row = None;
        self.resize_screen(true, width, height);
        self.resize_screen(false, width, height);
        self.columns = width;
        self.rows = height;
    }

    fn resize_screen(&mut self, primary: bool, width: usize, height: usize) {
        let old_columns = self.columns;
        let mut screen = if primary {
            std::mem::replace(&mut self.primary, Screen::new(2, 2))
        } else {
            std::mem::replace(&mut self.alternate, Screen::new(2, 2))
        };

        let offset = screen.row.saturating_sub(height - 1);
        if primary {
            for row in 0..offset {
                let line = std::mem::replace(&mut screen.lines[row], Vec::new());
                self.add_history(line);
            }
        }

        let mut result: Vec<Vec<Cell>> = vec![vec![Cell::UNWRITTEN; width]; height];
        for row in 0..height {
            let source = row + offset;
            if source >= screen.lines.len() {
                break;
            }
            let copy = old_columns.min(width);
            for col in 0..copy {
                result[row][col] = std::mem::replace(&mut screen.lines[source][col], Cell::UNWRITTEN);
            }
            if width < old_columns && result[row][width - 1].width == 2 {
                result[row][width - 1] = Cell::UNWRITTEN;
            }
        }

        screen.lines = result;
        screen.row = screen.row.saturating_sub(offset).min(height - 1);
        screen.column = screen.column.min(width - 1);
        screen.saved_row = screen.saved_row.saturating_sub(offset).min(height - 1);
        screen.saved_column = screen.saved_column.min(width - 1);
        screen.top = 0;
        screen.bottom = height - 1;
        screen.wrap_pending = false;

        if primary {
            self.primary = screen;
        } else {
            self.alternate = screen;
        }
    }

    // ------------------------------------------------------------------
    // Flattening
    // ------------------------------------------------------------------

    /// Copies `count` rows starting at `first` in history+screen index space into
    /// the caller's buffers. Each row contributes exactly `cols` code points.
    ///
    /// - `first` out of range (or `count == 0`) returns 0.
    /// - Wide-character right halves write code point `0` and attr bit 5.
    /// - Multi-code-point cells keep only the base code point in `out_cp`.
    /// - Default fg/bg are written as [`COLOR_SENTINEL`].
    pub fn copy_rows(
        &self,
        first: usize,
        count: usize,
        out_cp: &mut [u32],
        out_fg: &mut [u32],
        out_bg: &mut [u32],
        out_attrs: &mut [u8],
    ) -> usize {
        let columns = self.columns;
        let history_rows = if self.alternate_active {
            0
        } else {
            self.history.len()
        };
        let total = history_rows + self.rows;
        if first >= total || count == 0 {
            return 0;
        }
        let capacity = [
            out_cp.len() / columns,
            out_fg.len() / columns,
            out_bg.len() / columns,
            out_attrs.len() / columns,
        ]
        .into_iter()
        .min()
        .unwrap_or(0);
        let rows_to_write = count.min(total - first).min(capacity);

        for row in 0..rows_to_write {
            let index = first + row;
            let line: &[Cell] = if index < history_rows {
                &self.history[index]
            } else {
                &self.active().lines[index - history_rows]
            };
            let base = row * columns;
            for col in 0..columns {
                let slot = base + col;
                let cell = &line[col];
                let mut attrs = cell.attrs;
                let cp = if cell.width == 0 {
                    attrs |= 1 << 5;
                    0
                } else {
                    if cell.width == 2 {
                        attrs |= 1 << 4;
                    }
                    if cell.cp == 0 {
                        0x20
                    } else {
                        cell.cp
                    }
                };
                out_cp[slot] = cp;
                out_fg[slot] = cell.fg;
                out_bg[slot] = cell.bg;
                out_attrs[slot] = attrs;
            }
        }
        rows_to_write
    }
}

fn line_length(line: &[Cell]) -> usize {
    for col in (0..line.len()).rev() {
        let cell = &line[col];
        if (cell.cp != 0x20 && !(cell.cp == 0 && cell.width == 1))
            || cell.width == 0
            || cell.extra.is_some()
            || cell.bg != COLOR_SENTINEL
            || (cell.attrs & ATTR_INVERSE) != 0
        {
            return col + 1;
        }
    }
    0
}

fn clear_wide_cell(line: &mut [Cell], column: usize, columns: usize) {
    let width = line[column].width;
    if width == 0 && column > 0 {
        line[column - 1] = Cell::UNWRITTEN;
    }
    if width == 2 && column + 1 < columns {
        line[column + 1] = Cell::UNWRITTEN;
    }
    line[column] = Cell::UNWRITTEN;
}

fn erase(line: &mut [Cell], from: usize, to: usize, style: Style) {
    if from >= to {
        return;
    }
    let columns = line.len();
    clear_wide_cell(line, from, columns);
    clear_wide_cell(line, to - 1, columns);
    line[from..to].fill(Cell::blank(style));
}

fn first_param(clean: &str) -> &str {
    clean.split(';').next().unwrap_or("")
}

fn param_at(clean: &str, index: usize) -> Option<&str> {
    clean.split(';').nth(index)
}

/// `parameter()`: missing/zero falls back, values are capped at 65535 (J/K use
/// `parse()` instead, which has no cap — same as the Java model).
fn parameter(clean: &str, index: usize, fallback: i64) -> i64 {
    let value = match param_at(clean, index) {
        Some(value) => parse(value, fallback),
        None => fallback,
    };
    if value == 0 {
        fallback
    } else {
        value.min(65535)
    }
}

/// `parse()`: negative values clamp to 0, non-numeric uses the fallback.
fn parse(value: &str, fallback: i64) -> i64 {
    match value.parse::<i64>() {
        Ok(value) => value.max(0),
        Err(_) => fallback,
    }
}

/// SGR token lookup: splits on `;`/`:` runs, drops empty tokens, no allocation.
fn sgr_token(params: &str, index: usize) -> Option<&str> {
    params
        .split(|c| c == ';' || c == ':')
        .filter(|token| !token.is_empty())
        .nth(index)
}

/// SGR integer parsing trims whitespace (Java uses `Integer.parseInt(s.trim())`).
fn parse_sgr_int(value: &str, fallback: i32) -> i32 {
    value.trim().parse::<i32>().unwrap_or(fallback)
}

fn utf8_sequence_len(lead: u8) -> usize {
    match lead {
        0x00..=0x7F => 1,
        0xC2..=0xDF => 2,
        0xE0..=0xEF => 3,
        0xF0..=0xF4 => 4,
        _ => 1,
    }
}
