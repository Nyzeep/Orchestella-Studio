# T8 red/green evidence

## Red run 1: bootstrapper reverts to full embedding

Temporary change in `QemuRuntimeBootstrapper.bootstrap(..., boolean credentialsOnly)`:

```java
credentialsOnly = false; // T8 red run: simulate a revert to full embedding.
```

Command:

```bash
./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' -Porchestella.repositories.useAliyunMirror=true --no-daemon
```

Result: BUILD FAILED, 3 tests completed, 2 failed.

```
provisionedLaunchNeverOpensOrReadsTheRootfs: java.lang.AssertionError: provisioned launch must not open the distro tarball expected:<0> but was:<1>
launcherDecisionRequiresPersistedEvidenceForTheCurrentCatalogDigest: java.lang.AssertionError at ProvisionedLaunchRegressionTest.java:100
```

## Red run 2: launcher decision forced to the full path

Temporary change in `QemuInstanceLauncher.credentialsOnlyLaunch(...)`:

```java
if (true) return false; // T8 red run: simulate a launcher revert.
```

Command:

```bash
./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' -Porchestella.repositories.useAliyunMirror=true --no-daemon
```

Result: BUILD FAILED, 3 tests completed, 1 failed.

```
launcherDecisionRequiresPersistedEvidenceForTheCurrentCatalogDigest: java.lang.AssertionError at ProvisionedLaunchRegressionTest.java:100
```

## Green run: production code restored

Command:

```bash
./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' -Porchestella.repositories.useAliyunMirror=true --no-daemon
```

Result: BUILD SUCCESSFUL, 3 tests / 0 failures.

```
<testsuite name="io.github.nyzeep.orchestella.ProvisionedLaunchRegressionTest" tests="3" skipped="0" failures="0" errors="0" ...>
```
