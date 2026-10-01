package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ActivityNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AndroidUrlOpenerAdapterTest {
    @Test
    public void opensValidHttpAndHttpsUrlsThroughStarterSeam() {
        AtomicReference<String> launchedRef = new AtomicReference<>();
        AndroidUrlOpenerAdapter adapter = new AndroidUrlOpenerAdapter(launchedRef::set);

        boolean httpsResult = adapter.openUrl("https://droidlayer.github.io");
        assertTrue("https url should be accepted", httpsResult);
        assertEquals("https://droidlayer.github.io", launchedRef.get());

        boolean httpResult = adapter.openUrl("http://example.com/docs?id=123#sec");
        assertTrue("http url should be accepted", httpResult);
        assertEquals("http://example.com/docs?id=123#sec", launchedRef.get());
    }

    @Test
    public void rejectsDisallowedSchemesAndUnsafeUrls() {
        AtomicInteger invocations = new AtomicInteger();
        AndroidUrlOpenerAdapter adapter = new AndroidUrlOpenerAdapter(url -> {
            invocations.incrementAndGet();
        });

        String[] unsafe = new String[] {
            null,
            "",
            "file:///etc/passwd",
            "file:///data/data/io.github.nyzeep.droidlayer/databases/app.db",
            "javascript:alert(1)",
            "intent:#Intent;action=android.intent.action.VIEW;end",
            "content://media/external/images",
            "ftp://ftp.example.com",
            "data:text/html,test",
            "http://",
            "https://",
            "https://example.com/" + "a".repeat(2100)
        };

        for (String url : unsafe) {
            boolean result = adapter.openUrl(url);
            assertFalse("unsafe url must be rejected: " + url, result);
        }
        assertEquals(0, invocations.get());
    }

    @Test
    public void handlesActivityNotFoundExceptionGracefully() {
        AndroidUrlOpenerAdapter adapter = new AndroidUrlOpenerAdapter(url -> {
            throw new ActivityNotFoundException("no browser installed");
        });

        boolean result = adapter.openUrl("https://example.org");
        assertFalse("ActivityNotFoundException must return false", result);
    }

    @Test
    public void handlesSecurityExceptionGracefully() {
        AndroidUrlOpenerAdapter adapter = new AndroidUrlOpenerAdapter(url -> {
            throw new SecurityException("permission denied");
        });

        boolean result = adapter.openUrl("https://example.org");
        assertFalse("SecurityException must return false", result);
    }
}
