package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** PL-02: one canonical, secret-free failure vocabulary for every layer. */
public final class ProtectedFailureTest {

    @Test
    public void platformBlockUsesOneCodeAndKeepsStageAndErrno() {
        ProtectedFailure failure = ProtectedFailure.fromTerminal(7, "execve_errno_13");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertEquals(ProtectedFailureClass.PLATFORM_BLOCKED, failure.failureClass());
        assertEquals("execve", failure.stage());
        assertEquals(Integer.valueOf(13), failure.errno());
        assertEquals("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13", failure.render());
    }

    @Test
    public void platformBlockDetailIsCaseInsensitiveAndPathFree() {
        ProtectedFailure failure = ProtectedFailure.fromTerminal(
                7, "EXECVE_ERRNO_13 at /mnt/apk/bin/sh");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertEquals("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13", failure.render());
        assertTrue(!failure.render().contains("/mnt"));
    }

    @Test
    public void otherFln1ErrorsDoNotBecomeAPlatformBlock() {
        ProtectedFailure failure = ProtectedFailure.fromTerminal(7, "open_pty_errno_13");

        assertEquals(ProtectedFailureCode.TERMINAL_ERROR, failure.code());
        assertEquals(ProtectedFailureClass.LIFECYCLE, failure.failureClass());
        assertEquals("open_pty", failure.stage());
        assertEquals(Integer.valueOf(13), failure.errno());
        assertEquals("TERMINAL_ERROR stage=open_pty errno=13", failure.render());
    }

    @Test
    public void unknownFln1ErrorHasNoStageAndNoErrno() {
        ProtectedFailure failure = ProtectedFailure.fromTerminal(9, null);

        assertEquals(ProtectedFailureCode.TERMINAL_ERROR, failure.code());
        assertNull(failure.stage());
        assertNull(failure.errno());
        assertEquals("TERMINAL_ERROR", failure.render());
    }

    @Test
    public void runnerReasonsMapToFixedClasses() {
        assertEquals(ProtectedFailureCode.PERMISSION_DENIED,
                ProtectedFailure.fromRunnerReason("PERMISSION_DENIED").code());
        assertEquals(ProtectedFailureClass.PERMISSION,
                ProtectedFailure.fromRunnerReason("HIDDEN_API_BLOCKED").failureClass());
        assertEquals(ProtectedFailureCode.PAYLOAD_VERIFY_FAILED,
                ProtectedFailure.fromRunnerReason("PAYLOAD_VERIFY_FAILED").code());
        assertEquals(ProtectedFailureClass.INSTALL,
                ProtectedFailure.fromRunnerReason("PROTECTED_UNAVAILABLE").failureClass());
        assertEquals(ProtectedFailureCode.STOP_UNCONFIRMED,
                ProtectedFailure.fromRunnerReason("STOP_UNCONFIRMED").code());
        assertEquals(ProtectedFailureClass.STOP,
                ProtectedFailure.fromRunnerReason("STOP_UNCONFIRMED").failureClass());
        assertEquals(ProtectedFailureCode.VSOCK_FAILED,
                ProtectedFailure.fromRunnerReason("VSOCK_FAILED").code());
        assertEquals(ProtectedFailureCode.SCHEMA_INVALID,
                ProtectedFailure.fromRunnerReason("CONFIG_INVALID").code());
        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN,
                ProtectedFailure.fromRunnerReason("something else").code());
        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN,
                ProtectedFailure.fromRunnerReason(null).code());
    }

    @Test
    public void legacyTerminalCodesNormalizeToThePlatformCode() {
        ProtectedFailure errorCode = ProtectedFailure.fromLegacy("TERMINAL_ERROR_FLN1_7");
        ProtectedFailure detail = ProtectedFailure.fromLegacy("execve_errno_13");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, errorCode.code());
        assertEquals(Integer.valueOf(13), errorCode.errno());
        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, detail.code());
        assertEquals(Integer.valueOf(13), detail.errno());
    }

    @Test
    public void legacyOutcomeNeedsEvidenceToBecomeAPlatformBlock() {
        ProtectedFailure withoutEvidence = ProtectedFailure.fromLegacy("PREFLIGHT_TERMINAL_BLOCKED");
        ProtectedFailure withEvidence = ProtectedFailure.fromLegacy("PREFLIGHT_TERMINAL_BLOCKED", 7, 13);

        assertEquals(ProtectedFailureCode.TERMINAL_ERROR, withoutEvidence.code());
        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, withEvidence.code());
        assertEquals("execve", withEvidence.stage());
    }

    @Test
    public void legacyMatrixCellIsParsedWithoutEchoingItsDetail() {
        ProtectedFailure failure = ProtectedFailure.fromLegacy(
                "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertEquals("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13", failure.render());
    }

    @Test
    public void rawAvcTextNeverBecomesAFreeFormCode() {
        ProtectedFailure failure = ProtectedFailure.fromLegacy(
                "avc: denied { execute_no_trans } for path=/mnt/apk/bin/sh");

        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN, failure.code());
        assertEquals("FASTLANE_UNKNOWN", failure.render());
        assertTrue(!failure.render().contains("avc"));
        assertTrue(!failure.render().contains("mnt"));
    }

    @Test
    public void renderRejectsUnsafeStageNames() {
        try {
            ProtectedFailure.of(ProtectedFailureCode.TERMINAL_ERROR, "execve /data/x", 13);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void barePlatformCodeHasNoStageAndNoErrno() {
        ProtectedFailure failure = ProtectedFailure.fromLegacy("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertNull(failure.stage());
        assertNull(failure.errno());
        assertEquals("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM", failure.render());
    }

    @Test
    public void platformCodeWithExecveStageDefaultsToEacces() {
        ProtectedFailure failure = ProtectedFailure.fromLegacy(
                "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertEquals("execve", failure.stage());
        assertEquals(Integer.valueOf(13), failure.errno());
        assertEquals("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13", failure.render());
    }

    @Test
    public void matrixCellKeepsFln1AndExecveErrnoEvidence() {
        ProtectedFailure failure = ProtectedFailure.fromLegacy(
                "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertEquals("execve", failure.stage());
        assertEquals(Integer.valueOf(13), failure.errno());
    }

    @Test
    public void legacyExecveAliasStaysCanonical() {
        ProtectedFailure failure = ProtectedFailure.fromLegacy("execve_errno_13");

        assertEquals(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, failure.code());
        assertEquals("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13", failure.render());
    }

    @Test
    public void blankUnknownAndPathBearingInputsStayUnknown() {
        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN,
                ProtectedFailure.fromLegacy(null).code());
        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN,
                ProtectedFailure.fromLegacy("   ").code());
        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN,
                ProtectedFailure.fromLegacy("not-a-known-code").code());
        assertEquals(ProtectedFailureCode.FASTLANE_UNKNOWN,
                ProtectedFailure.fromLegacy("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM /data/app/base.apk").code());
    }

    @Test
    public void everyCodeHasExactlyOneClass() {
        for (ProtectedFailureCode code : ProtectedFailureCode.values()) {
            assertNotNull(code.failureClass());
        }
        assertEquals(7, ProtectedFailureClass.values().length);
    }
}
