# Oe3 screenshot goldens

Zero-device visual regression for the Compose UI migration (ticket UI3-11).
Tests render the real composables through Robolectric's native graphics on the
JVM; no emulator or device is involved.

## Commands

Record (rewrite the baselines):

```sh
JAVA_HOME=/opt/jdk17 ./gradlew :app:recordRoborazziDebug \
  -Porchestella.assets.allowMissingPayload=true \
  -Porchestella.repositories.useAliyunMirror=true
```

Verify (the CI gate; fails on any pixel difference):

```sh
JAVA_HOME=/opt/jdk17 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug \
  -Porchestella.assets.allowMissingPayload=true \
  -Porchestella.repositories.useAliyunMirror=true
```

The first Robolectric run downloads `android-all-instrumented` (~207 MB). The
Aliyun flag also points Robolectric's resolver at the mirror, because Maven
Central rate-limits the out-of-band download.

## Layout and naming

```
app/src/test/screenshots/
  oe3/tokens/dark-100.png          dark / 100% text scale
  oe3/tokens/dark-130.png          dark / 130%
  oe3/tokens/light-100.png         light / 100%
  oe3/tokens/light-130.png         light / 130%
  oe3/tokens/pure-black-100.png    pure black / 100%
  oe3/tokens/pure-black-130.png    pure black / 130%
  oe3/icons/dark.png               navigation + actions + distro marks
  oe3/icons/light.png
```

`<area>/<screen>-<mode>-<scale>` is fixed. Page tickets add their own area:
`oe3/screens/<page>-<mode>-<scale>.png`, shell goldens go under
`oe3/shell/`. One screen per file; do not encode dates or device names in the
path.

## Adding a golden

1. Add a test in `app/src/test/java/.../ui/oe3/screenshot/`, capture the
   smallest stable node with `onNodeWithTag("board").captureRoboImage(...)`.
2. Use a fixed board size and a fixed `@Config` qualifier. The existing boards
   use `w412dp-h<N>dp-mdpi`; 412dp is the Pixel reference width.
3. Never read the clock, preferences, network or a random seed. Disable motion
   with `Oe3Theme(reduceMotion = true)`; the PNG must be a pure function of the
   tokens and the composition.
4. Record, inspect the images, and commit the PNGs with the test. Record mode
   rewrites files silently; a reviewer must look at the diff.
   The repository ignores `*.png` globally, so the golden directory must be
   force-added explicitly (same policy as reviewed docs):

   ```sh
   git add -f app/src/test/screenshots/oe3
   ```

5. Keep new golden batches at 40 files or fewer per PR (ticket limit). The
   foundation set is 8 files; shell goldens wait for UI3-02, page goldens for
   their page tickets.

## Why these settings

- Robolectric `@Config(sdk = [35])`: SDK 36 requires Java 21, while the local
  acceptance command pins JDK 17. CI runs JDK 21 and uses the same SDK 35
  baseline, so goldens stay comparable.
- `mdpi` keeps the PNGs small (~50 KB each) and avoids density-dependent
  resampling. Dp geometry is unchanged.
- `@GraphicsMode(NATIVE)` is required for real pixel output; legacy graphics
  would produce blank or shader-less captures.
- The board is a pure function of `Oe3Theme`, `Oe3Colors`, the component
  primitives and the icon vectors. Fonts are the platform defaults
  (`FontFamily.Default` / `Monospace` in the tokens), so the golden locks
  layout, colour and hierarchy rather than font antialiasing.

## Failure output

`verifyRoborazziDebug` writes `<name>_actual.png` and `<name>_compare.png`
under `app/build/outputs/roborazzi`, plus the JUnit XML under
`app/build/test-results/testDebugUnitTest`. The CI workflow uploads those
directories when the job fails. A missing baseline is also a failure, not a
silent pass.

## Toolchain pins

- Roborazzi 1.79.0 (Apache-2.0), Robolectric 4.18, Compose BOM 2026.06.01
  (Compose UI 1.11.4).
- Haze is pinned to 1.6.10, not the 2.1.0 named in the ticket: Haze 2.1.0's
  Android artifact requires Compose 1.12.1 (AGP 9.1.0+, compileSdk 37) and
  kotlin-stdlib 2.4.21 / metadata 2.4, which this AGP 8.13.2 / compileSdk 36 /
  Kotlin 2.2.21 build cannot consume. `HazeDependencySmokeTest` proves the
  pinned artifact is readable; revisit the pin when the toolchain upgrades.
