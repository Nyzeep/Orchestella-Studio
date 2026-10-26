package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneDv01Contract;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneExitInfo;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.util.List;
import org.junit.Test;

/**
 * PVM_PAYLOAD lane starter, exercised against a fake platform boundary: no
 * Android, no VM, and no real readiness timeout. Every refusal is asserted by
 * its fixed code, never by message text.
 */
public final class PvmPayloadLaneStarterTest {
    private static final String PAYLOAD_TRIPLE = "aarch64-unknown-linux-android";

    @Test
    public void startBuildsTheCompilerPayloadSpecAndWrapsTheSession() throws Exception {
        FakeApi api = new FakeApi();
        LaneRuntime lane = starter(api, "").start();

        assertTrue(lane instanceof ProtectedLaneRuntime);
        assertEquals(LaneId.ACCEL, lane.laneId());
        assertEquals(1, api.startCount);
        assertTrue(lane.isAlive());

        FastLaneLaunchSpec spec = api.captured;
        assertNotNull(spec);
        assertEquals("pvm-payload-1", spec.instanceId());
        assertEquals("libpvm_compiler_payload.so", spec.payloadBinaryName());
        assertEquals(2048, spec.memoryMb());
        assertEquals(FastLaneLaunchSpec.CpuTopology.ONE_CPU, spec.cpuTopology());
        assertEquals(FastLaneLaunchSpec.DebugLevel.NONE, spec.debugLevel());
        assertEquals(FastLaneDv01Contract.DEFAULT_CONTROL_PORT, spec.controlPort());
        assertEquals(FastLaneDv01Contract.DEFAULT_TERMINAL_PORT, spec.terminalPort());
        assertEquals(FastLaneDv01Contract.DEFAULT_PROXY_PORT, spec.proxyPort());
        assertEquals(FastLaneDv01Contract.DEFAULT_DISTCC_PORT, spec.distccPort());
        assertTrue("encrypted storage must stay positive", spec.encryptedStorageBytes() > 0L);
        // A compiler payload has no FLN1 toolbox; the absence is declared.
        assertEquals("", spec.toolboxManifestSha256());
    }

    @Test
    public void readinessFollowsTheBackendWithoutBlockingStart() throws Exception {
        FakeApi api = new FakeApi();
        api.handle.ready = false;
        PvmPayloadLaneStarter starter = starter(api, "");
        LaneRuntime lane = starter.start();

        // start() returned while the payload was not ready: the LaneStarter
        // contract (no waiting inside start) still holds.
        assertFalse(lane.isReady());

        api.handle.ready = true;
        assertEquals(PvmPayloadLaneStarter.Readiness.READY, starter.awaitReady(lane, 0L));
    }

    @Test
    public void readyTimeoutReportsLoadFailed() throws Exception {
        FakeApi api = new FakeApi();
        api.handle.ready = false;
        PvmPayloadLaneStarter starter = starter(api, "");
        LaneRuntime lane = starter.start();

        // Zero budget: the loop checks once and gives up without sleeping.
        assertEquals(PvmPayloadLaneStarter.Readiness.LOAD_FAILED,
                starter.awaitReady(lane, 0L));
    }

    @Test
    public void deadVmReportsLoadFailed() throws Exception {
        FakeApi api = new FakeApi();
        PvmPayloadLaneStarter starter = starter(api, "");
        LaneRuntime lane = starter.start();
        api.handle.status = 0;

        assertFalse(lane.isAlive());
        assertEquals(PvmPayloadLaneStarter.Readiness.LOAD_FAILED,
                starter.awaitReady(lane, 0L));
    }

    @Test
    public void unavailableProtectedVmKeepsTheNoProtectedCode() throws Exception {
        String[] reasons = {
            FastLaneException.PROTECTED_UNAVAILABLE,
            FastLaneException.HIDDEN_API_BLOCKED,
            FastLaneException.PERMISSION_DENIED,
        };
        for (String reason : reasons) {
            FakeApi api = new FakeApi();
            api.failure = new FastLaneException(reason, "fake platform refusal");
            assertEquals(reason,
                    PvmPayloadLaneStarter.PAYLOAD_UNAVAILABLE_NO_PROTECTED,
                    refusalCode(starter(api, "")));
        }
    }

    @Test
    public void loadSideFailuresKeepTheLoadFailedCode() throws Exception {
        String[] reasons = {
            FastLaneException.PAYLOAD_VERIFY_FAILED,
            FastLaneException.VM_CREATE_FAILED,
            FastLaneException.CONFIG_INVALID,
            FastLaneException.VSOCK_FAILED,
            FastLaneException.STOP_UNCONFIRMED,
        };
        for (String reason : reasons) {
            FakeApi api = new FakeApi();
            api.failure = new FastLaneException(reason, "fake platform refusal");
            assertEquals(reason,
                    PvmPayloadLaneStarter.PAYLOAD_LOAD_FAILED,
                    refusalCode(starter(api, "")));
        }
    }

