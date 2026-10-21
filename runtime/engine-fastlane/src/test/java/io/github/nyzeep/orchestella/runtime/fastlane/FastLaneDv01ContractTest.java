package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
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
        FastLaneDv01Contract.requireDeviceAlias("HONOR-MEY-AN00");
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
}
