package io.github.nyzeep.orchestella.runtime.instance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Behavioral contract of the pacman {@code db.lck} recovery path
 * (performance plan A3 / zero-device ticket 07): a lock is removed only when
 * its lack of a holder is proven (descriptor scan or {@code fuser}), an
 * unprovable state never deletes, a live holder keeps the lock, and recovery
 * is idempotent — the second call after a successful removal stays at 0.
 *
 * <p>The tests extract {@code pacman_lock_holder} and {@code recover_pacman_lock}
 * from the product script and run them under {@code sh} with synthetic fixture
 * directories, mirroring the device-free "mirror state injection" the plan asks
 * for. Holder evidence is made deterministic on every host by injecting a fake
 * {@code fuser} through {@code PATH}; the descriptor-scan case uses the host's
 * real {@code /proc} and is skipped where that is unavailable.
 */
public final class PacmanLockRecoveryContractTest {

    private String script;
    private Path workspace;
    private Path functionsFixture;
    private Path recoveryScenario;

    @Before
    public void extractFunctionsIntoFixture() throws IOException {
        assumeTrue("POSIX sh required (CI runners and Git Bash provide it)",
                ProvisionScriptHarness.shAvailable());
        script = ProvisionScriptHarness.readProductScript();
        workspace = Files.createTempDirectory("a3-lock-contract");
        functionsFixture = ProvisionScriptHarness.writeFunctionsFixture(workspace,
                ProvisionScriptHarness.extractFunction(script, "pacman_lock_holder"),
                ProvisionScriptHarness.extractFunction(script, "recover_pacman_lock"));
        recoveryScenario = ProvisionScriptHarness.writeScenario(workspace, "recover.sh",
                ". \"$1\"\n"
                        + "recover_pacman_lock \"$2\" \"$3\"; printf 'rc=%s\\n' \"$?\"\n"
                        + "[ -e \"$2\" ] && echo exists=yes || echo exists=no\n");
    }

    /** A proc_root fixture that offers neither /1 nor /self, so it yields no descriptor evidence. */
    private static Path evidencelessProcFixture(Path workspace) throws IOException {
        return Files.createDirectories(workspace.resolve("proc-fixture"));
    }

    @Test
    public void staleLockIsRemovedAndRecoveryIsIdempotent() throws IOException {
        Path procFixture = evidencelessProcFixture(workspace);
        Path bin = workspace.resolve("bin-not-in-use");
        ProvisionScriptHarness.writeFakeTool(bin, "fuser", 1);
        Path lock = Files.createFile(workspace.resolve("stale-db.lck"));
        Map<String, String> env = Map.of("PATH",
                ProvisionScriptHarness.pathWithPrepended(bin, File.pathSeparator));
        // First recovery: the stale lock is proven unheld and is removed.
        ProvisionScriptHarness.ShResult first = ProvisionScriptHarness.runScript(recoveryScenario, env,
                functionsFixture.toString(), ProvisionScriptHarness.toShPath(lock),
                ProvisionScriptHarness.toShPath(procFixture));
        assertTrue("first recovery must succeed: " + first, first.stdout().contains("rc=0"));
        assertTrue("stale lock must be gone after the first recovery: " + first,
                first.stdout().contains("exists=no"));
        // Second recovery on the already-removed lock: still 0, still gone, no error —
        // the guarded call site ([ -e "$pacman_lock" ]) in the product script can only
        // observe this as a no-op.
        ProvisionScriptHarness.ShResult second = ProvisionScriptHarness.runScript(recoveryScenario, env,
                functionsFixture.toString(), ProvisionScriptHarness.toShPath(lock),
                ProvisionScriptHarness.toShPath(procFixture));
        assertTrue("recovery must be idempotent (second rc stays 0): " + second,
                second.stdout().contains("rc=0"));
        assertTrue("second recovery must not recreate anything: " + second,
                second.stdout().contains("exists=no"));
    }

    @Test
    public void fuserHeldLockIsKeptAndRecoveryConvergesAfterRelease() throws IOException {
        Path procFixture = evidencelessProcFixture(workspace);
        Path heldBin = workspace.resolve("bin-in-use");
        ProvisionScriptHarness.writeFakeTool(heldBin, "fuser", 0);
        Path freeBin = workspace.resolve("bin-not-in-use");
        ProvisionScriptHarness.writeFakeTool(freeBin, "fuser", 1);
        Path lock = Files.createFile(workspace.resolve("held-db.lck"));
        // While the holder lives (fuser reports in-use), the lock survives and the
        // recovery refuses with 2; the product call site turns that into
        // package-install/package-lock-held, never offline.
        ProvisionScriptHarness.ShResult held = ProvisionScriptHarness.runScript(recoveryScenario,
                Map.of("PATH", ProvisionScriptHarness.pathWithPrepended(heldBin, File.pathSeparator)),
                functionsFixture.toString(), ProvisionScriptHarness.toShPath(lock),
                ProvisionScriptHarness.toShPath(procFixture));
        assertTrue("held lock must refuse removal with rc=2: " + held,
                held.stdout().contains("rc=2"));
        assertTrue("held lock must survive: " + held, held.stdout().contains("exists=yes"));
        // After the holder exits, the same call converges to a clean removal.
        ProvisionScriptHarness.ShResult released = ProvisionScriptHarness.runScript(recoveryScenario,
                Map.of("PATH", ProvisionScriptHarness.pathWithPrepended(freeBin, File.pathSeparator)),
                functionsFixture.toString(), ProvisionScriptHarness.toShPath(lock),
                ProvisionScriptHarness.toShPath(procFixture));
        assertTrue("recovery after release must succeed: " + released,
                released.stdout().contains("rc=0"));
        assertTrue("released lock must be removed: " + released,
                released.stdout().contains("exists=no"));
    }

