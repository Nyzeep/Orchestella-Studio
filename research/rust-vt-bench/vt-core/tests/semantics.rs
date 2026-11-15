//! Behavioural tests mirroring `TerminalScreenModelTest.java` where the Java
//! semantics are observable, plus checks for the `copy_rows` flattening contract.

use vt_core::{palette, Terminal, COLOR_SENTINEL, NO_EXTRA};

fn term(cols: usize, rows: usize) -> Terminal {
    Terminal::new(cols, rows)
}

fn feed(terminal: &mut Terminal, text: &str) {
    terminal.feed(text.as_bytes());
}

fn line(terminal: &Terminal, row: usize) -> String {
    terminal.line_text(row)
}

fn text_at(terminal: &Terminal, row: usize, col: usize) -> String {
    terminal.cell_text(terminal.cell(row, col).unwrap())
}

// ---------------------------------------------------------------------------
// SGR
// ---------------------------------------------------------------------------

#[test]
fn sgr_basic_sets_style_and_resets() {
    let mut t = term(10, 3);
    feed(&mut t, "\x1b[1;4;31;44mX\x1b[0mY");
    let x = t.cell(0, 0).expect("X cell");
    assert!(x.bold());
    assert!(x.underline());
    assert!(!x.italic());
    assert_eq!(x.fg, palette::COLOR_RED);
    assert_eq!(x.bg, palette::COLOR_BLUE);
    let y = t.cell(0, 1).expect("Y cell");
    assert_eq!(y.fg, COLOR_SENTINEL);
    assert_eq!(y.bg, COLOR_SENTINEL);
    assert!(!y.bold() && !y.underline());
}

#[test]
fn sgr_256_truecolor_and_default_resets() {
    let mut t = term(10, 3);
    feed(&mut t, "\x1b[38;5;196mA\x1b[48;2;1;2;3mB\x1b[39;49mC");
    let a = t.cell(0, 0).unwrap();
    assert_eq!(a.fg, palette::color256(196));
    assert_eq!(a.bg, COLOR_SENTINEL);
    let b = t.cell(0, 1).unwrap();
    assert_eq!(b.fg, palette::color256(196));
    assert_eq!(b.bg, palette::truecolor(1, 2, 3));
    let c = t.cell(0, 2).unwrap();
    assert_eq!(c.fg, COLOR_SENTINEL);
    assert_eq!(c.bg, COLOR_SENTINEL);
}

#[test]
fn sgr_colon_parameters_are_flattened() {
    let mut t = term(10, 3);
    feed(&mut t, "\x1b[38:5:196mA\x1b[1:31mB");
    let a = t.cell(0, 0).unwrap();
    assert_eq!(a.fg, palette::color256(196));
    let b = t.cell(0, 1).unwrap();
    assert!(b.bold());
    assert_eq!(b.fg, palette::COLOR_RED);
}

#[test]
fn sgr_21_only_disables_bold() {
    let mut t = term(10, 3);
    feed(&mut t, "\x1b[1;3;4;7mX\x1b[21mY");
    let y = t.cell(0, 1).unwrap();
    assert!(!y.bold());
    assert!(y.italic());
    assert!(y.underline());
    assert!(y.inverse());
    feed(&mut t, "\x1b[22;23;24;27mZ");
    let z = t.cell(0, 2).unwrap();
    assert!(!z.bold() && !z.italic() && !z.underline() && !z.inverse());
}

#[test]
fn sgr_bright_colors_map_to_palette_indices() {
    let mut t = term(10, 3);
    feed(&mut t, "\x1b[91;104mX");
    let x = t.cell(0, 0).unwrap();
    assert_eq!(x.fg, palette::ansi16(9));
    assert_eq!(x.bg, palette::ansi16(12));
}

// ---------------------------------------------------------------------------
// Writing, control characters, wrapping
// ---------------------------------------------------------------------------

