//! `vt-bench` — benchmark CLI for the vt-core prototype.
//!
//! Usage:
//!   vt-bench bench <fixture-id|all> [--iters 5] [--warmup 2]
//!                  [--out out/rust.json] [--fixtures DIR]
//!
//! Fixtures are verified against `<fixtures>/SHA256SUMS` (lines: `<sha>  <name>`).
//! A missing or mismatching entry exits with code 3.
//!
//! Output JSON is hand-written (no serde), one object per fixture plus a top-level
//! envelope. `rustc` is recorded so results can be tied to the pinned toolchain.

mod alloc;

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use sha2::{Digest, Sha256};
use vt_core::{Cell, Terminal};

use alloc::{allocated_bytes, live_bytes, reset_alloc_counters};

const ENGINE: &str = "rust";
const COLS: usize = 90;
const ROWS: usize = 30;

struct Options {
    fixture: String,
    iters: usize,
    warmup: usize,
    out: PathBuf,
    fixtures: PathBuf,
}

struct FixtureResult {
    fixture: String,
    bytes: usize,
    sha256: String,
    iters: usize,
    mb_s_all: Vec<f64>,
    alloc_per_mb_all: Vec<f64>,
    copy_us_all: Vec<f64>,
    copy_screen_us_all: Vec<f64>,
    live_bytes_all: Vec<u64>,
    display_rows: usize,
}

fn main() {
    std::process::exit(run());
}

fn run() -> i32 {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let opts = match parse_options(&args) {
        Ok(opts) => opts,
        Err(message) => {
            eprintln!("{message}");
            eprintln!("{USAGE}");
            return 2;
        }
    };

    let sums = match read_sums(&opts.fixtures) {
        Ok(sums) => sums,
        Err(err) => {
            eprintln!("error: cannot read {}: {err}", opts.fixtures.join("SHA256SUMS").display());
            return 3;
        }
    };

    let fixtures = match select_fixtures(&opts.fixtures, &opts.fixture) {
        Ok(fixtures) => fixtures,
        Err(message) => {
            eprintln!("error: {message}");
            return 2;
        }
    };

    let rustc = rustc_version();
    let mut results = Vec::new();
    for path in fixtures {
        match bench_fixture(&path, &opts, &sums) {
            Ok(result) => results.push(result),
            Err((code, message)) => {
                eprintln!("error: {message}");
                return code;
            }
        }
    }

    let json = render_json(&results, &rustc);
    if let Some(parent) = opts.out.parent() {
        if !parent.as_os_str().is_empty() {
            if let Err(err) = fs::create_dir_all(parent) {
                eprintln!("error: cannot create {}: {err}", parent.display());
                return 2;
            }
        }
    }
    if let Err(err) = fs::write(&opts.out, json.as_bytes()) {
        eprintln!("error: cannot write {}: {err}", opts.out.display());
        return 2;
    }
    eprintln!("wrote {} ({} fixture(s))", opts.out.display(), results.len());
    0
}

const USAGE: &str = "usage: vt-bench bench <fixture-id|all> [--iters N] [--warmup N] [--out PATH] [--fixtures DIR]";

fn parse_options(args: &[String]) -> Result<Options, String> {
    if args.is_empty() || args[0] != "bench" {
        return Err("missing `bench` subcommand".to_string());
    }
    let mut fixture: Option<String> = None;
    let mut iters = 5usize;
    let mut warmup = 2usize;
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let mut out = manifest_dir.parent().unwrap_or(manifest_dir).join("out/rust.json");
    let mut fixtures = manifest_dir.join("../fixtures/generated");

    let mut i = 1;
    while i < args.len() {
        let arg = &args[i];
        let (name, inline) = match arg.split_once('=') {
            Some((name, value)) => (name, Some(value.to_string())),
            None => (arg.as_str(), None),
        };
        let take_value = |i: &mut usize| -> Result<String, String> {
            if let Some(value) = &inline {
                return Ok(value.clone());
            }
            *i += 1;
            args.get(*i)
                .cloned()
                .ok_or_else(|| format!("`{name}` requires a value"))
        };
        match name {
            "--iters" => {
                let value = take_value(&mut i)?;
                iters = value
                    .parse()
                    .map_err(|_| format!("invalid --iters value: {value}"))?;
            }
            "--warmup" => {
                let value = take_value(&mut i)?;
                warmup = value
                    .parse()
                    .map_err(|_| format!("invalid --warmup value: {value}"))?;
            }
            "--out" => out = PathBuf::from(take_value(&mut i)?),
            "--fixtures" => fixtures = PathBuf::from(take_value(&mut i)?),
            other if other.starts_with('-') => {
                return Err(format!("unknown option: {other}"));
            }
            other => {
                if fixture.is_some() {
                    return Err(format!("unexpected argument: {other}"));
                }
                fixture = Some(other.to_string());
            }
        }
        i += 1;
    }

    let fixture = fixture.ok_or_else(|| "missing fixture id (or `all`)".to_string())?;
    if iters == 0 {
        return Err("--iters must be >= 1".to_string());
    }
    Ok(Options {
        fixture,
        iters,
        warmup,
        out,
        fixtures,
    })
}

