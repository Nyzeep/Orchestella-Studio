/*
 * Force a 64-byte aligned PT_TLS segment for an Android aarch64 static PIE.
 *
 * NDK r27 static PIE binaries otherwise emit PT_TLS p_align=8. Bionic
 * requires p_align >= 64 on aarch64 and aborts at startup with
 * "executable's TLS segment is underaligned". A used, 64-byte aligned
 * thread-local object in its own .tdata input section makes lld compute
 * p_align=64 instead of patching the ELF after the fact.
 *
 * This object is linked into distccd by build-distccd.sh; it has no runtime
 * behaviour and is referenced only through -Wl,--undefined.
 */
__thread unsigned char orchestella_tls_align[64]
    __attribute__((aligned(64), used, section(".tdata.orchestella"))) = {1};