#[test]
fn ascii_write_and_control_characters() {
    let mut t = term(20, 4);
    feed(&mut t, "abc\rX");
    assert_eq!(line(&t, 0), "Xbc");
    assert_eq!(t.cursor_column(), 1);

    let mut t = term(20, 4);
    feed(&mut t, "ab\u{8}X");
    assert_eq!(line(&t, 0), "aX");
    assert_eq!(t.cursor_column(), 2);

    let mut t = term(20, 4);
    feed(&mut t, "a\tb");
    assert_eq!(t.cursor_column(), 9);
    assert_eq!(text_at(&t, 0, 8), "b");

    let mut t = term(20, 4);
    feed(&mut t, "a\x0bb\x0cc");
    assert_eq!(line(&t, 0), "a");
    // VT/FF index without resetting the column, so 'b' and 'c' keep advancing.
    assert_eq!(line(&t, 1), " b");
    assert_eq!(line(&t, 2), "  c");

    let mut t = term(20, 4);
    feed(&mut t, "ab\x7fX");
    assert_eq!(line(&t, 0), "abX");
}

#[test]
fn autowrap_happens_only_when_next_printable_arrives() {
    let mut t = term(4, 3);
    feed(&mut t, "abcd");
    assert_eq!(t.cursor_row(), 0);
    assert_eq!(t.cursor_column(), 3);
    feed(&mut t, "e");
    assert_eq!(t.cursor_row(), 1);
    assert_eq!(line(&t, 0), "abcd");
    assert_eq!(line(&t, 1), "e");
}

#[test]
fn wide_characters_occupy_two_cells() {
    let mut t = term(12, 4);
    feed(&mut t, "你a");
    let left = t.cell(0, 0).unwrap();
    assert_eq!(t.cell_text(left), "你");
    assert_eq!(left.width, 2);
    let right = t.cell(0, 1).unwrap();
    assert_eq!(t.cell_text(right), "");
    assert_eq!(right.width, 0);
    assert_eq!(text_at(&t, 0, 2), "a");
    assert_eq!(t.cursor_column(), 3);
    assert_eq!(line(&t, 0), "你a");
}

#[test]
fn wide_character_at_last_column_wraps_or_degrades() {
    // With autowrap on, Java wraps a wide character at the last column to the
    // next row before it ever considers degrading it.
    let mut t = term(4, 3);
    feed(&mut t, "abc你");
    assert_eq!(t.cursor_row(), 1);
    assert_eq!(line(&t, 0), "abc");
    assert_eq!(text_at(&t, 1, 0), "你");
    assert_eq!(t.cell(1, 0).unwrap().width, 2);
    feed(&mut t, "d");
    assert_eq!(line(&t, 1), "你d");

    // With autowrap off the same character degrades to a single cell.
    let mut t = term(4, 3);
    feed(&mut t, "\x1b[?7labc你");
    assert_eq!(t.cursor_row(), 0);
    let cell = t.cell(0, 3).unwrap();
    assert_eq!(t.cell_text(cell), "你");
    assert_eq!(cell.width, 1);
    assert_eq!(line(&t, 0), "abc你");
}

#[test]
fn combining_marks_attach_to_their_host_cell() {
    let mut t = term(12, 4);
    feed(&mut t, "e\u{301}你\u{301}");
    assert_eq!(text_at(&t, 0, 0), "e\u{301}");
    assert_eq!(t.cell(0, 0).unwrap().width, 1);
    assert_eq!(text_at(&t, 0, 1), "你\u{301}");
    assert_eq!(t.cell(0, 1).unwrap().width, 2);
    assert_eq!(t.cursor_column(), 3);
    assert_eq!(line(&t, 0), "e\u{301}你\u{301}");
}

#[test]
fn overwriting_a_wide_character_removes_both_halves() {
    let mut t = term(12, 4);
    feed(&mut t, "你");
    feed(&mut t, "\x1b[1;2HX");
    assert!(!t.line_text(0).contains('你'));
    assert!(!t.line_text(0).contains('\u{FFFD}'));
    assert_eq!(text_at(&t, 0, 1), "X");
}

#[test]
fn deleting_a_wide_base_repairs_the_orphan_half() {
    let mut t = term(12, 4);
    feed(&mut t, "你x");
    feed(&mut t, "\x1b[1;1H\x1b[P");
    // The left half was deleted, so the shifted right half must be blanked.
    assert_eq!(t.cell(0, 0).unwrap().width, 1);
    assert_eq!(text_at(&t, 0, 1), "x");
}

// ---------------------------------------------------------------------------
// Cursor and editing
// ---------------------------------------------------------------------------

