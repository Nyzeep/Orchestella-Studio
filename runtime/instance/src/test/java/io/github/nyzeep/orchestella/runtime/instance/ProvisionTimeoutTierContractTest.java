package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Behavioral contract of the first-boot provision timeout tiers
 * (performance plan A3 / zero-device ticket 08): every {@code run_step} stage
 * is classified into one of four I/O classes, each class carries a default
 * budget that {@code ORCHESTELLA_PROVISION_TIMEOUT_*} can override, and an
 * unusable override falls back to the default with a visible signal.
 *
 * <p>The tests extract the real functions from
 * {@code guest-assets/scripts/orchestella-guest-provision.sh} and execute them
 * under {@code sh} via fixture scenarios, so the shipped source is what gets
 * exercised. The tier table and its semantics must stay identical in the
 * generated initramfs copy ({@code guest-assets/initramfs/init}); a drift
 * guard pins that here at gradle level, where previously only the python
 * drivers saw it.
 *
 * <p>This suite permanently commits the override-boundary cases that the
 * zero-device round (e130897) only verified by hand ("定向验证：抽函数 10 例").
 */
public final class ProvisionTimeoutTierContractTest {

    /** Stage → I/O class, mirroring the explicit case table in the product script. */
    private static final Map<String, String> STAGE_CLASSES = buildStageClasses();

    private static final Map<String, Long> CLASS_DEFAULTS = Map.of(
            "download", 900L,
            "transaction", 3600L,
            "compute", 1800L,
            "verify", 600L);

    private static Map<String, String> buildStageClasses() {
        Map<String, String> stages = new LinkedHashMap<>();
        stages.put("repository-refresh", "download");
        stages.put("certificate-bootstrap-refresh", "download");
        stages.put("certificate-bootstrap-install", "download");
        stages.put("package-install", "transaction");
        stages.put("package-recovery", "transaction");
        stages.put("init-repair", "transaction");
        stages.put("optional-upgrade", "transaction");
        stages.put("locale", "compute");
        stages.put("package-lock-recovery", "verify");
        stages.put("package-signing", "verify");
        stages.put("certificates", "verify");
        stages.put("machine-identity", "verify");
        stages.put("ssh-host-keys", "verify");
        stages.put("ssh-configuration", "verify");
        stages.put("service-enablement", "verify");
        stages.put("dbus-start", "verify");
        stages.put("ssh-start", "verify");
        stages.put("manager-reload", "verify");
        return stages;
    }

    private String script;
    private Path workspace;
    private Path functionsFixture;
    private Path classifyScenario;
    private Path secondsScenario;
    private Path unknownClassScenario;
    private Path budgetTimeoutScenario;

    @Before
    public void extractFunctionsIntoFixture() throws IOException {
        assumeTrue("POSIX sh required (CI runners and Git Bash provide it)",
                ProvisionScriptHarness.shAvailable());
        script = ProvisionScriptHarness.readProductScript();
        workspace = Files.createTempDirectory("a3-tier-contract");
        functionsFixture = ProvisionScriptHarness.writeFunctionsFixture(workspace,
                ProvisionScriptHarness.extractFunction(script, "provision_timeout_class_for_stage"),
                ProvisionScriptHarness.extractFunction(script, "provision_timeout_seconds_for_class"));
        classifyScenario = ProvisionScriptHarness.writeScenario(workspace, "classify.sh",
                ". \"$1\"\nprovision_timeout_class_for_stage \"$2\"\n");
        secondsScenario = ProvisionScriptHarness.writeScenario(workspace, "seconds.sh",
                ". \"$1\"\nprovision_timeout_seconds_for_class \"$2\"\n");
        unknownClassScenario = ProvisionScriptHarness.writeScenario(workspace, "unknown.sh",
                ". \"$1\"\nif provision_timeout_seconds_for_class \"$2\"; then :; "
                        + "else printf 'rc=%s\\n' \"$?\"; fi\n");
        budgetTimeoutScenario = ProvisionScriptHarness.writeScenario(workspace, "budget_timeout.sh",
                ". \"$1\"\n"
                        // Prefer the POSIX coreutils next to sh itself, so a Windows
                        // System32 timeout.exe can never shadow the GNU one.
                        + "bindir=\"$(dirname \"$(command -v sh)\")\"\n"
                        + "PATH=\"$bindir:$PATH\"\n"
                        + "command -v timeout >/dev/null 2>&1 || exit 77\n"
                        + "timeout 1 true 2>/dev/null || exit 77\n"
                        + "budget=\"$(provision_timeout_seconds_for_class \"$2\")\" || exit 9\n"
                        + "shift 2\n"
                        + "timeout \"$budget\" \"$@\"\n"
                        + "printf 'rc=%s\\n' \"$?\"\n");
    }

