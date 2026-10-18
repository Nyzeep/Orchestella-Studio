# T8 red/green evidence

## Red run (temporary simulated revert)

Temporary change:
```java
credentialsOnly = false; // T8 red run: simulate a revert to full embedding.
```

Command:
```bash
./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' -Porchestella.repositories.useAliyunMirror=true --no-daemon
```

Result: BUILD FAILED, 2 tests completed, 1 failed.

```
provisionedLaunchNeverOpensOrReadsTheRootfs: java.lang.AssertionError: provisioned launch must not open the distro tarball expected:<0> but was:<1>
```

## Green run (production code restored)

Command:
```bash
./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' -Porchestella.repositories.useAliyunMirror=true --no-daemon
```
Result: BUILD SUCCESSFUL, 2 tests / 0 failures.
