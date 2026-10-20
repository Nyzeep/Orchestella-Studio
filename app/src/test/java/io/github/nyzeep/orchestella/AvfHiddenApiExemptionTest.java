package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/**
 * The hidden-API exemption is a process-wide, one-shot action: a second
 * attempt must not re-run the installer, and a failure is recorded instead of
 * being retried or silently reported as success.
 */
public final class AvfHiddenApiExemptionTest {
    @Before
    public void resetProcessState() {
        AvfHiddenApiExemption.resetForTest();
    }

    @Test
    public void installsExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        AvfHiddenApiExemption.State first =
                AvfHiddenApiExemption.installOnce(calls::incrementAndGet);
        AvfHiddenApiExemption.State second =
                AvfHiddenApiExemption.installOnce(calls::incrementAndGet);

        assertEquals(AvfHiddenApiExemption.State.INSTALLED, first);
        assertEquals(AvfHiddenApiExemption.State.INSTALLED, second);
        assertEquals(1, calls.get());
        assertEquals(AvfHiddenApiExemption.State.INSTALLED, AvfHiddenApiExemption.state());
    }

    @Test
    public void aFailedInstallIsRecordedAndNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        AvfHiddenApiExemption.State first = AvfHiddenApiExemption.installOnce(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("synthetic ART rejection");
        });
        AvfHiddenApiExemption.State second =
                AvfHiddenApiExemption.installOnce(calls::incrementAndGet);

        assertEquals(AvfHiddenApiExemption.State.FAILED, first);
        assertEquals(AvfHiddenApiExemption.State.FAILED, second);
        assertEquals(1, calls.get());
        assertTrue(AvfHiddenApiExemption.state() == AvfHiddenApiExemption.State.FAILED);
    }
}