#[test]
fn cursor_movements_are_clamped() {
    let mut t = term(10, 6);
    feed(&mut t, "\x1b[3;5H");
    assert_eq!((t.cursor_row(), t.cursor_column()), (2, 4));
    feed(&mut t, "\x1b[2A");
    assert_eq!(t.cursor_row(), 0);
    feed(&mut t, "\x1b[3B");
    assert_eq!(t.cursor_row(), 3);
    feed(&mut t, "\x1b[2C");
    assert_eq!(t.cursor_column(), 6);
    feed(&mut t, "\x1b[4D");
    assert_eq!(t.cursor_column(), 2);
    feed(&mut t, "\x1b[2E");
    assert_eq!((t.cursor_row(), t.cursor_column()), (5, 0));
    feed(&mut t, "\x1b[1F");
    assert_eq!(t.cursor_row(), 4);
    feed(&mut t, "\x1b[4G");
    assert_eq!(t.cursor_column(), 3);
    feed(&mut t, "\x1b[2d");
    assert_eq!(t.cursor_row(), 1);
    feed(&mut t, "\x1b[999B\x1b[999C");
    assert_eq!((t.cursor_row(), t.cursor_column()), (5, 9));
    feed(&mut t, "\x1b[99;99H");
    assert_eq!((t.cursor_row(), t.cursor_column()), (5, 9));
}

#[test]
fn erase_line_and_display_modes() {
    let mut t = term(10, 3);
    feed(&mut t, "abcdef\x1b[1;3H\x1b[K");
    assert_eq!(line(&t, 0), "ab");

    let mut t = term(10, 3);
    feed(&mut t, "abcdef\x1b[1;3H\x1b[1K");
    assert_eq!(line(&t, 0), "   def");

    let mut t = term(10, 3);
    feed(&mut t, "abcdef\x1b[1;3H\x1b[2K");
    assert_eq!(line(&t, 0), "");

    let mut t = term(10, 4);
    feed(&mut t, "first\r\nsecond\r\nthird\x1b[2;4H\x1b[J");
    assert_eq!(line(&t, 0), "first");
    assert_eq!(line(&t, 1), "sec");
    assert!(!t.line_text(2).contains("third"));
}

#[test]
fn erase_characters_preserves_tail() {
    let mut t = term(12, 4);
    feed(&mut t, "abcdef\x1b[1;3H\x1b[2X");
    assert_eq!(line(&t, 0), "ab  ef");
}

#[test]
fn character_insertion_deletion_and_line_editing() {
    let mut t = term(12, 4);
    feed(&mut t, "abcdef\x1b[1;3H\x1b[2@XY");
    assert_eq!(line(&t, 0), "abXYcdef");
    feed(&mut t, "\x1b[1;3H\x1b[2P");
    assert_eq!(line(&t, 0), "abcdef");

    let mut t = term(10, 4);
    feed(&mut t, "\x1b[?1049hA\r\nB\r\nC\r\nD\x1b[2;1H\x1b[LX");
    assert_eq!(line(&t, 1), "X");
    assert_eq!(line(&t, 2), "B");
    assert_eq!(line(&t, 3), "C");
    feed(&mut t, "\x1b[2;1H\x1b[M");
    assert_eq!(line(&t, 1), "B");
    assert_eq!(line(&t, 2), "C");
    assert_eq!(line(&t, 3), "");
}

#[test]
fn insert_mode_shifts_existing_characters() {
    let mut t = term(10, 3);
    feed(&mut t, "abc\x1b[1;2H\x1b[4hX\x1b[4lY");
    // X inserts before 'b'; Y then overwrites 'b' because insert mode is off.
    assert_eq!(line(&t, 0), "aXYc");
    assert!(!t.insert_mode());
}

// ---------------------------------------------------------------------------
// Scrolling, regions, history
// ---------------------------------------------------------------------------

#[test]
fn scroll_s_t_and_full_screen_history() {
    let mut t = term(10, 3);
    feed(&mut t, "a\r\nb\r\nc");
    assert_eq!(t.history_rows(), 0);
    feed(&mut t, "\x1b[S");
    assert_eq!(t.history_rows(), 1);
    assert_eq!(line(&t, 0), "b");
    assert_eq!(line(&t, 1), "c");
    feed(&mut t, "\x1b[T");
    assert_eq!(line(&t, 0), "");
    assert_eq!(line(&t, 1), "b");
    assert_eq!(line(&t, 2), "c");
}

