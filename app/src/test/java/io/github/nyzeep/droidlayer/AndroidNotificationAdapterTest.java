package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AndroidNotificationAdapterTest {
    @Test
    public void notifiesThroughPosterSeam() {
        AtomicInteger idRef = new AtomicInteger();
        AtomicReference<String> titleRef = new AtomicReference<>();
        AtomicReference<String> messageRef = new AtomicReference<>();

        AndroidNotificationAdapter adapter = new AndroidNotificationAdapter((id, title, message) -> {
            idRef.set(id);
            titleRef.set(title);
            messageRef.set(message);
            return true;
        });

        boolean result = adapter.notify("Build Finished", "All 10 tests passed successfully");

        assertTrue("notify should return true when poster succeeds", result);
        assertTrue("notification id should be positive", idRef.get() > 0);
        assertEquals("Build Finished", titleRef.get());
        assertEquals("All 10 tests passed successfully", messageRef.get());
    }

    @Test
    public void rejectsBlankOrNullNotificationParameters() {
        AtomicInteger invocations = new AtomicInteger();
        AndroidNotificationAdapter adapter = new AndroidNotificationAdapter((id, title, message) -> {
            invocations.incrementAndGet();
            return true;
        });

        assertFalse("null title must be rejected", adapter.notify(null, "message"));
        assertFalse("empty title must be rejected", adapter.notify("", "message"));
        assertFalse("whitespace title must be rejected", adapter.notify("   ", "message"));
        assertFalse("null message must be rejected", adapter.notify("title", null));
        assertFalse("empty message must be rejected", adapter.notify("title", ""));
        assertFalse("whitespace message must be rejected", adapter.notify("title", "   "));
        assertEquals(0, invocations.get());
    }

    @Test
    public void handlesPosterExceptionGracefully() {
        AndroidNotificationAdapter adapter = new AndroidNotificationAdapter((id, title, message) -> {
            throw new RuntimeException("notification service failed");
        });

        boolean result = adapter.notify("Title", "Message");
        assertFalse("exception in poster must return false", result);
    }
}
