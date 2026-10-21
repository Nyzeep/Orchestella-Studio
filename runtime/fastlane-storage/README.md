# runtime/fastlane-storage — design seam (P06)

**Boundary.** This module is host-side, pure JVM. It never sees a device or a
`DocumentFile`. It consumes a tar byte stream that the guest payload produced
(P02 `EXPORT_DATA` frames, unwrapped by the control mux) and writes it through an
`ExportSink`. Import is the reverse: a tar stream from a SAF-backed `ImportSource`
is validated here and emitted as a byte stream for the payload's staging importer.

**Ownership.** `FastLaneStorageLayout` owns names, marker version, and the rule that
every path component is a real directory, not a symlink. `TarReader` owns path
validation. `FastLaneExporter` owns the sink lifecycle: open, stream, verify,
`commit()` or `abort()`; a failed export never commits. `FastLaneImporter` owns the
send lifecycle and aborts on any validation error. The guest owns the encrypted
storage; the host never decrypts or copies plaintext to app storage.

**Manifest.** One entry per regular file: relative path, size, SHA-256, mtime
(epoch seconds). The manifest is emitted after the archive is fully streamed, so it
describes exactly the bytes that were committed. Entries are sorted by path.

**Failure taxonomy.** `PATH_REJECTED` (absolute, `..`, symlink, device, duplicate),
`MANIFEST_INVALID`, `LIMIT_EXCEEDED`, `UNSUPPORTED_FORMAT`, `LAYOUT_INVALID`,
`IO_FAILED`, `CANCELLED`. Every failure maps to `STORAGE_FAILED` at the Fast Lane
boundary; cancellation is not an error the UI reports as corruption.

**Test seam.** `ExportSink` / `ImportSource` are interfaces; tests use a local
filesystem sink with temp-file + atomic move and an in-memory source. SAF adapters
live in `:app` and implement these interfaces. No Android type enters this module.