#[test]
fn scrolling_region_protects_header_and_footer_without_history() {
    let mut t = term(20, 5);
    feed(
        &mut t,
        "\x1b[?1049hHEADER\x1b[2;1Hone\x1b[3;1Htwo\x1b[4;1Hthree\x1b[5;1HFOOTER",
    );
    feed(&mut t, "\x1b[2;4r\x1b[4;1H\nlast");
    assert_eq!(line(&t, 0), "HEADER");
    assert_eq!(line(&t, 1), "two");
    assert_eq!(line(&t, 2), "three");
    assert_eq!(line(&t, 3), "last");
    assert_eq!(line(&t, 4), "FOOTER");
    assert_eq!(t.history_rows(), 0);
}

#[test]
fn line_insert_and_delete_inside_region_do_not_add_history() {
    let mut t = term(10, 5);
    feed(&mut t, "a\r\nb\r\nc\r\nd\r\ne\x1b[2;4r\x1b[2;1H\x1b[L");
    assert_eq!(t.history_rows(), 0);
    assert_eq!(line(&t, 1), "");
    feed(&mut t, "\x1b[M");
    assert_eq!(t.history_rows(), 0);
}

#[test]
fn history_is_bounded_and_counts_evictions() {
    let mut t = term(20, 4);
    for i in 0..2100 {
        feed(&mut t, &format!("row-{i}\r\n"));
    }
    assert_eq!(t.history_rows(), 2000);
    let evicted_before = t.evicted_history_rows();
    for i in 2100..2200 {
        feed(&mut t, &format!("row-{i}\r\n"));
    }
    assert_eq!(t.history_rows(), 2000);
    assert_eq!(t.evicted_history_rows() - evicted_before, 100);
    assert!(t.history_line_text(0).starts_with("row-"));
}

#[test]
fn alternate_screen_scrolling_never_enters_history() {
    let mut t = term(20, 4);
    for i in 0..20 {
        feed(&mut t, &format!("line {i}\r\n"));
    }
    let history = t.history_rows();
    assert!(history > 0);
    feed(&mut t, "\x1b[?1049h");
    for _ in 0..50 {
        feed(&mut t, "editor\r\n");
    }
    assert_eq!(t.history_rows(), history);
    feed(&mut t, "\x1b[?1049l");
    assert_eq!(t.history_rows(), history);
}

// ---------------------------------------------------------------------------
// Alternate screens
// ---------------------------------------------------------------------------

#[test]
fn alt_screen_1049_restores_primary_and_saved_cursor() {
    let mut t = term(30, 6);
    feed(
        &mut t,
        "guest$ vim\x1b[?1049h\x1b[2J\x1b[Hhello\r\nworld\x1b[6;1H-- INSERT --",
    );
    assert!(t.is_alternate_screen());
    assert_eq!(line(&t, 0), "hello");
    assert_eq!(line(&t, 5), "-- INSERT --");
    feed(&mut t, "\x1b[2;1Hchanged\x1b[K\x1b[?1049l");
    assert!(!t.is_alternate_screen());
    assert_eq!(line(&t, 0), "guest$ vim");
    assert_eq!(t.cursor_column(), 10);
    assert!(!t.line_text(0).contains("changed"));
}

#[test]
fn alt_screen_47_reuses_buffer_and_1048_saves_cursor() {
    let mut t = term(10, 3);
    feed(&mut t, "A\x1b[?47hB\x1b[?47l");
    assert_eq!(line(&t, 0), "A");
    feed(&mut t, "\x1b[?47h");
    assert_eq!(line(&t, 0), "B");

    let mut t = term(10, 3);
    feed(&mut t, "\x1b[2;3H\x1b[?1048h\x1b[1;1H\x1b[?1048lX");
    assert_eq!(t.cursor_row(), 1);
    assert_eq!(t.cursor_column(), 3);
}

#[test]
fn mode_flags_track_private_and_insert_modes() {
    let mut t = term(10, 3);
    feed(&mut t, "\x1b[?1h\x1b[?2004h\x1b[?25l\x1b[?7l");
    assert!(t.is_application_cursor_keys());
    assert!(t.is_bracketed_paste());
    assert!(!t.is_cursor_visible());
    assert!(!t.auto_wrap());
    feed(&mut t, "\x1b[?1l\x1b[?2004l\x1b[?25h\x1b[?7h");
    assert!(!t.is_application_cursor_keys());
    assert!(!t.is_bracketed_paste());
    assert!(t.is_cursor_visible());
    assert!(t.auto_wrap());
}