fn read_sums(dir: &Path) -> io::Result<Vec<(String, String)>> {
    let text = fs::read_to_string(dir.join("SHA256SUMS"))?;
    let mut sums = Vec::new();
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        if let (Some(hash), Some(name)) = (parts.next(), parts.next()) {
            let name = name.trim_start_matches('*');
            sums.push((name.to_string(), hash.to_ascii_lowercase()));
        }
    }
    Ok(sums)
}

fn select_fixtures(dir: &Path, wanted: &str) -> Result<Vec<PathBuf>, String> {
    let mut all = Vec::new();
    let entries = fs::read_dir(dir).map_err(|err| format!("cannot read {}: {err}", dir.display()))?;
    for entry in entries {
        let entry = entry.map_err(|err| format!("cannot read {}: {err}", dir.display()))?;
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) == Some("bin") {
            all.push(path);
        }
    }
    all.sort();
    if wanted == "all" {
        if all.is_empty() {
            return Err(format!("no *.bin fixtures in {}", dir.display()));
        }
        return Ok(all);
    }
    let target = format!("{wanted}.bin");
    let selected: Vec<PathBuf> = all
        .into_iter()
        .filter(|path| path.file_name().and_then(|name| name.to_str()) == Some(target.as_str()))
        .collect();
    if selected.is_empty() {
        return Err(format!("fixture `{wanted}` not found in {}", dir.display()));
    }
    Ok(selected)
}

fn bench_fixture(
    path: &Path,
    opts: &Options,
    sums: &[(String, String)],
) -> Result<FixtureResult, (i32, String)> {
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| (2, format!("invalid fixture name: {}", path.display())))?
        .to_string();
    let bytes = fs::read(path).map_err(|err| (2, format!("cannot read {}: {err}", path.display())))?;
    let digest = hex(&Sha256::digest(&bytes));
    match sums.iter().find(|(entry, _)| entry == &name) {
        Some((_, expected)) if expected.eq_ignore_ascii_case(&digest) => {}
        Some((_, expected)) => {
            return Err((
                3,
                format!("sha256 mismatch for {name}: expected {expected}, got {digest}"),
            ));
        }
        None => {
            return Err((3, format!("no SHA256SUMS entry for {name}")));
        }
    }

    for _ in 0..opts.warmup {
        let mut terminal = Terminal::new(COLS, ROWS);
        terminal.feed(&bytes);
    }

    let mut mb_s_all = Vec::with_capacity(opts.iters);
    let mut alloc_per_mb_all = Vec::with_capacity(opts.iters);
    let mut copy_us_all = Vec::with_capacity(opts.iters);
    let mut copy_screen_us_all = Vec::with_capacity(opts.iters);
    let mut live_bytes_all = Vec::with_capacity(opts.iters);
    let mut display_rows = 0usize;
    let megabytes = bytes.len() as f64 / 1_048_576.0;

    for _ in 0..opts.iters {
        // Reset before construction so `live_bytes()` covers the whole terminal,
        // not just the allocations made during feed.
        reset_alloc_counters();
        let mut terminal = Terminal::new(COLS, ROWS);
        let alloc_before_feed = allocated_bytes();
        let start = Instant::now();
        terminal.feed(&bytes);
        let elapsed = start.elapsed().as_secs_f64();
        let allocated = allocated_bytes() - alloc_before_feed;
        let live = live_bytes();

        display_rows = terminal.display_row_count();
        // Enough room for the Kotlin-equivalent full display and for the 30-row
        // visible-screen reference copy, allocated before either timing block.
        let total = display_rows.max(ROWS) * COLS;
        let mut cp = vec![0u32; total];
        let mut fg = vec![0u32; total];
        let mut bg = vec![0u32; total];
        let mut attrs = vec![0u8; total];

        let copy_start = Instant::now();
        let copied = terminal.copy_rows(0, display_rows, &mut cp, &mut fg, &mut bg, &mut attrs);
        let copy_us = copy_start.elapsed().as_secs_f64() * 1e6;
        debug_assert!(copied <= display_rows);

        let screen_start = terminal.screen_start_row();
        let screen_cells = ROWS * COLS;
        let screen_copy_start = Instant::now();
        let screen_copied = terminal.copy_rows(
            screen_start,
            ROWS,
            &mut cp[..screen_cells],
            &mut fg[..screen_cells],
            &mut bg[..screen_cells],
            &mut attrs[..screen_cells],
        );
        let copy_screen_us = screen_copy_start.elapsed().as_secs_f64() * 1e6;
        debug_assert!(screen_copied <= ROWS);

        mb_s_all.push(if elapsed > 0.0 { megabytes / elapsed } else { 0.0 });
        alloc_per_mb_all.push(if megabytes > 0.0 {
            allocated as f64 / megabytes
        } else {
            0.0
        });
        copy_us_all.push(copy_us);
        copy_screen_us_all.push(copy_screen_us);
        live_bytes_all.push(live);
    }

    Ok(FixtureResult {
        fixture: name.trim_end_matches(".bin").to_string(),
        bytes: bytes.len(),
        sha256: digest,
        iters: opts.iters,
        mb_s_all,
        alloc_per_mb_all,
        copy_us_all,
        copy_screen_us_all,
        live_bytes_all,
        display_rows,
    })
}

