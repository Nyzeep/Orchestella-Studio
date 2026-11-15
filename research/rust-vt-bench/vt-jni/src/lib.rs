//! Measurement-only JNI shim for the Rust VT feasibility study (R01).
//!
//! This crate exists to answer two questions with numbers:
//!   1. what does a JNI crossing cost (empty call, and a feed call with a
//!      direct ByteBuffer), and
//!   2. how big is the stripped arm64-v8a `.so` for the real core?
//!
//! It is deliberately *not* wired into the Android build. The production seam
//! (R02) would live in `runtime/terminal-core/` with a Kotlin `TerminalCore`
//! interface and a fallback implementation.
//!
//! Contract (mirrored by `jni/RustJniProbe.java`):
//!   - `nativeNew(cols, rows) -> handle`, 0 on failure
//!   - `nativeFeed(handle, directBuffer, len) -> 0 | negative error`
//!   - `nativeCopyRows(handle, first, count, cp, fg, bg, attrs) -> rows | negative`
//!   - `nativeFree(handle) -> 0`
//!   - `nativeEmpty() -> 0` (overhead probe only)
//!
//! Every entry point is wrapped in `catch_unwind`; a Rust panic must never
//! unwind across the JNI boundary.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::slice;

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use vt_core::Terminal;

const ERR_NULL_HANDLE: jint = -1;
const ERR_NOT_DIRECT: jint = -2;
const ERR_BUFFER_TOO_SMALL: jint = -3;
const ERR_PANIC: jint = -99;

#[inline]
unsafe fn terminal<'a>(handle: jlong) -> Option<&'a mut Terminal> {
    if handle == 0 {
        None
    } else {
        Some(&mut *(handle as *mut Terminal))
    }
}

#[inline]
unsafe fn direct<'a>(env: &JNIEnv, buffer: &JByteBuffer) -> Option<&'a mut [u8]> {
    let address = env.get_direct_buffer_address(buffer).ok()?;
    let capacity = env.get_direct_buffer_capacity(buffer).ok()?;
    if address.is_null() {
        return None;
    }
    Some(slice::from_raw_parts_mut(address, capacity))
}

#[no_mangle]
pub extern "system" fn Java_io_github_nyzeep_orchestella_terminal_RustJniProbe_nativeNew(
    _env: JNIEnv,
    _class: JClass,
    cols: jint,
    rows: jint,
) -> jlong {
    if cols <= 0 || rows <= 0 {
        return 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        Box::into_raw(Box::new(Terminal::new(cols as usize, rows as usize))) as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_io_github_nyzeep_orchestella_terminal_RustJniProbe_nativeFeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteBuffer,
    len: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(terminal) = (unsafe { terminal(handle) }) else {
            return ERR_NULL_HANDLE;
        };
        let Some(bytes) = (unsafe { direct(&env, &buffer) }) else {
            return ERR_NOT_DIRECT;
        };
        if len < 0 || len as usize > bytes.len() {
            return ERR_BUFFER_TOO_SMALL;
        }
        terminal.feed(&bytes[..len as usize]);
        0
    }))
    .unwrap_or(ERR_PANIC)
}

/// Writes `count` rows starting at logical row `first`.
///
/// `cp`, `fg`, `bg` are direct `IntBuffer`s of `count * cols` ints each;
/// `attrs` is a direct `ByteBuffer` of `count * cols` bytes. Returns the number
/// of rows actually written (0 when `first` is past the end).
#[no_mangle]
pub extern "system" fn Java_io_github_nyzeep_orchestella_terminal_RustJniProbe_nativeCopyRows(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first: jint,
    count: jint,
    cp: JByteBuffer,
    fg: JByteBuffer,
    bg: JByteBuffer,
    attrs: JByteBuffer,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(terminal) = (unsafe { terminal(handle) }) else {
            return ERR_NULL_HANDLE;
        };
        if first < 0 || count < 0 {
            return ERR_BUFFER_TOO_SMALL;
        }
        let (Some(cp), Some(fg), Some(bg), Some(attrs)) = (
            unsafe { direct(&env, &cp) },
            unsafe { direct(&env, &fg) },
            unsafe { direct(&env, &bg) },
            unsafe { direct(&env, &attrs) },
        ) else {
            return ERR_NOT_DIRECT;
        };
        let count = count as usize;
        let columns = terminal.cols();
        let cells = count * columns;
        if cp.len() < cells * 4 || fg.len() < cells * 4 || bg.len() < cells * 4 || attrs.len() < cells
        {
            return ERR_BUFFER_TOO_SMALL;
        }
        // Direct ByteBuffers are page-aligned, so the u32 views are aligned.
        let (cp_out, fg_out, bg_out, attrs_out) = unsafe {
            (
                slice::from_raw_parts_mut(cp.as_mut_ptr() as *mut u32, cells),
                slice::from_raw_parts_mut(fg.as_mut_ptr() as *mut u32, cells),
                slice::from_raw_parts_mut(bg.as_mut_ptr() as *mut u32, cells),
                slice::from_raw_parts_mut(attrs.as_mut_ptr(), cells),
            )
        };
        terminal.copy_rows(first as usize, count, cp_out, fg_out, bg_out, attrs_out) as jint
    }))
    .unwrap_or(ERR_PANIC)
}

#[no_mangle]
pub extern "system" fn Java_io_github_nyzeep_orchestella_terminal_RustJniProbe_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        unsafe { drop(Box::from_raw(handle as *mut Terminal)) };
        0
    }))
    .unwrap_or(ERR_PANIC)
}

/// Overhead probe: measures the cost of crossing the boundary with no work.
#[no_mangle]
pub extern "system" fn Java_io_github_nyzeep_orchestella_terminal_RustJniProbe_nativeEmpty(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let _ = ptr::null::<u8>();
    0
}
