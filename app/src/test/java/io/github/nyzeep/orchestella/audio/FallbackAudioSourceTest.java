package io.github.nyzeep.orchestella.audio;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link FallbackAudioSource}.
 */
public class FallbackAudioSourceTest {

    private static class MockAudioSource implements AudioSourceSeam {
        boolean shouldFail = false;
        boolean connected = false;
        boolean closed = false;
        int openAttempts = 0;
        final byte[] data = new byte[]{1, 2, 3, 4};

        @Override
        public InputStream openStream() throws IOException {
            openAttempts++;
            if (shouldFail) {
                throw new IOException("Simulated source failure");
            }
            connected = true;
            return new ByteArrayInputStream(data);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }
    }

    @Test
    public void primarySourceSucceedsDirectly() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        MockAudioSource secondary = new MockAudioSource();
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 10);

        InputStream stream = fallback.openStream();
        assertNotNull(stream);
        assertSame(primary, fallback.getActiveSource());
        assertTrue(fallback.isConnected());
        assertEquals(1, primary.openAttempts);
        assertEquals(0, secondary.openAttempts);

        fallback.close();
        assertTrue(primary.closed);
        assertTrue(secondary.closed);
        assertFalse(fallback.isConnected());
    }

    @Test
    public void secondarySourceUsedWhenPrimaryFails() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        MockAudioSource secondary = new MockAudioSource();
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 10);

        InputStream stream = fallback.openStream();
        assertNotNull(stream);
        assertSame(secondary, fallback.getActiveSource());
        assertTrue(fallback.isConnected());
        assertEquals(1, primary.openAttempts);
        assertEquals(1, secondary.openAttempts);

        fallback.close();
        assertTrue(primary.closed);
        assertTrue(secondary.closed);
    }

    @Test
    public void throwsWhenBothSourcesFail() {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        MockAudioSource secondary = new MockAudioSource();
        secondary.shouldFail = true;
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 5);

        try {
            fallback.openStream();
            fail("Expected IOException when both fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Simulated source failure"));
        }
        assertEquals(2, primary.openAttempts);
        assertEquals(2, secondary.openAttempts);
        assertFalse(fallback.isConnected());
    }

    @Test
    public void cannotOpenStreamAfterClosed() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        MockAudioSource secondary = new MockAudioSource();
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 1, 0);

        fallback.close();
        try {
            fallback.openStream();
            fail("Expected IOException after closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already closed"));
        }
    }

    @Test
    public void throwsWhenSourceReturnsNull() {
        MockAudioSource primary = new MockAudioSource() {
            @Override
            public InputStream openStream() {
                openAttempts++;
                return null;
            }
        };
        MockAudioSource secondary = new MockAudioSource() {
            @Override
            public InputStream openStream() {
                openAttempts++;
                return null;
            }
        };
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 5);
        try {
            InputStream stream = fallback.openStream();
            fail("Expected IOException when sources return null stream, got: " + stream);
        } catch (IOException expected) {
            // expected
        }
    }
}