// ---------------------------------------------------------------------------
// Resize
// ---------------------------------------------------------------------------

#[test]
fn resize_preserves_content_and_pushes_top_rows_to_history() {
    let mut t = term(10, 5);
    feed(&mut t, "a\r\nb\r\nc\r\nd\r\ne");
    assert_eq!(t.cursor_row(), 4);
    t.resize(8, 3);
    assert_eq!(t.cols(), 8);
    assert_eq!(t.rows(), 3);
    assert_eq!(t.history_rows(), 2);
    assert_eq!(t.cursor_row(), 2);
    assert_eq!(line(&t, 0), "c");
    assert_eq!(line(&t, 1), "d");
    assert_eq!(line(&t, 2), "e");
}

#[test]
fn resize_clears_wide_base_in_last_column() {
    let mut t = term(6, 2);
    feed(&mut t, "abcd你");
    assert_eq!(t.cell(0, 4).unwrap().width, 2);
    t.resize(5, 2);
    assert_eq!(text_at(&t, 0, 4), " ");
    assert_eq!(t.cell(0, 4).unwrap().width, 1);
    assert_eq!(line(&t, 0), "abcd");
}

#[test]
fn resize_growing_columns_keeps_old_cells_and_blank_fills_new_ones() {
    let mut t = term(4, 2);
    feed(&mut t, "ab");
    t.resize(6, 2);
    assert_eq!(text_at(&t, 0, 0), "a");
    assert_eq!(text_at(&t, 0, 1), "b");
    assert_eq!(text_at(&t, 0, 5), " ");
    assert_eq!(t.cell(0, 5).unwrap().width, 1);
}

// ---------------------------------------------------------------------------
// UTF-8 and control strings
// ---------------------------------------------------------------------------

#[test]
fn split_utf8_feed_matches_whole_feed() {
    let text = "你好😀e\u{301}";
    let mut whole = term(20, 4);
    whole.feed(text.as_bytes());
    let mut split = term(20, 4);
    for byte in text.as_bytes() {
        split.feed(&[*byte]);
    }
    assert_eq!(whole.line_text(0), split.line_text(0));
    assert!(!split.line_text(0).contains('\u{FFFD}'));
}

#[test]
fn invalid_utf8_becomes_replacement_character() {
    let mut t = term(10, 2);
    t.feed(&[0xFF, b'a']);
    assert_eq!(t.line_text(0), "\u{FFFD}a");
}

#[test]
fn osc_dcs_and_apc_are_swallowed_including_split_terminators() {
    let mut t = term(20, 4);
    feed(&mut t, "before\x1b]52;c;secret\x1b");
    feed(&mut t, "\\after\x1bPpayload\x1b\\\x1b_apc\x1b\\");
    assert_eq!(line(&t, 0), "beforeafter");
    assert!(!t.line_text(0).contains("secret"));
    assert!(!t.line_text(0).contains("payload"));
    assert!(!t.line_text(0).contains("apc"));
}

#[test]
fn oversized_osc_is_discarded_without_leaking_to_screen() {
    let mut t = term(20, 2);
    let mut stream = String::from("\x1b]0;");
    stream.push_str(&"A".repeat(13_000));
    stream.push('\u{7}');
    stream.push('X');
    feed(&mut t, &stream);
    assert_eq!(line(&t, 0), "X");
}

#[test]
fn dec_graphics_charset_maps_line_drawing() {
    let mut t = term(10, 2);
    feed(&mut t, "\x1b(0q\x1b(Bq");
    assert_eq!(text_at(&t, 0, 0), "─");
    assert_eq!(text_at(&t, 0, 1), "q");
    feed(&mut t, "\x0eq\x0fq");
    assert_eq!(text_at(&t, 0, 2), "─");
    assert_eq!(text_at(&t, 0, 3), "q");
}

// ---------------------------------------------------------------------------
// copy_rows
// ---------------------------------------------------------------------------

