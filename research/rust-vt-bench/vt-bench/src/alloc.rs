//! Global allocation counters for the benchmark binary.
//!
//! `ALLOC_BYTES` counts bytes requested from the allocator (`alloc` /
//! `alloc_zeroed` = full layout size, `realloc` = growth delta), `DEALLOC_BYTES`
//! counts bytes returned (`dealloc` = layout size, `realloc` shrink = delta).
//! `live_bytes()` is therefore an approximation of heap bytes still held by the
//! measured region: it ignores allocator metadata and any allocation made before
//! the last `reset_alloc_counters()`.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};

static ALLOC_BYTES: AtomicU64 = AtomicU64::new(0);
static DEALLOC_BYTES: AtomicU64 = AtomicU64::new(0);
static ALLOC_CALLS: AtomicU64 = AtomicU64::new(0);

pub struct CountingAllocator;

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOC_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        ALLOC_CALLS.fetch_add(1, Ordering::Relaxed);
        System.alloc(layout)
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        ALLOC_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        ALLOC_CALLS.fetch_add(1, Ordering::Relaxed);
        System.alloc_zeroed(layout)
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        DEALLOC_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        System.dealloc(ptr, layout)
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        if new_size > layout.size() {
            ALLOC_BYTES.fetch_add((new_size - layout.size()) as u64, Ordering::Relaxed);
        } else {
            DEALLOC_BYTES.fetch_add((layout.size() - new_size) as u64, Ordering::Relaxed);
        }
        ALLOC_CALLS.fetch_add(1, Ordering::Relaxed);
        System.realloc(ptr, layout, new_size)
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

pub fn reset_alloc_counters() {
    ALLOC_BYTES.store(0, Ordering::SeqCst);
    DEALLOC_BYTES.store(0, Ordering::SeqCst);
    ALLOC_CALLS.store(0, Ordering::SeqCst);
}

pub fn allocated_bytes() -> u64 {
    ALLOC_BYTES.load(Ordering::SeqCst)
}

pub fn deallocated_bytes() -> u64 {
    DEALLOC_BYTES.load(Ordering::SeqCst)
}

/// Approximate live heap bytes since the last reset (cumulative alloc - free).
pub fn live_bytes() -> u64 {
    allocated_bytes().saturating_sub(deallocated_bytes())
}

#[allow(dead_code)]
pub fn alloc_calls() -> u64 {
    ALLOC_CALLS.load(Ordering::SeqCst)
}
