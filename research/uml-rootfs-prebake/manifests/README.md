# Manifest samples

These files are provenance samples emitted by `build-rootfs.sh` (renamed from the
build output `manifest.json`). The artifacts themselves are not committed: they
are 100–220 MB and are rebuilt from the pinned inputs recorded here.

| File | Purpose |
| --- | --- |
| `alpine-3.22.8-aarch64.json` | target ARM64 musl image |
| `debian-trixie-aarch64.json` | target ARM64 glibc image |
| `alpine-3.22.8-x86_64-reference.json` | x86_64 reference used for UML boot/T3 evidence |
| `debian-trixie-x86_64-reference.json` | x86_64 glibc reference used for UML boot/T3 evidence |

Validate any sample with:

```bash
/usr/bin/python3 -m jsonschema -i manifests/alpine-3.22.8-aarch64.json manifest.schema.json
```

`build.startedAtUtc` and `build.durationSeconds` differ between runs and are not
part of the artifact identity. The artifact identity is `outputs[].sha256`, which
is reproducible for a fixed input set as documented in the top-level README.
