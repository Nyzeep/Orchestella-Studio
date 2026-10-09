package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The Ticket 30 read-only share contract, exercised over the AVF 9p-over-vsock
 * data plane: the SAME {@link SharedDirectoryContract.SharedDirectorySpec} the
 * QEMU strategy serves through virtio-9p drives the self-built 9P2000.L
 * server, and a mount-side client (the trans=fd stand-in) verifies the wire
 * semantics — browsing works, and every write-class operation is physically
 * refused EROFS (ADR-0009 data plane, ADR-0010 read-only whitelist).
 */
public final class Avf9pShareContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final int SHARE_PORT = 9102;

    public static void main(String[] arguments) throws Exception {
        browseReadonlyThroughTheContractSpecOver9p();
        rejectEveryWriteClassOperationWithErofs();
        refuseUnknownAttachNamesAndContainEscapes();
        keepTheDataPlaneSilentWithoutAShare();
        System.out.println(
                "AVF_9P_SHARE_CONTRACT_TEST_OK browse=1 write_refusals=1 containment=1"
                        + " no_share_leak=1");
    }

    private static void browseReadonlyThroughTheContractSpecOver9p() throws Exception {
        Path share = Files.createTempDirectory("avf9p-share");
        Files.writeString(share.resolve("hello.txt"), "hello from Downloads",
                StandardCharsets.UTF_8);
        Files.createDirectories(share.resolve("nested"));
        Files.writeString(share.resolve("nested/deep.txt"), "deep", StandardCharsets.UTF_8);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Consumer<Socket> mountClient = client -> {
            try {
                TestNinePClient nineP = new TestNinePClient(
                        client.getInputStream(), client.getOutputStream());
                String negotiated = nineP.version();
                TestNinePClient.assertEquals("9P2000.L", negotiated, "negotiated version");
                nineP.attach(1, "downloads");
                int walked = nineP.walk(1, 2, "nested", "deep.txt");
                TestNinePClient.assertEquals(2, walked, "two-element walk succeeds");
                nineP.lopen(2);
                byte[] content = nineP.read(2, 0, 1024);
                TestNinePClient.assertEquals(
                        "deep", new String(content, StandardCharsets.UTF_8), "file content");
                TestNinePClient.Attr attr = nineP.getattr(2);
                TestNinePClient.assertEquals(4L, attr.size, "getattr size");

                nineP.attach(3, "downloads");
                nineP.lopen(3);
                List<String> names = nineP.readDirNames(3);
                TestNinePClient.assertEquals(
                        Arrays.asList("hello.txt", "nested"), names, "root listing");
                nineP.clunk(3);
                nineP.clunk(2);
                nineP.clunk(1);
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        };
        runShareScenario(share, mountClient, done, failure);
    }

    private static void rejectEveryWriteClassOperationWithErofs() throws Exception {
        Path share = Files.createTempDirectory("avf9p-writes");
        Files.writeString(share.resolve("entry.txt"), "data", StandardCharsets.UTF_8);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Consumer<Socket> mountClient = client -> {
            try {
                TestNinePClient nineP = new TestNinePClient(
                        client.getInputStream(), client.getOutputStream());
                nineP.version();
                nineP.attach(1, "downloads");
                nineP.lopen(1);

                nineP.writeIsRejectedWithErofs(nineP.writeRequest(1, "write".getBytes()));
                nineP.writeIsRejectedWithErofs(nineP.mkdirRequest(1, "sneaky"));
                nineP.writeIsRejectedWithErofs(nineP.lcreateRequest(1));
                nineP.writeIsRejectedWithErofs(nineP.setattrRequest(1));
                nineP.writeIsRejectedWithErofs(nineP.removeRequest(1));
                nineP.writeIsRejectedWithErofs(nineP.unlinkatRequest(1, "entry.txt"));
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        };
        runShareScenario(share, mountClient, done, failure);
    }

    private static void refuseUnknownAttachNamesAndContainEscapes() throws Exception {
        Path share = Files.createTempDirectory("avf9p-containment");
        Files.createDirectories(share.resolve("sub"));
        Files.writeString(share.resolve("sub/leaf.txt"), "leaf", StandardCharsets.UTF_8);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Consumer<Socket> mountClient = client -> {
            try {
                TestNinePClient nineP = new TestNinePClient(
                        client.getInputStream(), client.getOutputStream());
                nineP.version();
                nineP.attach(1, "downloads");

                // ".." clamps at the export root: walking it from root lands
                // back on the root qid instead of escaping the share.
                nineP.walk(1, 2, "..");
                // A missing name is refused (first-element failure = Rlerror).
                try {
                    nineP.walk(1, 3, "missing-name");
                    failure.set(new IllegalStateException("a missing name must not walk"));
                    return;
                } catch (TestNinePClient.NinePError refused) {
                    TestNinePClient.assertEquals(
                            TestNinePClient.ENOENT, refused.errno, "missing walk element");
                }
                // A slash-bearing element never reaches the file system.
                try {
                    nineP.walk(1, 4, "sub/../..");
                    failure.set(new IllegalStateException(
                            "a slash-bearing element must not walk"));
                    return;
                } catch (TestNinePClient.NinePError refused) {
                    TestNinePClient.assertEquals(
                            TestNinePClient.ENOENT, refused.errno, "slash-bearing element");
                }
                // Inside a subdirectory ".." returns to the root, never above.
                nineP.walk(1, 5, "sub");
                nineP.walk(5, 6, "..");
                // Attach with an unknown name is refused outright.
                try {
                    nineP.attach(7, "elsewhere");
                    failure.set(new IllegalStateException("an unknown attach name must fail"));
                    return;
                } catch (TestNinePClient.NinePError refused) {
                    TestNinePClient.assertEquals(
                            TestNinePClient.ENOENT, refused.errno, "unknown attach name");
                }
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        };
        runShareScenario(share, mountClient, done, failure);
    }

    private static void keepTheDataPlaneSilentWithoutAShare() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.recordingApiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(AvfLaunchSpec.builder(
                            "no-share-1", "/kernel", "/initramfs", "/root.raw")
                    .build());
            try {
                session.shareChannel();
                fail("shareChannel must refuse without a configured share");
            } catch (AvfEngineException expected) {
                // the data plane stays closed when nothing is shared
            }
            List<VirtualMachineCustomImageConfig> observed = guest.observedConfigs();
            TestNinePClient.assertEquals(
                    true, !observed.isEmpty(), "at least one observed config");
            String cmdline = observed.get(0).cmdline();
            TestNinePClient.assertEquals(true, cmdline != null, "cmdline assembled");
            if (cmdline.contains("droidlayer.vsock_9p_port")) {
                fail("no 9p data-plane token may appear without a share: " + cmdline);
            }
            session.closeChannels();
        }
    }

    /**
     * Boots the engine with the T30 share spec while the simulated guest
     * listens on the 9p rendezvous port and runs the mount-side client, then
     * asserts the cmdline announce actually reached the observed VM config.
     */
    private static void runShareScenario(
            Path share,
            Consumer<Socket> mountClient,
            CountDownLatch done,
            AtomicReference<Throwable> failure) throws Exception {
        SharedDirectoryContract.SharedDirectorySpec spec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(
                        share.toAbsolutePath().toString());
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(
                TOKEN, 9100, 9101, SHARE_PORT, mountClient, 0)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.recordingApiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(AvfLaunchSpec.builder(
                            "share-1", "/kernel", "/initramfs", "/root.raw")
                    .sharedDirectory(spec)
                    .build());
            String cmdline = guest.observedConfigs().get(0).cmdline();
            if (cmdline == null
                    || !cmdline.contains("droidlayer.vsock_9p_port=" + SHARE_PORT)) {
                fail("the cmdline must announce the 9p data port: " + cmdline);
            }
            session.startShareServer();
            if (!done.await(15, TimeUnit.SECONDS)) {
                fail("the mount-side client did not finish");
            }
            if (failure.get() != null) {
                fail("mount-side scenario failed: " + failure.get());
            }
            session.closeChannels();
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_9P_SHARE_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