#[test]
fn copy_rows_shape_sentinel_and_attrs() {
    let mut t = term(4, 2);
    feed(&mut t, "ab\r\ncd");
    let mut cp = [0u32; 8];
    let mut fg = [0u32; 8];
    let mut bg = [0u32; 8];
    let mut attrs = [0u8; 8];
    let rows = t.copy_rows(0, 2, &mut cp, &mut fg, &mut bg, &mut attrs);
    assert_eq!(rows, 2);
    assert_eq!(&cp[0..4], &[b'a' as u32, b'b' as u32, 0x20, 0x20]);
    assert_eq!(&cp[4..8], &[b'c' as u32, b'd' as u32, 0x20, 0x20]);
    assert_eq!(fg[0], COLOR_SENTINEL);
    assert_eq!(bg[0], COLOR_SENTINEL);
    assert_eq!(attrs[0], 0);
    assert_eq!(t.copy_rows(5, 1, &mut cp, &mut fg, &mut bg, &mut attrs), 0);
}

#[test]
fn copy_rows_reports_wide_halves_and_colors() {
    let mut t = term(4, 2);
    feed(&mut t, "\x1b[1;31m你\x1b[0m");
    let mut cp = [0u32; 8];
    let mut fg = [0u32; 8];
    let mut bg = [0u32; 8];
    let mut attrs = [0u8; 8];
    let rows = t.copy_rows(0, 2, &mut cp, &mut fg, &mut bg, &mut attrs);
    assert_eq!(rows, 2);
    assert_eq!(cp[0], '你' as u32);
    assert_eq!(attrs[0] & 0b01_0000, 0b01_0000);
    assert_eq!(cp[1], 0);
    assert_eq!(attrs[1] & 0b10_0000, 0b10_0000);
    assert_eq!(fg[0], palette::COLOR_RED);
    assert_eq!(fg[1], palette::COLOR_RED);
}

#[test]
fn copy_rows_indexes_history_before_the_visible_screen() {
    let mut t = term(5, 2);
    feed(&mut t, "a\r\nb\r\nc");
    assert_eq!(t.history_rows(), 1);
    assert_eq!(t.screen_start_row(), 1);
    let mut cp = [0u32; 15];
    let mut fg = [0u32; 15];
    let mut bg = [0u32; 15];
    let mut attrs = [0u8; 15];
    let rows = t.copy_rows(0, 3, &mut cp, &mut fg, &mut bg, &mut attrs);
    assert_eq!(rows, 3);
    assert_eq!(cp[0], b'a' as u32);
    assert_eq!(cp[5], b'b' as u32);
    assert_eq!(cp[10], b'c' as u32);
}

#[test]
fn copy_rows_with_alternate_screen_has_no_history_offset() {
    let mut t = term(5, 2);
    feed(&mut t, "a\r\nb\r\nc\x1b[?1049hZ");
    assert!(t.is_alternate_screen());
    assert_eq!(t.screen_start_row(), 0);
    let mut cp = [0u32; 5];
    let mut fg = [0u32; 5];
    let mut bg = [0u32; 5];
    let mut attrs = [0u8; 5];
    assert_eq!(t.copy_rows(0, 1, &mut cp, &mut fg, &mut bg, &mut attrs), 1);
    assert_eq!(cp[0], b'Z' as u32);
}

#[test]
fn cell_is_a_compact_value_type() {
    fn assert_copy<T: Copy>() {}
    assert_copy::<vt_core::Cell>();
    let size = std::mem::size_of::<vt_core::Cell>();
    println!("size_of::<Cell>() = {size} bytes (target 16)");
    assert_eq!(size, 16, "Cell is {size} bytes, expected 16");
    assert_eq!(vt_core::Cell::BLANK.cp, 0x20);
    assert_eq!(vt_core::Cell::BLANK.fg, COLOR_SENTINEL);
    assert_eq!(vt_core::Cell::BLANK.bg, COLOR_SENTINEL);
    assert_eq!(vt_core::Cell::BLANK.width, 1);
    assert_eq!(vt_core::Cell::BLANK.extra, NO_EXTRA);
    assert_eq!(vt_core::Cell::UNWRITTEN.extra, NO_EXTRA);
}

