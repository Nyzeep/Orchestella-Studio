package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Ticket 07: the 9p server has exactly one owner per session. A second start
 * returns the same server, a stop/close ends it, and a retry after the session
 * closed never starts a second server on a fresh vsock connection.
 */
public final class Avf9pShareLifecycleTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        startsOnceAndStopsWithTheSession();
        System.out.println("AVF_9P_SHARE_LIFECYCLE_TEST_OK single_owner=1 stop=1 no_second_server=1");
    }

    private static void startsOnceAndStopsWithTheSession() throws Exception {
        Path share = Files.createTempDirectory("avf9p-lifecycle");
        Files.writeString(share.resolve("hello.txt"), "hello");
        SharedDirectoryContract.SharedDirectorySpec shareSpec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(
                        share.toAbsolutePath().toString());

        AtomicInteger accepted = new AtomicInteger();
        Consumer<Socket> guestClient = socket -> {
            accepted.incrementAndGet();
            try (socket) {
                // Keep the mount-side connection open until the host closes it.
                while (socket.getInputStream().read() >= 0) {
                    // discard; this test asserts lifecycle, not 9p payloads
                }
            } catch (IOException ignored) {
                // the host closed the share channel
            }
        };

        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(
                TOKEN, 9100, 9101, AvfLaunchSpec.DEFAULT_9P_VSOCK_PORT, guestClient, 0)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(AvfLaunchSpec.builder(
                            "share-lifecycle-1", "/kernel", "/initramfs", "/root.raw")
                    .sharedDirectory(shareSpec)
                    .build());

            Avf9pShareServer first = session.startShareServer();
            Avf9pShareServer second = session.startShareServer();
            assertTrue(first == second, "a second start returns the same owner");
            await(() -> accepted.get() == 1, "exactly one share connection is opened");
            assertTrue(session.isShareServing(), "the server thread is alive");

            session.stopRuntime(0L);
            await(() -> !session.isShareServing(), "the server stops with the session");

            try {
                session.startShareServer();
                throw new AssertionError("a closed session must refuse a second 9p server");
            } catch (AvfEngineException expected) {
                assertTrue(expected.reason().equals(AvfEngineException.VSOCK_CONNECT_FAILED),
                        "the refusal is typed");
            }
            assertEquals(1, accepted.get(), "no second vsock connection was opened");
            session.closeChannels();
            session.closeChannels();
        } finally {
            Files.deleteIfExists(share.resolve("hello.txt"));
            Files.deleteIfExists(share);
        }
    }

    private static void await(Check condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.ok()) return;
            Thread.sleep(20L);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
