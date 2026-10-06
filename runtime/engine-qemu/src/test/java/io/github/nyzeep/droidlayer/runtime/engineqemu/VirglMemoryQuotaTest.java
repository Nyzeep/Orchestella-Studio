package io.github.nyzeep.droidlayer.runtime.engineqemu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM unit tests for VirGL memory quota sentinel and JNI bridge (M4 Ticket 57 / ADR-0019).
 *
 * <p>Verifies:
 * <ul>
 *   <li>1024 MB default allocation quota boundary enforcement;</li>
 *   <li>Circuit breaker tripping on memory overallocation;</li>
 *   <li>Quota release and recovery behavior;</li>
 *   <li>Lock-free thread safety under concurrent allocation pressure;</li>
 *   <li>VirglJniBridge lifecycle and shader crash resilience.</li>
 * </ul>
 */
public final class VirglMemoryQuotaTest {

    private static final long MB = 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        testDefaultQuotaIs1024Mb();
        testAllocationWithinQuotaSucceeds();
        testAllocationExceedingQuotaFailsAndTrips();
        testReleaseRecoversQuotaAndClearsTrip();
        testNegativeOrZeroHandling();
        testConcurrentAllocationsThreadSafety();
        testVirglJniBridgeLifecycleAndShaderResilience();
        System.out.println("ALL_VIRGL_MEMORY_QUOTA_TESTS_PASSED");
    }

    private static void testDefaultQuotaIs1024Mb() {
        MemoryQuotaSentinel sentinel = new MemoryQuotaSentinel();
        assertEquals(1024L * MB, sentinel.maxQuotaBytes(), "default quota must be 1024 MB");
        assertEquals(0L, sentinel.allocatedBytes(), "initial allocated must be 0");
        assertEquals(1024L * MB, sentinel.availableBytes(), "initial available must be 1024 MB");
        assertFalse(sentinel.isTripped(), "initial state must not be tripped");
        System.out.println("TEST_DEFAULT_QUOTA_OK");
    }

    private static void testAllocationWithinQuotaSucceeds() {
        MemoryQuotaSentinel sentinel = new MemoryQuotaSentinel(1024L * MB);

        assertTrue(sentinel.tryAllocate(256L * MB), "allocating 256MB should succeed");
        assertEquals(256L * MB, sentinel.allocatedBytes(), "allocated bytes mismatch");
        assertEquals(768L * MB, sentinel.availableBytes(), "available bytes mismatch");
        assertFalse(sentinel.isTripped(), "must not trip under quota");

        assertTrue(sentinel.tryAllocate(512L * MB), "allocating additional 512MB should succeed");
        assertEquals(768L * MB, sentinel.allocatedBytes(), "allocated bytes mismatch");
        assertEquals(256L * MB, sentinel.availableBytes(), "available bytes mismatch");
        assertFalse(sentinel.isTripped(), "must not trip under quota");

        System.out.println("TEST_ALLOCATION_WITHIN_QUOTA_OK");
    }

    private static void testAllocationExceedingQuotaFailsAndTrips() {
        MemoryQuotaSentinel sentinel = new MemoryQuotaSentinel(1024L * MB);

        assertTrue(sentinel.tryAllocate(800L * MB), "allocating 800MB should succeed");

        // Attempting to allocate 300MB more (800 + 300 = 1100 > 1024)
        assertFalse(sentinel.tryAllocate(300L * MB), "allocating 300MB more should be rejected");
        assertTrue(sentinel.isTripped(), "sentinel must trip when over quota");
        assertEquals(800L * MB, sentinel.allocatedBytes(), "allocated bytes must remain at 800MB after rejection");
        assertEquals(224L * MB, sentinel.availableBytes(), "available bytes must remain 224MB");

        System.out.println("TEST_ALLOCATION_EXCEEDING_QUOTA_OK");
    }

    private static void testReleaseRecoversQuotaAndClearsTrip() {
        MemoryQuotaSentinel sentinel = new MemoryQuotaSentinel(1024L * MB);

        sentinel.tryAllocate(800L * MB);
        sentinel.tryAllocate(300L * MB); // trips
        assertTrue(sentinel.isTripped(), "must be tripped");

        // Release 400MB
        sentinel.release(400L * MB);
        assertEquals(400L * MB, sentinel.allocatedBytes(), "allocated must drop to 400MB");
        assertEquals(624L * MB, sentinel.availableBytes(), "available must increase to 624MB");
        assertFalse(sentinel.isTripped(), "tripped state should clear after release brings below limit");

        // Now allocating 300MB must succeed (400 + 300 = 700 <= 1024)
        assertTrue(sentinel.tryAllocate(300L * MB), "allocating 300MB must now succeed");
        assertEquals(700L * MB, sentinel.allocatedBytes(), "allocated must be 700MB");

        System.out.println("TEST_RELEASE_RECOVERS_QUOTA_OK");
    }

    private static void testNegativeOrZeroHandling() {
        MemoryQuotaSentinel sentinel = new MemoryQuotaSentinel(1024L * MB);

        assertFalse(sentinel.tryAllocate(0), "zero allocation should return false");
        assertFalse(sentinel.tryAllocate(-100), "negative allocation should return false");
        assertEquals(0L, sentinel.allocatedBytes(), "allocated bytes should remain 0");

        sentinel.release(0);
        sentinel.release(-50);
        assertEquals(0L, sentinel.allocatedBytes(), "allocated bytes should remain 0");

        System.out.println("TEST_NEGATIVE_OR_ZERO_OK");
    }

    private static void testConcurrentAllocationsThreadSafety() throws Exception {
        final long quota = 500L * MB;
        final MemoryQuotaSentinel sentinel = new MemoryQuotaSentinel(quota);
        final int threadCount = 10;
        final long chunkSize = 100L * MB;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        final AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                boolean ok = sentinel.tryAllocate(chunkSize);
                if (ok) {
                    successCount.incrementAndGet();
                }
                return ok;
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        for (Future<Boolean> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertEquals(5, successCount.get(), "exactly 5 chunks of 100MB must succeed for 500MB quota");
        assertEquals(500L * MB, sentinel.allocatedBytes(), "total allocated must equal exactly 500MB");
        assertEquals(0L, sentinel.availableBytes(), "available must be 0MB");

        System.out.println("TEST_CONCURRENT_THREAD_SAFETY_OK");
    }

    private static void testVirglJniBridgeLifecycleAndShaderResilience() {
        assertTrue(VirglJniBridge.init(1024, true), "VirglJniBridge.init must succeed");
        assertTrue(VirglJniBridge.isInitialized(), "bridge must report initialized");
        assertEquals(1024, VirglJniBridge.getMemoryQuotaMb(), "quota must be 1024 MB");
        assertTrue(VirglJniBridge.isRobustnessEnabled(), "robustness must be enabled");

        // Test test allocation through bridge
        assertTrue(VirglJniBridge.tryAllocateTestMemory(512L * MB), "allocating 512MB via bridge should succeed");
        assertEquals(512L * MB, VirglJniBridge.getAllocatedMemoryBytes(), "bridge allocated bytes mismatch");

        // Shader crash resilience simulation
        assertTrue(VirglJniBridge.simulateShaderCrash(), "shader crash simulation must be handled without process exit");
        assertTrue(VirglJniBridge.isInitialized(), "bridge must remain initialized after recovery");

        VirglJniBridge.releaseTestMemory(512L * MB);
        assertEquals(0L, VirglJniBridge.getAllocatedMemoryBytes(), "released memory must drop to 0");

        VirglJniBridge.cleanup();
        assertFalse(VirglJniBridge.isInitialized(), "bridge must report uninitialized after cleanup");

        System.out.println("TEST_VIRGL_JNI_BRIDGE_LIFECYCLE_OK");
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            fail(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail("Expected true but got false: " + message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            fail("Expected false but got true: " + message);
        }
    }

    private static void fail(String reason) {
        System.err.println("VIRGL_MEMORY_QUOTA_TEST_FAILED: " + reason);
        System.exit(1);
    }
}