#[test]
fn combining_mark_on_never_written_cell_is_dropped() {
    // Java keeps untouched cells as null and drops a mark aimed at one.
    let mut t = term(10, 2);
    feed(&mut t, "\x1b[1;3H\u{301}");
    assert_eq!(t.cursor_column(), 2);
    assert_eq!(t.cell(0, 1).unwrap().extra, NO_EXTRA);
    assert_eq!(line(&t, 0), "");
    // A written host accepts the mark as usual.
    feed(&mut t, "e\u{301}");
    assert_eq!(text_at(&t, 0, 2), "e\u{301}");
}

#[test]
fn history_tail_blanks_are_trimmed_but_copy_rows_is_unchanged() {
    let mut t = term(6, 2);
    feed(&mut t, "ab\r\n\n");
    assert_eq!(t.history_rows(), 1);
    assert_eq!(t.history_line_text(0), "ab");
    let mut cp = [0u32; 6];
    let mut fg = [0u32; 6];
    let mut bg = [0u32; 6];
    let mut attrs = [0u8; 6];
    assert_eq!(t.copy_rows(0, 1, &mut cp, &mut fg, &mut bg, &mut attrs), 1);
    assert_eq!(&cp[..6], &[b'a' as u32, b'b' as u32, 0x20, 0x20, 0x20, 0x20]);
    assert_eq!(bg[2], COLOR_SENTINEL);
}

#[test]
fn history_keeps_trailing_background_cells() {
    let mut t = term(6, 2);
    feed(&mut t, "\x1b[44m  \x1b[0m\r\n\n");
    let mut cp = [0u32; 6];
    let mut fg = [0u32; 6];
    let mut bg = [0u32; 6];
    let mut attrs = [0u8; 6];
    t.copy_rows(0, 1, &mut cp, &mut fg, &mut bg, &mut attrs);
    assert_eq!(bg[0], palette::COLOR_BLUE);
    assert_eq!(bg[1], palette::COLOR_BLUE);
    assert_eq!(bg[2], COLOR_SENTINEL);
    assert_eq!(cp[2], 0x20);
}

#[test]
fn history_keeps_trailing_inverse_cells() {
    let mut t = term(6, 2);
    feed(&mut t, "\x1b[7m  \x1b[0m\r\n\n");
    let mut cp = [0u32; 6];
    let mut fg = [0u32; 6];
    let mut bg = [0u32; 6];
    let mut attrs = [0u8; 6];
    t.copy_rows(0, 1, &mut cp, &mut fg, &mut bg, &mut attrs);
    assert_eq!(attrs[0] & 0b1000, 0b1000);
    assert_eq!(attrs[1] & 0b1000, 0b1000);
    assert_eq!(attrs[2] & 0b1000, 0);
}

#[test]
fn history_keeps_wide_right_half_and_its_base() {
    let mut t = term(4, 2);
    feed(&mut t, "ab你\r\n\n");
    assert_eq!(t.history_line_text(0), "ab你");
    let mut cp = [0u32; 4];
    let mut fg = [0u32; 4];
    let mut bg = [0u32; 4];
    let mut attrs = [0u8; 4];
    t.copy_rows(0, 1, &mut cp, &mut fg, &mut bg, &mut attrs);
    assert_eq!(cp[2], '你' as u32);
    assert_eq!(cp[3], 0);
    assert_eq!(attrs[2] & 0b01_0000, 0b01_0000);
    assert_eq!(attrs[3] & 0b10_0000, 0b10_0000);
}

#[test]
fn fully_blank_history_row_still_occupies_a_line() {
    let mut t = term(3, 2);
    feed(&mut t, "\r\n\n");
    assert_eq!(t.history_rows(), 1);
    assert_eq!(t.history_line_text(0), "");
    let mut cp = [0u32; 3];
    let mut fg = [0u32; 3];
    let mut bg = [0u32; 3];
    let mut attrs = [0u8; 3];
    assert_eq!(t.copy_rows(0, 1, &mut cp, &mut fg, &mut bg, &mut attrs), 1);
    assert_eq!(&cp[..3], &[0x20, 0x20, 0x20]);
}

#[test]
fn history_cap_still_counts_untrimmed_columns() {
    let mut t = term(90, 2);
    for _ in 0..1200 {
        feed(&mut t, "x\r\n");
    }
    assert_eq!(t.history_rows(), 1111);
    assert_eq!(t.history_cells(), 1111 * 90);
}
