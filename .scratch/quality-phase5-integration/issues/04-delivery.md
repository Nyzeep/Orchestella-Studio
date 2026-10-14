# 04 Producer, validation, independent review and delivery

Type: task
Status: pending
Blocked by: 01, 02, 03

Rebuild one production static AArch64 Linux guest-agent with pinned NDK/musl/UAPI/builtins provenance, non-executable stack, no interpreter/dynamic segment. Repack the unique agent preserving the other 428 entries, generate init from final canonical sources and verify source/payload/NOTICE/runtime hashes and APK identity/version/licenses. Run complete App union, all applicable runtime/guest/profile/display/services/AVF/9p checks, strict Java/native builds and lint without weakening gates. Inspect devices and perform only supported directly relevant regressions if available; missing devices/permissions stay NOT_RUN.

Two independent read-only reviewers cover quality/protocol/lifecycle and Phase5/data/product/producer boundaries, respectively, using the final diff and new evidence. Fix actual blockers and revalidate affected gates. Produce canonical Chinese implementation, verification and review reports. Stage only reviewed exact paths; exclude secrets, disks, signing material and large ignored artifacts. Ordinary push only to verified git@github.com:Nyzeep/Orchestella-Studio.git integration/quality-phase5; no force/main/release/reference upstream writes. Final delivery includes exact branch/commits/push status, APK size/hash/ID/version, actual tests/device result and unrun/unimplemented matrix, plus owned temporary resource terminal state.
