package io.github.nyzeep.orchestella.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * G-05 attach path of {@link LocalSocketAudioSource}: an established
 * {@link StreamConnection} feeds the source without the client-connect path.
 * The legacy constructor/openStream semantics stay byte-for-byte unchanged for
 * sources that never attach (guarded by the same synchronized contract).
 */
public class LocalSocketAudioSourceAttachTest {

    private static final class FakeConnection implements StreamConnection {
        final AtomicInteger closes = new AtomicInteger();
        private final InputStream input;
        private final OutputStream output = new ByteArrayOutputStream(0);
        boolean closed;

        FakeConnection(byte[] payload) {
            this.input = new ByteArrayInputStream(payload);
        }

        @Override public InputStream inputStream() { return input; }

        @Override public OutputStream outputStream() { return output; }

        @Override public void close() {
            closes.incrementAndGet();
            closed = true;
        }
    }

    @Test
    public void attachedConnectionFeedsOpenStreamWithoutClientConnect() throws Exception {
        byte[] payload = {0x10, 0x20, 0x30, 0x40};
        FakeConnection connection = new FakeConnection(payload);
        LocalSocketAudioSource source = new LocalSocketAudioSource();

        source.attach(connection);
        assertTrue("an attached connection means the source is fed", source.isConnected());

        InputStream stream = source.openStream();
        assertNotNull(stream);
        byte[] read = new byte[payload.length];
        int count = stream.read(read);
        assertEquals(payload.length, count);
        for (int i = 0; i < payload.length; i++) {
            assertEquals("attached stream must carry the established connection bytes",
                    payload[i], read[i]);
        }
        // Repeated openStream returns the same attached stream (stable identity).
        assertSame(stream, source.openStream());

        source.close();
        assertEquals("close must tear down the attached transport exactly once", 1, connection.closes.get());
        assertFalse(source.isConnected());
        assertEquals(LocalSocketAudioSource.DEFAULT_SOCKET_NAME, source.getSocketName());
    }

    @Test
    public void attachingASecondConnectionReplacesAndClosesTheFirst() throws Exception {
        FakeConnection first = new FakeConnection(new byte[]{1});
        FakeConnection second = new FakeConnection(new byte[]{2, 3});
        LocalSocketAudioSource source = new LocalSocketAudioSource();

        source.attach(first);
        source.attach(second);
        assertEquals("the replaced link must be closed on re-attach", 1, first.closes.get());

        InputStream stream = source.openStream();
        assertEquals("the newest attach wins", 2, stream.read());
        assertEquals(3, stream.read());

        source.close();
        assertEquals(1, first.closes.get());
        assertEquals("source close must close the active attached transport", 1, second.closes.get());
    }

    @Test
    public void attachAfterCloseAndNullAttachAreRejected() throws Exception {
        FakeConnection connection = new FakeConnection(new byte[]{1});
        LocalSocketAudioSource source = new LocalSocketAudioSource();
        source.close();

        try {
            source.attach(connection);
            fail("attach after close must fail like openStream after close");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already closed"));
        }
        try {
            new LocalSocketAudioSource().attach(null);
            fail("null attach is a programming error");
        } catch (NullPointerException expected) {
            // required
        }
        assertEquals("a rejected attach must not close the offered connection", 0, connection.closes.get());
    }

    @Test
    public void attachIsIdempotentForTheSameConnection() throws Exception {
        FakeConnection connection = new FakeConnection(new byte[]{9});
        LocalSocketAudioSource source = new LocalSocketAudioSource();
        source.attach(connection);
        source.attach(connection);
        source.attach(connection);
        assertEquals("re-attaching the same connection must not close it", 0, connection.closes.get());
        source.close();
        assertEquals(1, connection.closes.get());
    }
}
