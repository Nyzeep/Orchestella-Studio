# GPL Release Delivery Policy

> Status: active policy ｜ Effective: 2026-09-20 ｜ Applies: every public
> DroidLayer release that distributes GPL object code or executables.

This policy operationalizes ADR-0014 and ADR-0015. It does not create a
public repository, tag, Release, binary distribution, or authority to push.
The current `origin` configuration is not evidence that a repository is
public or that a Release Maintainer has accepted responsibility.

## 1. Terms and scope

- **Corresponding Source Release**: the public source delivery paired with
  one released binary version: an immutable release tag and a matching
  source-bundle asset.
- **Release Maintainer**: the named person or organization responsible for
  publishing, retaining, and recovering that source delivery. This role must
  be named before the first applicable release; it is not inferred from a Git
  username or repository remote.
- **Applicable object code**: a GPL executable or object-code work the
  project actually distributes, including an APK-bundled kernel, QEMU, or a
  GPL program inside a project-built initramfs.

This policy does not turn GPL reference projects into dependencies. It also
does not apply merely because a guest later downloads an upstream rootfs or a
user installs software inside an Instance.

## 2. Required public release set

Before publishing an applicable binary release, the Release Maintainer must
verify and record all of the following:

1. the approved public repository URL and public visibility;
2. the release version, fixed tag, and full commit identifier; a published
   tag must not be silently moved;
3. a release asset named for that version, such as
   `DroidLayer-<version>-corresponding-source.tar.xz`, with its SHA-256;
4. a release manifest that maps every distributed binary to its source
   record, license/NOTICE material, and source-bundle digest;
5. a named Release Maintainer and a recovery location/owner able to keep the
   public source delivery available for at least three years after the last
   applicable object-code distribution.

Missing or unverified fields close the release gate. They are not replaced by
an unpublished branch, a floating upstream URL, or a generic written-offer
contact phrase.

## 3. Source-bundle content

The Corresponding Source Release bundle must be reproducible from the release
tag and contain, for every applicable GPL work:

- the exact upstream source archive or complete source tree used to build it;
- project patches, final configuration, and generated build-input records;
- every script and interface definition needed to control compilation and
  installation of the distributed form;
- the source/license manifest, GPL text, required LICENSE/NOTICE/attribution
  material, and checksums for the bundle and released binaries.

For QEMU, the bundle and manifest must additionally satisfy
`QEMU_JNI_SUPPLY_CHAIN_POLICY.md` §6. For Ticket 25 guest assets, the existing
source records are inputs, not proof that a future Release bundle exists.

The bundle must contain no credential, signing material, user data, device
data, `.git` history, or unrelated workspace content.

## 4. Release gate and retention

CI may generate the release manifest and inspect the bundle, but a release is
not ready until an independent reviewer confirms that the published Release
contains the declared tag, source bundle, source records, licenses, and
checksums. The review must use the actual release candidate, not a previous
Ticket 25 output or an earlier tag.

The Release Maintainer records the final applicable object-code distribution
date, retention end date, public URL, and recovery location in the release
record. If the primary hosting location is removed or migrated, the recovery
location must preserve equivalent public access through the retention end
date. A written offer remains a fallback commitment; it does not waive this
public-source gate.

## 5. Current status

The delivery model is selected, but no first release is authorized. Before
the first applicable release, the user must confirm the public repository URL,
the Release Maintainer, and the recovery/retention owner. No agent may infer
those facts, create a tag, upload a source bundle, change repository
visibility, or push a Release without separate user authorization.
