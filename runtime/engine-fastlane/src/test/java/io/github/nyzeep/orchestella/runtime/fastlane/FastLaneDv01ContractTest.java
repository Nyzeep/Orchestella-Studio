package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class FastLaneDv01ContractTest {
    private static final String TOKEN = "0123456789abcdef";
    private static final String SHA = "a".repeat(64);

    @Test
    public void acceptsTheExpectedTokenLengths() {
        FastLaneDv01Contract.requireRunToken(TOKEN);
        FastLaneDv01Contract.requireRunToken("f".repeat(64));
    }

    @Test
    public void rejectsMalformedRunTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireRunToken(null));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireRunToken("0".repeat(15)));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireRunToken("0".repeat(65)));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireRunToken("0123456789ABCDEF"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireRunToken("0123456789abcdeg"));
    }

    @Test
    public void requiresLowercaseSha256() {
        FastLaneDv01Contract.requireSha256(SHA, "toolbox");
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireSha256("A".repeat(64), "toolbox"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireSha256("a".repeat(63), "toolbox"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireSha256(null, "toolbox"));
    }

    @Test
    public void onlyTheShippedPayloadNameIsAllowed() {
        FastLaneDv01Contract.requirePayloadName("libfastlane_payload.so");
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePayloadName("libpayload.so"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePayloadName("lib/fastlane_payload.so"));
    }

    @Test
    public void acceptsDefaultPortsAndRejectsCollisions() {
        FastLaneDv01Contract.requirePorts(
                FastLaneDv01Contract.DEFAULT_CONTROL_PORT,
                FastLaneDv01Contract.DEFAULT_TERMINAL_PORT,
                FastLaneDv01Contract.DEFAULT_PROXY_PORT,
                FastLaneDv01Contract.DEFAULT_DISTCC_PORT);
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePorts(5100, 5100, 5102, 5103));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePorts(1023, 5101, 5102, 5103));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePorts(5100, 65536, 5102, 5103));
    }

    @Test
    public void defaultPortsMatchThePayloadConfiguration() throws IOException {
        // The payload header is the normative default. A host default that
        // drifts from it produces "Connection reset by peer" at connect time.
        String header = Files.readString(findPayloadHeader());
        assertEquals(FastLaneDv01Contract.DEFAULT_CONTROL_PORT, port(header, "control_port"));
        assertEquals(FastLaneDv01Contract.DEFAULT_TERMINAL_PORT, port(header, "terminal_port"));
        assertEquals(FastLaneDv01Contract.DEFAULT_PROXY_PORT, port(header, "proxy_port"));
        assertEquals(FastLaneDv01Contract.DEFAULT_DISTCC_PORT, port(header, "distcc_port"));
    }

    private static int port(String header, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("uint16_t\\s+" + field + "\\s*=\\s*(\\d+)\\s*;")
                .matcher(header);
        assertTrue("payload header has no " + field, matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    private static Path findPayloadHeader() {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 8 && directory != null; depth++) {
            Path candidate = directory.resolve("fastlane-payload/include/fastlane/runtime.h");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError("fastlane-payload runtime.h not found from "
                + System.getProperty("user.dir"));
    }

    @Test
    public void cycleCountIsBoundedToTwenty() {
        FastLaneDv01Contract.requireCycleCount(1);
        FastLaneDv01Contract.requireCycleCount(FastLaneDv01Contract.DEFAULT_CYCLE_COUNT);
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireCycleCount(0));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireCycleCount(21));
    }

    @Test
    public void deviceAliasIsShortAndSafe() {
        FastLaneDv01Contract.requireDeviceAlias("qa-device-01");
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireDeviceAlias("device with spaces"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireDeviceAlias("x".repeat(33)));
    }

    @Test
    public void instanceIdIsUniquePerCycleAndFitsThePlatformLimit() {
        String first = FastLaneDv01Contract.instanceId(TOKEN, "main");
        String second = FastLaneDv01Contract.instanceId(TOKEN, "c01");
        assertTrue(first.startsWith("dv01-0123456789ab-"));
        assertEquals(false, first.equals(second));
        assertTrue(first.length() <= 64);
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.instanceId(TOKEN, "bad/suffix"));
    }

    @Test
    public void reportSchemaRequiresEveryPromisedKey() {
        Set<String> keys = new HashSet<>(FastLaneDv01Contract.REQUIRED_REPORT_KEYS);
        FastLaneDv01Contract.requireReportKeys(keys);
        keys.remove("cycles");
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireReportKeys(keys));
    }

    @Test
    public void requiredReportKeysCoverTheDv01EvidenceContract() {
        Set<String> keys = Set.copyOf(FastLaneDv01Contract.REQUIRED_REPORT_KEYS);
        assertTrue(keys.contains("schema"));
        assertTrue(keys.contains("device_alias"));
        assertTrue(keys.contains("sdk_int"));
        assertTrue(keys.contains("build_id"));
        assertTrue(keys.contains("apk_sha256"));
        assertTrue(keys.contains("payload_sha256"));
        assertTrue(keys.contains("toolbox_manifest_sha256"));
        assertTrue(keys.contains("auth_token"));
        assertTrue(keys.contains("stage_results"));
        assertTrue(keys.contains("cycles"));
        assertTrue(keys.contains("stop_timings_ms"));
        assertTrue(keys.contains("failure_codes"));
        assertTrue(keys.contains("negative_cases"));
        assertTrue(keys.contains("not_tested"));
    }

    @Test
    public void terminalDetailKeepsOnlyTheStageAndErrno() {
        assertEquals("execve_errno_13", FastLaneDv01Contract.terminalDetail(
                "fln1=7 EXEC_FAILED: child exec failed (execve errno=13)"));
        assertEquals("access_errno_13", FastLaneDv01Contract.terminalDetail(
                "fln1=7 EXEC_FAILED: tool is not executable (access errno=13)"));
        assertEquals("realpath_errno_2", FastLaneDv01Contract.terminalDetail(
                "fln1=7 EXEC_FAILED: executable not found (realpath errno=2)"));
        assertEquals("not_regular", FastLaneDv01Contract.terminalDetail(
                "fln1=7 EXEC_FAILED: tool is not a regular file"));
        assertEquals("unclassified", FastLaneDv01Contract.terminalDetail(
                "fln1=7 EXEC_FAILED: EXEC_FAILED"));
        assertEquals("unclassified", FastLaneDv01Contract.terminalDetail(null));
    }

    @Test
    public void failureTaxonomyCoversTheRequiredDv01Classes() {
        assertEquals("HIDDEN_API_BLOCKED", FastLaneDv01Contract.classifyFailure(
                FastLaneException.HIDDEN_API_BLOCKED));
        assertEquals("PERMISSION_DENIED", FastLaneDv01Contract.classifyFailure(
                FastLaneException.PERMISSION_DENIED));
        assertEquals("PROTECTED_UNAVAILABLE", FastLaneDv01Contract.classifyFailure(
                FastLaneException.PROTECTED_UNAVAILABLE));
        assertEquals("PAYLOAD_VERIFY_FAILED", FastLaneDv01Contract.classifyFailure(
                FastLaneException.PAYLOAD_VERIFY_FAILED));
        assertEquals("VSOCK_FAILED", FastLaneDv01Contract.classifyFailure(
                FastLaneException.VSOCK_FAILED));
        assertEquals("STOP_UNCONFIRMED", FastLaneDv01Contract.classifyFailure(
                FastLaneException.STOP_UNCONFIRMED));
        assertEquals("CONFIG_INVALID", FastLaneDv01Contract.classifyFailure(
                FastLaneException.CONFIG_INVALID));
        assertEquals("VM_CREATE_FAILED", FastLaneDv01Contract.classifyFailure(
                FastLaneException.VM_CREATE_FAILED));
        assertEquals("FASTLANE_UNKNOWN", FastLaneDv01Contract.classifyFailure("OTHER"));
        assertEquals("FASTLANE_UNKNOWN", FastLaneDv01Contract.classifyFailure(null));
    }

    @Test
    public void preflightStagesAreExactlyTheNineContractStages() {
        assertEquals(9, FastLaneDv01Contract.PREFLIGHT_STAGES.size());
        assertEquals(List.of(
                "parameter_validation",
                "platform_facts",
                "create",
                "run",
                "payload_ready",
                "control_hello",
                "terminal_pty",
                "stop",
                "delete"), FastLaneDv01Contract.PREFLIGHT_STAGES);
        Set<String> stages = new HashSet<>(FastLaneDv01Contract.PREFLIGHT_STAGES);
        FastLaneDv01Contract.requirePreflightStages(stages);
        stages.add("cycles");
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightStages(stages));
    }

    @Test
    public void preflightStageResultsRequireStatusAndReasonCode() {
        FastLaneDv01Contract.requirePreflightStageResultKeys(
                Set.of("status", "reason_code"));
        FastLaneDv01Contract.requirePreflightStageResultKeys(
                Set.of("status", "reason_code", "detail"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightStageResultKeys(Set.of("status")));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightStageResultKeys(
                        Set.of("status", "reason_code", "message")));
    }

    @Test
    public void preflightNotTestedKeepsTheThreeExactValues() {
        assertEquals(List.of(
                "encrypted_storage_persistence",
                "20_cycles",
                "negative_cases"), FastLaneDv01Contract.PREFLIGHT_NOT_TESTED);
        FastLaneDv01Contract.requirePreflightNotTested(
                FastLaneDv01Contract.PREFLIGHT_NOT_TESTED);
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightNotTested(
                        List.of("encrypted_storage_persistence", "20_cycles")));
    }

    @Test
    public void preflightAliasDefaultsToQaDevice() {
        assertEquals("qa-device", FastLaneDv01Contract.preflightDeviceAlias(null));
        assertEquals("qa-device", FastLaneDv01Contract.preflightDeviceAlias(""));
        assertEquals("device-A.1", FastLaneDv01Contract.preflightDeviceAlias("device-A.1"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.preflightDeviceAlias("device with spaces"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.preflightDeviceAlias("x".repeat(33)));
    }

    @Test
    public void preflightReportPathIsDeviceRelativeAndTokenScoped() {
        assertEquals("files/avf-qa/fastlane-dv01-preflight-" + TOKEN + ".json",
                FastLaneDv01Contract.preflightReportPath(TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.preflightReportPath("../escape"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.preflightReportPath("0123456789ABCDEF"));
    }

    @Test
    public void preflightCycleCountIsExactlyOne() {
        FastLaneDv01Contract.requirePreflightCycleCount(
                FastLaneDv01Contract.PREFLIGHT_CYCLE_COUNT);
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightCycleCount(0));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightCycleCount(20));
    }

    @Test
    public void preflightInstanceIdIsSingleAndTokenScoped() {
        String instance = FastLaneDv01Contract.preflightInstanceId(TOKEN);
        assertEquals("dv01-0123456789ab-preflight", instance);
        assertTrue(instance.length() <= 64);
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.preflightInstanceId("short"));
    }

    @Test
    public void preflightReusesTheFullParameterGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireRunToken("0123456789ABCDEF"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePayloadName("libpayload.so"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requireSha256("a".repeat(63), "toolbox"));
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePorts(5100, 5100, 5102, 5103));
    }

    @Test
    public void preflightFln1SevenExecveEaccesIsAPlatformBlock() {
        FastLaneDv01Contract.TerminalClassification terminal =
                FastLaneDv01Contract.classifyPreflightTerminal(
                        false, 7, "execve_errno_13", "TERMINAL_ERROR_FLN1_7");
        assertEquals("blocked", terminal.status);
        assertEquals(FastLaneDv01Contract.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM,
                terminal.reasonCode);
        assertEquals(7, terminal.fln1Code);
        assertEquals("execve", terminal.execStage);
        assertEquals(13, terminal.errno);
    }

    @Test
    public void preflightTerminalKeepsSuccessAndOtherFailuresSanitized() {
        FastLaneDv01Contract.TerminalClassification ok =
                FastLaneDv01Contract.classifyPreflightTerminal(
                        true, -1, null, "PREFLIGHT_TERMINAL_OK");
        assertEquals("pass", ok.status);
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK, ok.reasonCode);
        assertEquals("not-tested", ok.execStage);
        assertEquals(-1, ok.fln1Code);
        assertEquals(-1, ok.errno);

        FastLaneDv01Contract.TerminalClassification access =
                FastLaneDv01Contract.classifyPreflightTerminal(
                        false, 7, "access_errno_13", "TERMINAL_ERROR_FLN1_7");
        assertEquals("fail", access.status);
        assertEquals("TERMINAL_ERROR_FLN1_7", access.reasonCode);
        assertEquals(7, access.fln1Code);
        assertEquals("unknown", access.execStage);
        assertEquals(13, access.errno);

        FastLaneDv01Contract.TerminalClassification vsock =
                FastLaneDv01Contract.classifyPreflightTerminal(
                        false, -1, null, "VSOCK_FAILED");
        assertEquals("blocked", vsock.status);
        assertEquals("VSOCK_FAILED", vsock.reasonCode);
        assertEquals(-1, vsock.fln1Code);
        assertEquals(-1, vsock.errno);
    }

    @Test
    public void preflightOutcomeNormalizesImplementationValues() {
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK,
                FastLaneDv01Contract.normalizePreflightOutcome("PREFLIGHT_PASS", true));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.normalizePreflightOutcome(
                        FastLaneDv01Contract.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, true));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.normalizePreflightOutcome("VSOCK_FAILED", true));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.normalizePreflightOutcome("PREFLIGHT_FAILED", true));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL,
                FastLaneDv01Contract.normalizePreflightOutcome("PREFLIGHT_FAILED", false));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL,
                FastLaneDv01Contract.normalizePreflightOutcome("BLOCKED_EXTERNAL", true));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.normalizePreflightOutcome("FAILED", true));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL,
                FastLaneDv01Contract.normalizePreflightOutcome("FAILED", false));
    }

    @Test
    public void preflightOutcomeGateRequiresTerminalStopAndDelete() {
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK,
                FastLaneDv01Contract.preflightOutcome(true, "pass",
                        FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK, "CONFIRMED", "pass"));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.preflightOutcome(true, "pass",
                        FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK, "UNCONFIRMED", "pass"));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.preflightOutcome(true, "blocked",
                        FastLaneDv01Contract.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM,
                        "CONFIRMED", "pass"));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                FastLaneDv01Contract.preflightOutcome(true, "pass",
                        FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK, "CONFIRMED", "fail"));
        assertEquals(FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL,
                FastLaneDv01Contract.preflightOutcome(false, "blocked",
                        "PERMISSION_DENIED", "NOT_TESTED", "blocked"));
    }

    @Test
    public void preflightReportRequiresTheSchemaKeys() {
        Set<String> keys = new HashSet<>(
                FastLaneDv01Contract.PREFLIGHT_REQUIRED_REPORT_KEYS);
        FastLaneDv01Contract.requirePreflightReportKeys(keys);
        keys.remove("mode");
        assertThrows(IllegalArgumentException.class,
                () -> FastLaneDv01Contract.requirePreflightReportKeys(keys));
    }

    @Test
    public void preflightConstantsMatchTheCheckedInSchema() throws IOException {
        String schema = Files.readString(findPreflightSchema());
        assertTrue(schema.contains("\"" + FastLaneDv01Contract.PREFLIGHT_SCHEMA + "\""));
        assertTrue(schema.contains("\"" + FastLaneDv01Contract.PREFLIGHT_MODE + "\""));
        assertTrue(schema.contains("\"" + FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK + "\""));
        assertTrue(schema.contains(
                "\"" + FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED + "\""));
        assertTrue(schema.contains(
                "\"" + FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL + "\""));
        assertTrue(schema.contains(
                "\"" + FastLaneDv01Contract.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM + "\""));
        for (String stage : FastLaneDv01Contract.PREFLIGHT_STAGES) {
            assertTrue("schema has no stage " + stage, schema.contains("\"" + stage + "\""));
        }
        for (String value : FastLaneDv01Contract.PREFLIGHT_NOT_TESTED) {
            assertTrue("schema has no not_tested value " + value,
                    schema.contains("\"" + value + "\""));
        }
    }

    @Test
    public void preflightReaderUsesThePlatformVirtualizationFeature() throws IOException {
        String source = Files.readString(findRepoFile(
                "app/src/androidTest/java/io/github/nyzeep/orchestella/FastLaneDv01Probe.java"));
        // PackageManager exposes "...virtualization_framework" with an
        // underscore; a dot form silently reports false on every device.
        assertFalse(source.contains("\"android.software.virtualization.framework\""));
        assertTrue(source.contains("AvfCapabilityEvaluator.VIRTUALIZATION_FEATURE"));
    }

    private static Path findRepoFile(String relative) {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 8 && directory != null; depth++) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError(relative + " not found from "
                + System.getProperty("user.dir"));
    }

    private static Path findPreflightSchema() {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 8 && directory != null; depth++) {
            Path candidate = directory.resolve(
                    "tools/fastlane-build/preflight-report.schema.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError("preflight-report.schema.json not found from "
                + System.getProperty("user.dir"));
    }
}
