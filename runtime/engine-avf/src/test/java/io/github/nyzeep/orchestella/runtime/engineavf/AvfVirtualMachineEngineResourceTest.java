package io.github.nyzeep.orchestella.runtime.engineavf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Session FD ownership and failure classification at the engine API boundary. */
public final class AvfVirtualMachineEngineResourceTest {
    public static void main(String[] args) throws Exception {
        closesAllSessionDescriptorsEvenWhenOneCloseFails();
        doesNotRetryPermissionOrUnavailableApi();
        stopDoesNotWaitForBlockedConnect();
        concurrentLazyCallsShareOneConnectAndCloseWakesWaiter();
        concurrentLazyCallsPublishTheSameOwner();
        closeWakesRetryBackoffWithoutAnotherConnect();
        platformStopPrecedesBlockingPublishedChannelClose();
        System.out.println("AVF_ENGINE_RESOURCE_TEST_OK all_fd_cleanup=1 terminal_failure_retry=3 blocked_connect_stop=5"
                + " single_flight=2 waiter_cancel=1 retry_cancel=1 stop_before_close=1");
    }

    private static AvfLaunchSpec spec() {
        return AvfLaunchSpec.builder("resource-test", "/kernel", "/initrd", "/root").build();
    }

    private static void closesAllSessionDescriptorsEvenWhenOneCloseFails() throws Exception {
        List<Channel> channels = new ArrayList<>();
        AvfVirtualMachineApi api = new FixtureApi() {
            @Override public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port) {
                var channel = new Channel(channels.isEmpty());
                channels.add(channel);
                return channel;
            }
        };
        var session = new AvfVirtualMachineEngine(api, 1, 0).launch(spec());
        session.controlChannel();
        session.terminalChannel();
        session.graphicsChannel();
        session.openChannel(9200);
        try { session.closeChannels(); throw new AssertionError("close failure expected"); }
        catch (IOException expected) { }
        for (Channel channel : channels) check(channel.closeCount == 1, "all owned channels must close once");
        session.closeChannels();
        for (Channel channel : channels) check(channel.closeCount == 1, "repeat close must not retry closed descriptors");
        try { session.openChannel(9200); throw new AssertionError("closed session should reject"); }
        catch (AvfEngineException expected) { }
    }

    private static void doesNotRetryPermissionOrUnavailableApi() throws Exception {
        for (String reason : List.of(AvfEngineException.PERMISSION_DENIED,
                AvfEngineException.REFLECTION_UNAVAILABLE, AvfEngineException.HARDWARE_UNSUPPORTED)) {
            int[] attempts = {0};
            AvfVirtualMachineApi api = new FixtureApi() {
                @Override public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port) {
                    attempts[0]++;
                    throw new AvfEngineException(reason, "permanent refusal");
                }
            };
            var session = new AvfVirtualMachineEngine(api, 5, 0).launch(spec());
            try { session.controlChannel(); throw new AssertionError("refusal expected"); }
            catch (AvfEngineException expected) { check(reason.equals(expected.reason()), "reason preserved"); }
            check(attempts[0] == 1, "permission/API refusal is not a boot retry");
            session.closeChannels();
        }
    }

    private static void stopDoesNotWaitForBlockedConnect() throws Exception {
        List<ChannelOpener> paths = List.of(
                AvfVirtualMachineEngine.AvfVmSession::controlChannel,
                AvfVirtualMachineEngine.AvfVmSession::terminalChannel,
                AvfVirtualMachineEngine.AvfVmSession::graphicsChannel,
                AvfVirtualMachineEngine.AvfVmSession::audioVsockChannel,
                session -> session.openChannel(9200));
        for (int index = 0; index < paths.size(); index++) {
            BlockingApi api = new BlockingApi();
            var session = new AvfVirtualMachineEngine(api, 100, 200).launch(spec());
            ChannelOpener path = paths.get(index);
            FutureTask<AvfVirtualMachineApi.AvfVsockChannel> connection = task(() -> path.open(session));
            check(api.entered.await(1, TimeUnit.SECONDS), "connect must actually block inside API");
            long started = System.nanoTime();
            FutureTask<Void> stopping = task(() -> { session.stop(); return null; });
            try {
                stopping.get(1, TimeUnit.SECONDS);
                long elapsed = System.nanoTime() - started;
                check(elapsed <= TimeUnit.SECONDS.toNanos(1), "STOP must finish within one second");
                check(api.stops.get() == 1, "STOP must reach handle.stop while connect is blocked");
                check(!connection.isDone(), "test must not unblock connect to let STOP pass");
                System.out.println("AVF_BLOCKED_CONNECT_STOP path=" + index + " elapsed_ns=" + elapsed);
            } catch (TimeoutException failure) {
                throw new AssertionError("STOP is blocked by session connection/lazy-access lock", failure);
            } finally {
                api.release.countDown();
                stopping.get(2, TimeUnit.SECONDS);
            }
            expectClosed(connection);
            check(api.raw.closeCount == 1, "late connection must be closed exactly once");
            session.closeChannels();
            check(api.raw.closeCount == 1, "terminal close must not reacquire late owner");
        }
    }

    private static void concurrentLazyCallsShareOneConnectAndCloseWakesWaiter() throws Exception {
        BlockingApi api = new BlockingApi();
        var session = new AvfVirtualMachineEngine(api, 1, 0).launch(spec());
        var owner = task(session::controlChannel);
        check(api.entered.await(1, TimeUnit.SECONDS), "owner must be inside connect");
        var waiter = new FutureTask<>(session::controlChannel);
        Thread waiterThread = new Thread(waiter, "avf-single-flight-waiter");
        waiterThread.setDaemon(true);
        waiterThread.start();
        awaitWaiting(waiterThread);
        try {
            session.closeChannels();
            expectClosed(waiter);
            check(api.connects.get() == 1, "same port must not open multiple connections");
            check(!owner.isDone(), "owner API is still blocked after waiter cancellation");
        } finally { api.release.countDown(); }
        expectClosed(owner);
        check(api.raw.closeCount == 1, "late single-flight raw closed once");
    }

    private static void concurrentLazyCallsPublishTheSameOwner() throws Exception {
        BlockingApi api = new BlockingApi();
        var session = new AvfVirtualMachineEngine(api, 1, 0).launch(spec());
        var owner = task(session::graphicsChannel);
        check(api.entered.await(1, TimeUnit.SECONDS), "owner must block before second getter");
        var waiter = new FutureTask<>(session::graphicsChannel);
        Thread waiterThread = new Thread(waiter, "avf-single-flight-success-waiter");
        waiterThread.setDaemon(true);
        waiterThread.start();
        try {
            awaitWaiting(waiterThread);
            check(api.connects.get() == 1, "pending port has one API connection");
        } finally { api.release.countDown(); }
        var first = owner.get(1, TimeUnit.SECONDS);
        var second = waiter.get(1, TimeUnit.SECONDS);
        check(first == second, "simultaneous lazy callers receive one published owner");
        first.close();
        session.closeChannels();
        check(api.raw.closeCount == 1, "caller/session close share exactly one raw owner");
    }

    private static void awaitWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            check(thread.isAlive(), "waiter must not return before owner connection");
            Thread.sleep(1);
        }
        check(thread.getState() == Thread.State.WAITING, "waiter must actually wait with connect blocked");
    }

    private static void closeWakesRetryBackoffWithoutAnotherConnect() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var api = new FixtureApi() {
            @Override public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port) {
                attempts.incrementAndGet();
                attempted.countDown();
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED, "not yet listening");
            }
        };
        var session = new AvfVirtualMachineEngine(api, 100, 20000).launch(spec());
        var connecting = task(session::graphicsChannel);
        check(attempted.await(1, TimeUnit.SECONDS), "real retry attempt before close");
        long started = System.nanoTime();
        session.closeChannels();
        expectClosed(connecting);
        check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1), "20s backoff wakes on close");
        check(attempts.get() == 1, "closed session cannot retry");
    }

    private static void platformStopPrecedesBlockingPublishedChannelClose() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch teardownRelease = new CountDownLatch(1);
        BlockingApi api = new BlockingApi() {
            @Override public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port) {
                return new AvfVsockChannel() {
                    public java.io.InputStream inputStream() { return new ByteArrayInputStream(new byte[0]); }
                    public java.io.OutputStream outputStream() { return new ByteArrayOutputStream(); }
                    public void close() throws IOException {
                        closing.countDown();
                        try { teardownRelease.await(); }
                        catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IOException(failure);
                        }
                    }
                };
            }
        };
        var session = new AvfVirtualMachineEngine(api, 1, 0).launch(spec());
        session.controlChannel();
        var stopping = task(() -> { session.stop(); return null; });
        try {
            check(closing.await(1, TimeUnit.SECONDS), "channel close is genuinely blocked");
            check(api.stops.get() == 1, "handle.stop must already run before channel teardown");
            expectClosed(task(session::controlChannel));
        } finally { teardownRelease.countDown(); }
        stopping.get(1, TimeUnit.SECONDS);
    }

    @FunctionalInterface private interface ChannelOpener {
        AvfVirtualMachineApi.AvfVsockChannel open(AvfVirtualMachineEngine.AvfVmSession session);
    }
    private static <T> FutureTask<T> task(java.util.concurrent.Callable<T> action) {
        FutureTask<T> future = new FutureTask<>(action);
        Thread thread = new Thread(future, "avf-resource-concurrency-test");
        thread.setDaemon(true);
        thread.start();
        return future;
    }
    private static void expectClosed(FutureTask<?> future) throws Exception {
        try { future.get(1, TimeUnit.SECONDS); throw new AssertionError("closed connection must be rejected"); }
        catch (ExecutionException expected) {
            check(expected.getCause() instanceof AvfEngineException, "closed result must be an engine failure");
            AvfEngineException failure = (AvfEngineException) expected.getCause();
            check(AvfEngineException.VSOCK_CONNECT_FAILED.equals(failure.reason())
                    && failure.getMessage().contains("closed"), "terminal closed state must be explicit");
        }
    }
    private static class BlockingApi extends FixtureApi {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final Channel raw = new Channel(false);
        @Override public AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec) {
            return new AvfVirtualMachineHandle() {
                public String instanceId() { return spec.instanceId(); }
                public List<String> reflectionNotes() { return List.of(); }
                public int waitFor() { return 0; }
                public void stop() { stops.incrementAndGet(); }
            };
        }
        @Override public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port) {
            connects.incrementAndGet();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED, "fixture interrupted", failure);
            }
            return raw;
        }
    }

    private abstract static class FixtureApi implements AvfVirtualMachineApi {
        public boolean isReflectionAvailable() { return true; }
        public AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec) {
            return new AvfVirtualMachineHandle() {
                public String instanceId() { return spec.instanceId(); }
                public List<String> reflectionNotes() { return List.of(); }
                public int waitFor() { return 0; }
                public void stop() { }
            };
        }
    }
    private static final class Channel implements AvfVirtualMachineApi.AvfVsockChannel {
        final boolean fail;
        int closeCount;
        final ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel(boolean fail) { this.fail = fail; }
        public ByteArrayInputStream inputStream() { return in; }
        public ByteArrayOutputStream outputStream() { return out; }
        public void close() throws IOException {
            closeCount++;
            if (fail) throw new IOException("synthetic close refusal");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
