//! Dependency-free VT screen prototype for the Rust core feasibility study.
//!
//! The public surface is deliberately small: construct a [`Terminal`], push bytes
//! into it with [`Terminal::feed`], and flatten the result for a renderer with
//! [`Terminal::copy_rows`]. Semantics follow Orchestella's Java
//! `TerminalScreenModel` + `AnsiEscapeParser`, not the full VT spec; see the lane
//! report for the list of intentional approximations.

#![forbid(unsafe_code)]

pub mod palette;
pub mod width;

mod terminal;

pub use terminal::{
    Cell, Style, Terminal, ATTR_BOLD, ATTR_INVERSE, ATTR_ITALIC, ATTR_UNDERLINE, COLOR_SENTINEL,
};
pub use width::character_width;
