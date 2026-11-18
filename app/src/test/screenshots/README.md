# UI screenshot goldens

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

**Running plain `testDebugUnitTest` silently re-records goldens.** Any Gradle
invocation whose task graph reaches Roborazzi without an explicit verify or
record property will rewrite the golden PNGs in place — including partial,
working-tree-state captures from parallel builds. When running screenshot-adjacent
tests outside the two commands above, always pass
`-Proborazzi.test.verify=true`; if `git status` shows unexpected PNG changes,
restore them from `HEAD` and re-run the record command on a quiet tree. Golden
files are gitignored (`*.png`) — committing updates requires `git add -f`.

## UI/UX v4 matrix

UI-16 adds 29 captures under `v4/`. Each filename follows
`{page}_{mode}_{variant}.png` and matches one test method under `ui.v4`.

| Coverage | Fixtures | Captures |
| --- | --- | ---: |
| Main pages | Workstation, instances, hardware, distros, config. First viewport of the production shell in the two mascot themes | 10 |
| Theme Studio | Entry list, presets, terminal customization, background and transparency | 4 |
| Instance creation | Storage, network, hardware, startup | 4 |
| Secondary pages | Port forwarding, VM hardware, SSH | 3 |
| Slider | Default, dragging with pointer held, disabled, minimum, maximum | 5 |
| Glass | Injected L0, L1, and L2. L2 uses the current L1 renderer pending UI-06b | 3 |

The older `oe3/`, `ui3/`, and `ui4/` files still participate in verification.
Use filtered recording to add or intentionally change a v4 baseline after
reviewing the rendered output.

```sh
./gradlew :app:recordRoborazziDebug --tests 'io.github.nyzeep.orchestella.ui.v4.*' \
  -Porchestella.assets.allowMissingPayload=true \
  -Porchestella.repositories.useAliyunMirror=true
```

Inspect the PNGs and stage only the intended files in `v4/`. Run unfiltered
`:app:verifyRoborazziDebug` afterward to compare all committed baselines.

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
  oe3/shell/dark-100.png           shell chrome / dark / 100%   (UI3-11A)
  oe3/shell/dark-130.png           shell chrome / dark / 130%
  oe3/shell/light-100.png          shell chrome / light / 100%
  oe3/shell/light-130.png          shell chrome / light / 130%
  oe3/shell/pure-black-100.png     shell chrome / pure black / 100%
  oe3/shell/pure-black-130.png     shell chrome / pure black / 130%
  oe3/nav/fallback-<mode>.png      nav bar glass fallback / α0.96 + top scrim (UI3-10)
  oe3/screens/theme-studio-root-<mode>.png      v4 UI-10 root entry list, 2 files
  oe3/screens/theme-studio-presets-<mode>.png   v4 UI-10 preset page, 2 files
  oe3/screens/theme-studio-<page>-dark.png      v4 UI-10 accent/background/terminal, 3 files
  oe3/screens/theme-terminal-<mode>-100.png     UI3-09 terminal sketch, 2 files (own test)
  ui3/dashboard/overview-<mode>.png     UI3-05 workstation page (UI3-11A)
  ui3/instances/<state>-<mode>.png      UI3-06 instance page states (own test)
  ui3/distros/<state>-<mode>.png        UI3-08 distros page states (own test)
  ui3/config/catalog-<mode>.png         UI3-04 config page (UI3-11A)
  ui3/hardware/ready-<mode>.png         UI3-07 hardware page (UI3-11A)
```

`<area>/<screen>-<mode>-<scale>` is fixed. Areas that render one fixed
composition (tokens, icons, shell) omit the `<screen>` segment and name only
`<mode>-<scale>`; page areas follow the UI3-06/08 `<state>-<mode>` convention.
One screen per file; do not encode dates or device names in the path.

Ownership notes (UI3-11A):

- Shell goldens live under `oe3/shell/` and the board composes the production
  `Oe3TopBar` / `Oe3Pager` / `Oe3NavBar` exactly like `Oe3App` does:
  `Oe3App` applies its own theme and does not forward a `pureBlack` argument,
  so the board passes the mode to `Oe3Theme` instead of rendering the app
  root. The real `Oe3App` composable is verified in `Oe3SemanticsTest`.
- Page goldens are unified under `ui3/<page>/` (`dashboard` = 05 工作站,
  `config` = 04 配置, `hardware` = 07 硬件), one dark + one light file each;
  their tests sit beside the screens in `ui/screens/<page>/`. The existing
  `ui3/instances/` (UI3-06) and `ui3/distros/` (UI3-08) sets stay untouched.
- The theme-studio goldens under `oe3/screens/` were re-recorded by UI-10 after
  the single-page studio became an entry list plus four independent pages; the
  old `theme-studio-<mode>-<scale>.png` files are retired, not renamed.
- When a page exposes no stable test tag, capturing `onRoot()` under a fixed
  window is the accepted fallback (dashboard does this); prefer a `testTag`
  when the page offers one.

Ownership notes (UI3-10):

- `oe3/nav/` goldens pin the nav bar's glass fallback draw path (the solid
  α0.96 surface with the 24dp top scrim that renders when backdrop blur is
  unavailable or the layer is switched off). Robolectric does not render blur,
  so the golden board forces the fallback deterministically: `Oe3Theme` at
  `glassLevel = Standard` with `LocalOe3GlassEnabled = false`. The default
  `None` level (solid, scrim-free) stays covered by the UI3-11A shell goldens;
  the real blur on API 31+ is a device-stage review item.

## Adding a golden

1. Add a test next to the surface it pins: shell and token boards in
   `app/src/test/java/.../ui/oe3/screenshot/`, page screens in their own
   `app/src/test/java/.../ui/screens/<page>/` package. Capture the smallest
   stable node with `captureRoboImage(...)` writing under
   `app/src/test/screenshots/`.
2. Use a fixed board size and a fixed `@Config` qualifier. The existing boards
   use `w412dp-h<N>dp-mdpi`; 412dp is the Pixel reference width.
3. Never read the clock, preferences, network or a random seed. Disable motion
   with `Oe3Theme(reduceMotion = true)`; the PNG must be a pure function of the
   tokens and the composition.
4. Record, inspect the images, and commit the PNGs with the test. Record mode
   rewrites files silently; a reviewer must look at the diff.
   The repository ignores `*.png` globally, so the golden directory must be
   force-added explicitly (same policy as reviewed docs) — this covers the
   `oe3/` and `ui3/` areas alike:

   ```sh
   git add -f app/src/test/screenshots
   ```

5. Keep new golden batches at 40 files or fewer per PR (ticket limit). The
   original UI3 inventory included 8 foundation goldens (tokens + icons), 6 shell
   (UI3-11A), 2 nav glass fallback (UI3-10), 6 config/dashboard/hardware pages
   (UI3-11A), 8 theme studio + terminal (UI3-09, owner: UI3-13), 4 instances
   (UI3-06) and 3 distros (UI3-08). The UI3-11A batch adds 12 files (6 shell +
   6 pages); the UI3-10 batch adds 2.

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
