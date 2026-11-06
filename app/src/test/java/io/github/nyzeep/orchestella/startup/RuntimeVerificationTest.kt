package io.github.nyzeep.orchestella.startup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 锁定 QEMU 运行时校验的记忆化语义（冷启动主线程卫生）：
 * 计算只发生一次、结果进程级复用、回调必须送达。
 */
public final class RuntimeVerificationTest {

    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        RuntimeVerification.resetForTest()
        executor = Executors.newSingleThreadExecutor()
    }

    @After
    fun tearDown() {
        RuntimeVerification.resetForTest()
        executor.shutdownNow()
    }

    @Test
    fun computeRunsOnceAndResultIsMemoized() {
        val computations = AtomicInteger(0)
        fun fakeCompute(): RuntimeVerification.Result {
            computations.incrementAndGet()
            return RuntimeVerification.Result.Verified
        }

        val firstDone = CountDownLatch(1)
        var firstResult: RuntimeVerification.Result? = null
        RuntimeVerification.verifyAsync(executor = executor, callback = {
            firstResult = it
            firstDone.countDown()
        }, compute = ::fakeCompute)
        // 首次计算完成后再发起后续请求，锁定“命中缓存不再计算”的语义。
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))

        var secondResult: RuntimeVerification.Result? = null
        RuntimeVerification.verifyAsync(executor = executor, callback = { secondResult = it }, compute = {
            computations.incrementAndGet()
            RuntimeVerification.Result.Unavailable("must-not-run")
        })
        var thirdResult: RuntimeVerification.Result? = null
        RuntimeVerification.verifyAsync(executor = executor, callback = { thirdResult = it }, compute = {
            computations.incrementAndGet()
            RuntimeVerification.Result.Unavailable("must-not-run")
        })

        assertSame(RuntimeVerification.Result.Verified, firstResult)
        assertSame(RuntimeVerification.Result.Verified, secondResult)
        assertSame(RuntimeVerification.Result.Verified, thirdResult)
        assertEquals("compute must run exactly once", 1, computations.get())
        assertSame(RuntimeVerification.Result.Verified, RuntimeVerification.cached())
    }

    @Test
    fun cacheHitDoesNotTouchExecutor() {
        val done = CountDownLatch(1)
        RuntimeVerification.verifyAsync(
            executor = executor,
            callback = { done.countDown() },
            compute = { RuntimeVerification.Result.Unavailable("io-broken") },
        )
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val executorTouches = AtomicInteger(0)
        val unusedExecutor = Executor { executorTouches.incrementAndGet() }
        var result: RuntimeVerification.Result? = null
        RuntimeVerification.verifyAsync(
            executor = unusedExecutor,
            callback = { result = it },
            compute = { RuntimeVerification.Result.Verified },
        )

        assertNotNull(result)
        assertTrue(result is RuntimeVerification.Result.Unavailable)
        assertEquals("cached hit must not touch the executor", 0, executorTouches.get())
    }

    @Test
    fun unavailableCarriesStableReason() {
        val latch = CountDownLatch(1)
        var result: RuntimeVerification.Result? = null
        RuntimeVerification.verifyAsync(
            executor = executor,
            callback = {
                result = it
                latch.countDown()
            },
            compute = { RuntimeVerification.Result.Unavailable("runtime-artifact-checksum-mismatch") },
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "runtime-artifact-checksum-mismatch",
            (result as RuntimeVerification.Result.Unavailable).reason,
        )
    }
}
