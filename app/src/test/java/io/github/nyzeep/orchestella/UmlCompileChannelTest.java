package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.UmlChannel.State;
import io.github.nyzeep.orchestella.UmlChannel.UmlChannelException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * Gate 1 (ACR 2026-11-13 方案 A, fabric-route doc D-1): the real D-1 channel's
 * contract — {@link UmlChannels#overTcpLane}, distcc over the fabric's UML-TCP
 * lane. The stub's lifecycle rules are locked by {@code UmlChannelContractTest};
 * these tests lock the real channel on top of them: the byte pipe pump (both
 * directions, over pipes and over a real loopback TCP echo), the fixed failure
 * mapping (dial and lane-side failures carry {@code uml-channel-execute-failed}
 * with the cause, state stays open — eviction is the orchestrator's call), and
 * the two shapes that must <b>not</b> fail the face: a job whose client went
 * away (the item side), and a peer that answered and hung up.
 */
public final class UmlCompileChannelTest {

    private final ExecutorService jobs = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        jobs.shutdownNow();
    }

    // --- lifecycle parity with the locked stub contract ------------------------------

    @Test
    public void lifecycleGuardsMatchTheLockedContract() throws UmlChannelException {
        UmlChannel channel = UmlChannels.overTcpLane(echoLaneFactory());
        assertSame(State.NEW, channel.state());
        try {
            channel.execute(1, pipeItem());
            fail("execute before open must refuse");
        } catch (UmlChannelException refusal) {
            assertEquals(UmlChannel.NOT_OPEN_DETAIL, refusal.detail());
        }
        channel.open();
        try {
            channel.open();
            fail("second open must refuse");
        } catch (UmlChannelException refusal) {
            assertEquals(UmlChannel.ALREADY_OPEN_DETAIL, refusal.detail());
        }
        channel.close();
        try {
            channel.open();
            fail("reopen must refuse");
        } catch (UmlChannelException refusal) {
            assertEquals(UmlChannel.CLOSED_DETAIL, refusal.detail());
        }
        channel.close();
        assertSame(State.CLOSED, channel.state());
        try {
            channel.execute(1, pipeItem());
            fail("execute after close must refuse");
        } catch (UmlChannelException refusal) {
            assertEquals(UmlChannel.CLOSED_DETAIL, refusal.detail());
        }
    }

    @Test
    public void workItemThatIsNotABytePipeFailsFast() throws UmlChannelException {
        UmlChannel channel = UmlChannels.overTcpLane(failingDialFactory(
                new IOException("never dialed")));
        channel.open();
        try {
            channel.execute(1, new FabricOrchestrator.WorkItem() {
            });
            fail("an opaque work item is a caller bug");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("FabricCompileItem"));
        }
        // The guard is a caller-bug refusal, not a channel failure.
        assertSame(State.OPEN, channel.state());
    }

    // --- the fixed failure mapping ---------------------------------------------------

    @Test
    public void dialFailureCarriesTheFixedDetailAndKeepsStateOpen() throws UmlChannelException {
        IOException refused = new IOException("guest distccd not there yet");
        UmlChannel channel = UmlChannels.overTcpLane(failingDialFactory(refused));
        channel.open();
        try {
            channel.execute(1, pipeItem());
            fail("a dead guest must fail the job");
        } catch (UmlChannelException failure) {
            assertEquals(UmlChannel.EXECUTE_FAILED_DETAIL, failure.detail());
            assertSame(refused, failure.getCause());
        }
        // The channel stays open — eviction is the orchestrator's call.
        assertSame(State.OPEN, channel.state());
    }

    @Test
    public void laneRefusalReasonRidesTheCauseChain() throws UmlChannelException {
        FastLaneException laneRefusal =
                new FastLaneException("LANE_REFUSED", "the lane refused the dial");
        UmlChannel channel = UmlChannels.overTcpLane(failingDialFactory(laneRefusal));
        channel.open();
        try {
            channel.execute(1, pipeItem());
            fail("a refused dial must fail the job");
        } catch (UmlChannelException failure) {
            assertEquals(UmlChannel.EXECUTE_FAILED_DETAIL, failure.detail());
            assertSame(laneRefusal, failure.getCause());
        }
        assertSame(State.OPEN, channel.state());
    }

    @Test
    public void laneFailureMidResponseThrowsAndKeepsStateOpen() throws Exception {
        UmlChannel channel = UmlChannels.overTcpLane(() -> new FastLaneChannel() {
            @Override public InputStream inputStream() {
                return new InputStream() {
                    private boolean served;

                    @Override public int read() throws IOException {
                        if (!served) {
                            served = true;
                            return 'X';
                        }
                        throw new IOException("guest died mid-response");
                    }
                };
            }

            @Override public OutputStream outputStream() {
                return nullOutput();
            }

            @Override public void close() {
            }
        });
        channel.open();
        PipedInputStream requestRead = new PipedInputStream(16 * 1024);
        PipedInputStream responseRead = new PipedInputStream(16 * 1024);
        FabricCompileItem item = new FabricCompileItem(
                requestRead, new PipedOutputStream(responseRead));
        Future<?> job = jobs.submit(() -> {
            try {
                channel.execute(1, item);
            } catch (UmlChannelException failure) {
                throw new AssertionError(failure);
            }
        });
        try {
            job.get(5, TimeUnit.SECONDS);
            fail("a guest dying mid-response must fail the job");
        } catch (java.util.concurrent.ExecutionException wrapped) {
            assertTrue(wrapped.getCause() instanceof AssertionError);
            UmlChannelException failure = (UmlChannelException) wrapped.getCause().getCause();
            assertEquals(UmlChannel.EXECUTE_FAILED_DETAIL, failure.detail());
            assertTrue(failure.getCause() instanceof IOException);
        }
        assertSame(State.OPEN, channel.state());
    }

    // --- the byte pipe pump ----------------------------------------------------------

    @Test(timeout = 10000)
    public void pumpsTheBytePipeBothDirectionsThroughTheLane() throws Exception {
        AtomicInteger laneOpens = new AtomicInteger();
        UmlChannel channel = UmlChannels.overTcpLane(new DistccTcpVsockBridge.ChannelFactory() {
            @Override public FastLaneChannel open() throws IOException {
                laneOpens.incrementAndGet();
                PipedInputStream guestInput = new PipedInputStream(16 * 1024);
                PipedOutputStream hostToGuest = new PipedOutputStream(guestInput);
                PipedInputStream hostInput = new PipedInputStream(16 * 1024);
                PipedOutputStream guestToHost = new PipedOutputStream(hostInput);
                Thread echo = new Thread(() -> {
                    try {
                        guestInput.transferTo(guestToHost);
                    } catch (IOException ignored) {
                        // The client's teardown ends the echo.
                    } finally {
                        closeQuietly(guestToHost);
                    }
                }, "echo-guest");
                echo.setDaemon(true);
                echo.start();
                return new FastLaneChannel() {
                    @Override public InputStream inputStream() {
                        return hostInput;
                    }

                    @Override public OutputStream outputStream() {
                        return hostToGuest;
                    }

                    @Override public void close() throws IOException {
                        IOException first = null;
                        for (AutoCloseable closeable : new AutoCloseable[] {
                                hostToGuest, guestInput, guestToHost, hostInput}) {
                            try {
                                closeable.close();
                            } catch (Exception failure) {
                                if (first == null && failure instanceof IOException io) {
                                    first = io;
                                }
                            }
                        }
                        if (first != null) {
                            throw first;
                        }
                    }
                };
            }
        });
        channel.open();
        PipedInputStream requestRead = new PipedInputStream(16 * 1024);
        PipedOutputStream requestWrite = new PipedOutputStream(requestRead);
        PipedInputStream responseRead = new PipedInputStream(16 * 1024);
        PipedOutputStream responseWrite = new PipedOutputStream(responseRead);
        FabricCompileItem item = new FabricCompileItem(requestRead, responseWrite);

        Future<?> job = jobs.submit(() -> {
            try {
                channel.execute(1, item);
            } catch (UmlChannelException failure) {
                throw new AssertionError(failure);
            }
        });
        // The client side: request bytes, half-close, then the echoed answer.
        requestWrite.write("compile-me".getBytes(StandardCharsets.UTF_8));
        requestWrite.flush();
        requestWrite.close();
        byte[] echoed = responseRead.readNBytes("compile-me".length());
        assertEquals("compile-me", new String(echoed, StandardCharsets.UTF_8));
        assertEquals("the answer's EOF closes with the job", -1, responseRead.read());
        job.get(5, TimeUnit.SECONDS);
        assertEquals("one lane connection per admitted job", 1, laneOpens.get());
        channel.close();
    }

    @Test(timeout = 10000)
    public void roundTripOverARealTcpLoopbackLane() throws Exception {
        try (EchoServer server = new EchoServer()) {
            UmlChannel channel = UmlChannels.overTcpLane(
                    io.github.nyzeep.orchestella.runtime.fastlane.distcc.UmlTcpChannelFactory.loopback(
                            InetAddress.getLoopbackAddress(), server.port()));
            channel.open();
            PipedInputStream requestRead = new PipedInputStream(16 * 1024);
            PipedOutputStream requestWrite = new PipedOutputStream(requestRead);
            PipedInputStream responseRead = new PipedInputStream(16 * 1024);
            PipedOutputStream responseWrite = new PipedOutputStream(responseRead);
            FabricCompileItem item = new FabricCompileItem(requestRead, responseWrite);

            Future<?> job = jobs.submit(() -> {
                try {
                    channel.execute(2, item);
                } catch (UmlChannelException failure) {
                    throw new AssertionError(failure);
                }
            });
            requestWrite.write("uml-tcp-ping".getBytes(StandardCharsets.UTF_8));
            requestWrite.flush();
            requestWrite.close();
            byte[] echoed = responseRead.readNBytes("uml-tcp-ping".length());
            assertEquals("uml-tcp-ping", new String(echoed, StandardCharsets.UTF_8));
            job.get(5, TimeUnit.SECONDS);
            channel.close();
        }
    }

    // --- the two shapes that must NOT fail the face -----------------------------------

    @Test(timeout = 10000)
    public void itemSideGoneEndsTheJobQuietlySoTheFaceStaysRoutable() throws Exception {
        UmlChannel channel = UmlChannels.overTcpLane(echoLaneFactory());
        channel.open();
        PipedInputStream requestRead = new PipedInputStream(16 * 1024);
        PipedOutputStream requestWrite = new PipedOutputStream(requestRead);
        PipedInputStream responseRead = new PipedInputStream(16 * 1024);
        FabricCompileItem item = new FabricCompileItem(
                requestRead, new PipedOutputStream(responseRead));
        Future<?> job = jobs.submit(() -> {
            try {
                channel.execute(1, item);
            } catch (UmlChannelException failure) {
                throw new AssertionError("a dead client must not fail the face", failure);
            }
        });
        requestWrite.write("partial".getBytes(StandardCharsets.UTF_8));
        requestWrite.flush();
        // The job's client vanished: the submitter's pipe is torn down — the
        // channel's request read fails on the ITEM side, which is cancellation,
        // never a channel failure.
        requestRead.close();
        job.get(5, TimeUnit.SECONDS);
        assertSame(State.OPEN, channel.state());
        channel.close();
    }

    @Test(timeout = 10000)
    public void laneHangUpAfterTheFullResponseReturnsNormally() throws Exception {
        // The bridge's own normal shape: the payload answers, closes the
        // transport, and the client may still be flushing. A lane-side write
        // failure after the response fully arrived is that hang-up, not a
        // failure — the face must stay routable.
        UmlChannel channel = UmlChannels.overTcpLane(() -> new FastLaneChannel() {
            @Override public InputStream inputStream() {
                return new ByteArrayInputStream("done".getBytes(StandardCharsets.UTF_8));
            }

            @Override public OutputStream outputStream() {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        throw new IOException("lane closed by peer");
                    }

                    @Override public void write(byte[] bytes, int offset, int length)
                            throws IOException {
                        throw new IOException("lane closed by peer");
                    }

                    @Override public void flush() {
                    }

                    @Override public void close() {
                    }
                };
            }

            @Override public void close() {
            }
        });
        channel.open();
        PipedInputStream requestRead = new PipedInputStream(16 * 1024);
        PipedOutputStream requestWrite = new PipedOutputStream(requestRead);
        PipedInputStream responseRead = new PipedInputStream(16 * 1024);
        PipedOutputStream responseWrite = new PipedOutputStream(responseRead);
        FabricCompileItem item = new FabricCompileItem(requestRead, responseWrite);
        Future<?> job = jobs.submit(() -> {
            try {
                channel.execute(1, item);
            } catch (UmlChannelException failure) {
                throw new AssertionError("the hang-up must not fail the face", failure);
            }
        });
        // The answer is already on the pipe; read it BEFORE sending anything so
        // responseComplete is settled deterministically.
        byte[] answer = responseRead.readNBytes("done".length());
        assertEquals("done", new String(answer, StandardCharsets.UTF_8));
        // Now the request direction's write hits the closed lane — swallowed.
        requestWrite.write("late bytes".getBytes(StandardCharsets.UTF_8));
        requestWrite.flush();
        requestWrite.close();
        job.get(5, TimeUnit.SECONDS);
        assertSame(State.OPEN, channel.state());
        channel.close();
    }

    // --- fixtures ---------------------------------------------------------------------

    private static FabricCompileItem pipeItem() {
        try {
            return new FabricCompileItem(
                    new PipedInputStream(1024), new PipedOutputStream(new PipedInputStream(1024)));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static DistccTcpVsockBridge.ChannelFactory failingDialFactory(Exception failure) {
        return () -> {
            if (failure instanceof FastLaneException laneRefusal) {
                throw laneRefusal;
            }
            throw (IOException) failure;
        };
    }

    private static OutputStream nullOutput() {
        return new OutputStream() {
            @Override public void write(int value) {
            }

            @Override public void flush() {
            }

            @Override public void close() {
            }
        };
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Fixture teardown.
        }
    }

    /** A pipe-backed lane whose "guest" echoes every byte until EOF. */
    private static DistccTcpVsockBridge.ChannelFactory echoLaneFactory() {
        return () -> {
            PipedInputStream guestInput = new PipedInputStream(16 * 1024);
            PipedOutputStream hostToGuest = new PipedOutputStream(guestInput);
            PipedInputStream hostInput = new PipedInputStream(16 * 1024);
            PipedOutputStream guestToHost = new PipedOutputStream(hostInput);
            Thread echo = new Thread(() -> {
                try {
                    guestInput.transferTo(guestToHost);
                } catch (IOException ignored) {
                    // The client's teardown ends the echo.
                } finally {
                    closeQuietly(guestToHost);
                }
            }, "echo-guest");
            echo.setDaemon(true);
            echo.start();
            return new FastLaneChannel() {
                @Override public InputStream inputStream() {
                    return hostInput;
                }

                @Override public OutputStream outputStream() {
                    return hostToGuest;
                }

                @Override public void close() throws IOException {
                    IOException first = null;
                    for (AutoCloseable closeable : new AutoCloseable[] {
                            hostToGuest, guestInput, guestToHost, hostInput}) {
                        try {
                            closeable.close();
                        } catch (Exception failure) {
                            if (first == null && failure instanceof IOException io) {
                                first = io;
                            }
                        }
                    }
                    if (first != null) {
                        throw first;
                    }
                }
            };
        };
    }

    /** A loopback echo "guest" over real TCP — the production lane shape. */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final AtomicBoolean closed = new AtomicBoolean();

        EchoServer() throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(this::acceptLoop, "uml-tcp-echo-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed.get()) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException failure) {
                    return;
                }
                pool.execute(() -> {
                    try (Socket ignored = socket) {
                        socket.getInputStream().transferTo(socket.getOutputStream());
                    } catch (IOException ignored) {
                        // The client's teardown ends the echo.
                    }
                });
            }
        }

        @Override public void close() {
            closed.set(true);
            try {
                listener.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
            pool.shutdownNow();
        }
    }
}