    @Test
    public void unprovableLockIsNeverDeleted() throws IOException {
        // No /proc descriptor evidence and no fuser on PATH: the state cannot be
        // proven, so the conservative answer is "treat as held, never delete".
        // The lock path is a parameter here — deliberately not the hardcoded
        // /var/lib/pacman one — which also pins the issue-07 no-hardcoding rule.
        Path procFixture = evidencelessProcFixture(workspace);
        Path emptyBin = Files.createDirectories(workspace.resolve("bin-empty"));
        Path lock = Files.createFile(workspace.resolve("unprovable-db.lck"));
        ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(recoveryScenario,
                Map.of("PATH", ProvisionScriptHarness.toShPath(emptyBin)),
                functionsFixture.toString(), ProvisionScriptHarness.toShPath(lock),
                ProvisionScriptHarness.toShPath(procFixture));
        assertTrue("unprovable lock must refuse removal with rc=2: " + result,
                result.stdout().contains("rc=2"));
        assertTrue("unprovable lock must survive: " + result, result.stdout().contains("exists=yes"));
    }

    @Test
    public void descriptorScanDetectsALiveHolderWithoutFuserAndConvergesAfterItExits()
            throws IOException {
        assumeTrue("host must expose /proc/<pid>/fd descriptor symlinks",
                ProvisionScriptHarness.runInline("[ -d /proc/self/fd ]").exitCode() == 0);
        // On Windows cygpath translates the fixture root into the sh-visible form
        // so the /proc fd readlink targets match the scanned path.
        Path lockParent = Files.createDirectories(workspace.resolve("root-relative"));
        String body = ". \"$1\"\n"
                + "fix=\"$2\"\n"
                + "command -v cygpath >/dev/null 2>&1 && fix=\"$(cygpath -u \"$fix\")\" || true\n"
                + "lock=\"$fix/db.lck\"\n"
                + ": > \"$lock\"\n"
                + "sh -c 'exec 9<\"$1\"; exec sleep 30' sh \"$lock\" &\n"
                + "holder=$!\n"
                + "sleep 1\n"
                + "if pacman_lock_holder \"$lock\" /proc; then printf 'scan_rc=0\\n'; "
                + "else printf 'scan_rc=%s\\n' \"$?\"; fi\n"
                + "[ -e \"$lock\" ] && echo held_exists=yes || echo held_exists=no\n"
                + "kill \"$holder\" 2>/dev/null\n"
                + "wait \"$holder\" 2>/dev/null\n"
                + "recover_pacman_lock \"$lock\" /proc; printf 'recover_rc=%s\\n' \"$?\"\n"
                + "[ -e \"$lock\" ] && echo after_exists=yes || echo after_exists=no\n";
        ProvisionScriptHarness.writeScenario(workspace, "fdscan.sh", body);
        ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(
                workspace.resolve("fdscan.sh"), Map.of(),
                functionsFixture.toString(), ProvisionScriptHarness.toShPath(lockParent));
        assertTrue("descriptor scan must detect the live holder: " + result,
                result.stdout().contains("scan_rc=0"));
        assertTrue("holder-opened lock must survive the scan: " + result,
                result.stdout().contains("held_exists=yes"));
        assertTrue("recovery must converge after the holder exits: " + result,
                result.stdout().contains("recover_rc=0"));
        assertTrue("lock must be gone after the holder exited: " + result,
                result.stdout().contains("after_exists=no"));
    }

    @Test
    public void lockFunctionsAreIdenticalInTheGeneratedInit() throws IOException {
        String init = ProvisionScriptHarness.readGeneratedInit();
        for (String function : new String[] {
                "pacman_lock_holder", "recover_pacman_lock", "run_lock_recovery_stage"}) {
            assertEquals(function + " must not drift between product script and generated init",
                    ProvisionScriptHarness.extractFunction(script, function),
                    ProvisionScriptHarness.extractFunction(init, function));
        }
    }
}