    @Test
    public void everyStageLiteralInTheScriptIsClassifiedByTheTable() throws IOException {
        assertFalse("stage classes table must not be empty", STAGE_CLASSES.isEmpty());
        for (Map.Entry<String, String> expected : STAGE_CLASSES.entrySet()) {
            String stage = expected.getKey();
            String classified = ProvisionScriptHarness.runScript(classifyScenario, Map.of(),
                    functionsFixture.toString(), stage).stdout().trim();
            assertEquals("I/O class of stage " + stage, expected.getValue(), classified);
        }
    }

    @Test
    public void unclassifiedStagesFallBackToTheShortVerifyBudget() throws IOException {
        // The `*` branch is a fail-safe: a stage nobody classified gets the
        // shortest budget instead of inheriting the longest one.
        String classified = ProvisionScriptHarness.runScript(classifyScenario, Map.of(),
                functionsFixture.toString(), "a-stage-nobody-classified").stdout().trim();
        assertEquals("verify", classified);
    }

    @Test
    public void runStepAndWrapperCallSitesOnlyUseClassifiedStages() {
        StringBuilder code = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            // Full-line comments mention run_step in prose ("run_step overwrites
            // this..."); only executable lines carry call sites.
            if (!line.trim().startsWith("#")) {
                code.append(line).append('\n');
            }
        }
        java.util.Set<String> callSites = new java.util.HashSet<>();
        Matcher literal = Pattern.compile("\\brun_step ([a-z0-9][a-z0-9-]*)").matcher(code);
        while (literal.find()) {
            callSites.add(literal.group(1));
        }
        Matcher dnfWrapper = Pattern.compile("\\bdnf_step ([a-z0-9][a-z0-9-]*)").matcher(code);
        while (dnfWrapper.find()) {
            callSites.add(dnfWrapper.group(1));
        }
        Matcher zypperWrapper = Pattern.compile("\\bzypper_step ([a-z0-9][a-z0-9-]*)").matcher(code);
        while (zypperWrapper.find()) {
            callSites.add(zypperWrapper.group(1));
        }
        assertFalse("run_step call-site scan found nothing; script format drifted", callSites.isEmpty());
        java.util.Set<String> unknown = new java.util.HashSet<>(callSites);
        unknown.removeAll(STAGE_CLASSES.keySet());
        assertTrue("run_step/dnf_step/zypper_step stages missing from the tier table: " + unknown,
                unknown.isEmpty());
    }

    @Test
    public void tierTableAndDefaultsAreIdenticalInTheGeneratedInit() throws IOException {
        String init = ProvisionScriptHarness.readGeneratedInit();
        for (String function : new String[] {
                "provision_timeout_class_for_stage", "provision_timeout_seconds_for_class"}) {
            assertEquals(function + " must not drift between product script and generated init",
                    ProvisionScriptHarness.extractFunction(script, function),
                    ProvisionScriptHarness.extractFunction(init, function));
        }
    }

    @Test
    public void everyClassDefaultsToItsBudgetWithoutAnOverride() throws IOException {
        for (Map.Entry<String, Long> tier : CLASS_DEFAULTS.entrySet()) {
            String out = ProvisionScriptHarness.runScript(secondsScenario, Map.of(),
                    functionsFixture.toString(), tier.getKey()).stdout().trim();
            assertEquals("default budget of the " + tier.getKey() + " class",
                    String.valueOf(tier.getValue()), out);
        }
    }

    @Test
    public void unknownClassHasNoBudgetAtAll() throws IOException {
        ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(
                unknownClassScenario, Map.of(), functionsFixture.toString(), "not-a-class");
        assertTrue("unknown class must fail the lookup", result.stdout().contains("rc=1"));
    }

    @Test
    public void positiveOverrideIsInjectedForEveryClass() throws IOException {
        for (String tierClass : CLASS_DEFAULTS.keySet()) {
            Map<String, String> env = Map.of(overrideVariable(tierClass), "1234");
            String out = ProvisionScriptHarness.runScript(secondsScenario, env,
                    functionsFixture.toString(), tierClass).stdout().trim();
            assertEquals("injected budget of the " + tierClass + " class", "1234", out);
        }
    }

    @Test
    public void unusableOverrideFallsBackToTheDefaultAndSaysSo() throws IOException {
        // Permanently commits the directed boundary cases from the zero-device
        // round: signed, suffixed, zero-prefixed, zero and non-numeric values
        // are all rejected; the default stays in force and one IGNORED line
        // names the class and the rejected value.
        String[] rejected = {"0", "0900", "900s", "-60", "1e3", "abc", "9 9", "+900"};
        for (String bad : rejected) {
            Map<String, String> env = Map.of(overrideVariable("transaction"), bad);
            ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(
                    secondsScenario, env, functionsFixture.toString(), "transaction");
            assertEquals("override <" + bad + "> must fall back to the transaction default",
                    "3600", result.stdout().trim());
            assertTrue("override <" + bad + "> must print one IGNORED line",
                    result.stderr().contains("ORCHESTELLA_PROVISION_TIMEOUT_OVERRIDE_IGNORED "
                            + "class=transaction value=" + bad));
        }
    }

    @Test
    public void emptyOverrideKeepsTheDefaultQuietly() throws IOException {
        Map<String, String> env = Map.of(overrideVariable("download"), "");
        ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(
                secondsScenario, env, functionsFixture.toString(), "download");
        assertEquals("empty override means unset", "900", result.stdout().trim());
        assertFalse("an unset override must not be reported as ignored",
                result.stderr().contains("OVERRIDE_IGNORED"));
    }

    @Test
    public void pureDigitOverrideIsAcceptedWhateverItsSize() throws IOException {
        // The function validates shape only; whether a huge budget is sensible
        // is the overrider's decision, so 10 digits pass through unchanged.
        Map<String, String> env = Map.of(overrideVariable("verify"), "9999999999");
        String out = ProvisionScriptHarness.runScript(secondsScenario, env,
                functionsFixture.toString(), "verify").stdout().trim();
        assertEquals("9999999999", out);
    }

    @Test
    public void injectedBudgetReachesTheTimeoutTool() throws IOException {
        // End to end at function level: the override flows out of the resolver
        // into `timeout`, which then kills the wrapped command (124).
        Map<String, String> env = Map.of(overrideVariable("verify"), "1");
        ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(
                budgetTimeoutScenario, env,
                functionsFixture.toString(), "verify", "sleep", "2");
        assumeTrue("GNU-style timeout tool not usable on this host", result.exitCode() != 77);
        assertTrue("injected 1s budget must time out a 2s sleep; rc=" + result.exitCode()
                        + " stdout=" + result.stdout() + " stderr=" + result.stderr(),
                result.stdout().contains("rc=124"));
    }

    @Test
    public void defaultBudgetWrapsAQuickCommandWithoutTimeout() throws IOException {
        ProvisionScriptHarness.ShResult result = ProvisionScriptHarness.runScript(
                budgetTimeoutScenario, Map.of(),
                functionsFixture.toString(), "verify", "true");
        assumeTrue("GNU-style timeout tool not usable on this host", result.exitCode() != 77);
        assertTrue("600s default budget must let `true` finish; rc=" + result.exitCode()
                        + " stdout=" + result.stdout() + " stderr=" + result.stderr(),
                result.stdout().contains("rc=0"));
    }

    private static String overrideVariable(String tierClass) {
        return ("ORCHESTELLA_PROVISION_TIMEOUT_" + tierClass).toUpperCase(java.util.Locale.ROOT);
    }
}