fn render_json(results: &[FixtureResult], rustc: &str) -> String {
    let mut out = String::new();
    out.push_str("{\n");
    out.push_str("  \"generated_at_utc\": \"");
    out.push_str(&iso_utc_now());
    out.push_str("\",\n");
    out.push_str("  \"generated_at_unix\": ");
    out.push_str(&unix_now().to_string());
    out.push_str(",\n");
    out.push_str("  \"host\": \"");
    out.push_str(std::env::consts::ARCH);
    out.push_str("\",\n");
    out.push_str("  \"fixtures\": [\n");
    for (index, result) in results.iter().enumerate() {
        out.push_str("    {\n");
        out.push_str(&format!("      \"engine\": \"{ENGINE}\",\n"));
        out.push_str(&format!("      \"rustc\": \"{}\",\n", escape(rustc)));
        out.push_str(&format!("      \"fixture\": \"{}\",\n", escape(&result.fixture)));
        out.push_str(&format!("      \"bytes\": {},\n", result.bytes));
        out.push_str(&format!("      \"sha256\": \"{}\",\n", result.sha256));
        out.push_str(&format!("      \"iters\": {},\n", result.iters));
        out.push_str(&format!(
            "      \"mb_s_median\": {},\n",
            number(median(&result.mb_s_all))
        ));
        out.push_str("      \"mb_s_all\": [");
        for (i, value) in result.mb_s_all.iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            out.push_str(&number(*value));
        }
        out.push_str("],\n");
        out.push_str(&format!(
            "      \"alloc_bytes_per_mb_median\": {},\n",
            number(median(&result.alloc_per_mb_all))
        ));
        out.push_str(&format!(
            "      \"copy_rows_us_median\": {},\n",
            number(median(&result.copy_us_all))
        ));
        out.push_str(&format!(
            "      \"copy_rows_screen_us_median\": {},\n",
            number(median(&result.copy_screen_us_all))
        ));
        out.push_str(&format!(
            "      \"live_bytes_after_feed\": {},\n",
            median_u64(&result.live_bytes_all)
        ));
        out.push_str(&format!(
            "      \"cell_size_bytes\": {},\n",
            std::mem::size_of::<Cell>()
        ));
        out.push_str(&format!("      \"display_rows\": {},\n", result.display_rows));
        out.push_str(&format!("      \"cells\": {}\n", COLS * ROWS));
        out.push_str("    }");
        if index + 1 < results.len() {
            out.push(',');
        }
        out.push('\n');
    }
    out.push_str("  ]\n}\n");
    out
}

fn median(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    sorted[sorted.len() / 2]
}

fn median_u64(values: &[u64]) -> u64 {
    if values.is_empty() {
        return 0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    sorted[sorted.len() / 2]
}

fn number(value: f64) -> String {
    if value.is_finite() {
        format!("{value:.6}")
    } else {
        "0.000000".to_string()
    }
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn escape(value: &str) -> String {
    value.replace('\\', "\\\\").replace('"', "\\\"")
}

fn rustc_version() -> String {
    let rustc = std::env::var("RUSTC").unwrap_or_else(|_| "rustc".to_string());
    match Command::new(rustc).arg("--version").output() {
        Ok(output) if output.status.success() => {
            String::from_utf8_lossy(&output.stdout).trim().to_string()
        }
        _ => "unknown".to_string(),
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

fn iso_utc_now() -> String {
    let secs = unix_now();
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

/// Howard Hinnant's civil_from_days (days since 1970-01-01).
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (year + i64::from(month <= 2), month, day)
}