    @Test
    public void unexpectedRuntimeFailureKeepsTheLoadFailedCode() {
        FastLaneVirtualMachineApi broken = new FastLaneVirtualMachineApi() {
            @Override
            public FastLaneHandle start(FastLaneLaunchSpec spec) {
                throw new IllegalStateException("fake platform breakage");
            }

            @Override
            public FastLaneChannel connect(FastLaneHandle handle, int port) {
                throw new IllegalStateException("fake platform breakage");
            }
        };
        assertEquals(PvmPayloadLaneStarter.PAYLOAD_LOAD_FAILED,
                refusalCode(new PvmPayloadLaneStarter("pvm-payload-1", manifest(), "",
                        broken, 0L, 0L)));
    }

    @Test
    public void tripleMismatchRefusesBeforeAnyPlatformCall() {
        FakeApi api = new FakeApi();
        assertEquals(PvmPayloadLaneStarter.PAYLOAD_COMPILER_MISMATCH,
                refusalCode(starter(api, "aarch64-unknown-linux-gnu")));
        assertEquals("the platform must not be touched on a triple mismatch",
                0, api.startCount);
    }

    @Test
    public void emptyRequiredTripleSkipsTheComparison() throws Exception {
        FakeApi api = new FakeApi();
        starter(api, "").start();
        assertEquals(1, api.startCount);
    }

    @Test
    public void stopDelegatesToTheSessionAndConfirmsOnce() throws Exception {
        FakeApi api = new FakeApi();
        api.handle.ready = true;
        PvmPayloadLaneStarter starter = starter(api, "");
        LaneRuntime lane = starter.start();
        assertEquals(PvmPayloadLaneStarter.Readiness.READY, starter.awaitReady(lane, 0L));

        assertEquals(2000L, starter.stopGraceMs());
        assertEquals(LaneStopOutcome.CONFIRMED, lane.stop(starter.stopGraceMs()));
        assertEquals(1, api.handle.stopped);
        assertFalse(lane.isAlive());

        // The session owns the settled stop; a second request is not a second
        // platform stop.
        assertEquals(LaneStopOutcome.CONFIRMED, lane.stop(starter.stopGraceMs()));
        assertEquals(1, api.handle.stopped);
    }

    private static PvmPayloadLaneStarter starter(FakeApi api, String requiredTriple) {
        return new PvmPayloadLaneStarter(
                "pvm-payload-1", manifest(), requiredTriple, api, 0L, 2000L);
    }

    private static String refusalCode(PvmPayloadLaneStarter starter) {
        try {
            starter.start();
        } catch (LaneStartException expected) {
            return expected.code();
        }
        throw new AssertionError("expected a fixed-code refusal");
    }

    /** Built through the public parser so the fixture matches the on-disk schema. */
    private static PvmCompilerManifest manifest() {
        String sha256 = "a".repeat(64);
        String json = "{\"schema\":1,"
                + "\"compiler_version\":\"22.1.8\","
                + "\"target_triple\":\"" + PAYLOAD_TRIPLE + "\","
                + "\"vcpu_topology\":\"one_cpu\","
                + "\"memory_mib\":2048,"
                + "\"payload_binary\":\"libpvm_compiler_payload.so\","
                + "\"libs\":[{\"name\":\"libLLVM.so\",\"size\":64700000,"
                + "\"sha256\":\"" + sha256 + "\"}]}";
        return PvmCompilerManifest.parse(json).orElseThrow();
    }

    /** Fake platform boundary: no binder, no vsock, no device. */
    private static final class FakeApi implements FastLaneVirtualMachineApi {
        final FakeHandle handle = new FakeHandle();
        int startCount;
        FastLaneLaunchSpec captured;
        FastLaneException failure;

        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException {
            startCount++;
            captured = spec;
            if (failure != null) {
                throw failure;
            }
            return handle;
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
            throw new FastLaneException(FastLaneException.VSOCK_FAILED, "fake has no vsock");
        }
    }

    private static final class FakeHandle implements FastLaneHandle {
        int status = 1;
        boolean ready;
        int stopped;
        FastLaneStopResult stopResult = FastLaneStopResult.CONFIRMED;

        @Override
        public String instanceId() {
            return "pvm-payload-1";
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public FastLaneExitInfo waitForExit() {
            return FastLaneExitInfo.normal(0L);
        }

        @Override
        public boolean payloadReady() {
            return ready;
        }

        @Override
        public FastLaneStopResult stopRuntime(long graceMs) {
            stopped++;
            if (stopResult == FastLaneStopResult.CONFIRMED) {
                status = 0;
            }
            return stopResult;
        }

        @Override
        public void close() {
            status = 0;
        }

        @Override
        public List<String> notes() {
            return List.of();
        }
    }
}
