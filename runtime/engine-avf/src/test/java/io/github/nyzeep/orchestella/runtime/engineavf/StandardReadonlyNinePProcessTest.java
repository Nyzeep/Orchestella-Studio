package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Actual child JVM stream/EOF and synthetic symlink race tests. No Android or device calls. */
public final class StandardReadonlyNinePProcessTest {
    private static void check(boolean ok, String label) { StandardReadonlyNinePTest.check(ok, label); }
    private static int fds() throws IOException {
        try (var directory = Files.list(Paths.get("/proc/self/fd"))) { return (int) directory.count(); }
    }
    private static byte[] sequence() throws Exception {
        return StandardReadonlyNinePTest.join(StandardReadonlyNinePTest.version(8192),
                StandardReadonlyNinePTest.attach(1, 1), StandardReadonlyNinePTest.walk(1, 2, 2, "safe.txt"),
                StandardReadonlyNinePTest.frame(12, 3, StandardReadonlyNinePTest.payload(2, 0)));
    }
    private static void resourceAndSessionReset(Path root) throws Exception {
        Files.writeString(root.resolve("safe.txt"), "safe synthetic file");
        for (int i = 0; i < 10; i++) StandardReadonlyNinePTest.serve(root, sequence());
        int baseline = fds();
        for (int i = 0; i < 500; i++) {
            StandardReadonlyNinePTest.serve(root, sequence());
            var answers = StandardReadonlyNinePTest.serve(root, StandardReadonlyNinePTest.join(sequence(),
                StandardReadonlyNinePTest.version(8192),
                StandardReadonlyNinePTest.frame(116, 4, StandardReadonlyNinePTest.payload(2, 0L, 64))));
            check(answers.get(5).errno() == 9, "version consumes old fids");
            try {
                byte[] shortFrame = new byte[] {30, 0, 0, 0, 116, 1, 0};
                StandardReadonlyNinePTest.serve(root, StandardReadonlyNinePTest.join(sequence(), shortFrame));
                throw new AssertionError("mid-frame EOF accepted");
            } catch (IOException expected) { /* finally must close open file */ }
        }
        List<StandardReadonlyNinePTest.Reply> invalidVersion = StandardReadonlyNinePTest.serve(root,
            StandardReadonlyNinePTest.join(sequence(), StandardReadonlyNinePTest.frame(100, 65535, new byte[0]),
            StandardReadonlyNinePTest.frame(116, 4, StandardReadonlyNinePTest.payload(2, 0L, 64))));
        check(invalidVersion.get(4).errno() == 22 && invalidVersion.get(5).errno() == 22,
            "invalid renegotiation invalidates old open session");
        OutputStream failedOutput = new OutputStream() {
            int reply;
            @Override public void write(int value) throws IOException { throw new IOException("synthetic sink failure"); }
            @Override public void write(byte[] bytes) throws IOException {
                if (++reply == 4) throw new IOException("synthetic sink failure after open");
            }
        };
        try {
            new Avf9pShareServer(SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(root.toString()))
                    .serve(new ByteArrayInputStream(sequence()), failedOutput);
            throw new AssertionError("write failure swallowed");
        } catch (IOException expected) { /* session cleanup also covers outgoing transport failures */ }
        int after = fds();
        check(after <= baseline + 2, "all EOF/reset/malformed/output paths release descriptors: " + baseline + " -> " + after);
        System.out.println("NINEP_FD_RECLAIM_OK sessions=1500 before=" + baseline + " after=" + after);
    }
    private static Process server(Path root, String label) throws IOException {
        return new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin/java").toString(),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", System.getProperty("java.class.path"),
                StandardReadonlyNinePProcessTest.class.getName(), "--serve", root.toString())
                .redirectError(root.getParent().resolve(label + "-stderr.log.txt").toFile()).start();
    }
    private static void processEofAndReadonly(Path root) throws Exception {
        Process process = server(root, "eof");
        try {
            StandardNinePClient client = new StandardNinePClient(process.getInputStream(), process.getOutputStream());
            check(client.version().equals("9P2000.L"), "process version");
            client.attach(1, "downloads"); client.walk(1, 2, "safe.txt"); client.lopen(2);
            check(new String(client.read(2, 0, 100), java.nio.charset.StandardCharsets.UTF_8).equals("safe synthetic file"), "real process read");
            client.mutationRejected(118, new byte[0]);
            process.getOutputStream().close();
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "open file session ends at EOF");
        } finally { process.destroyForcibly(); }
        Process broken = server(root, "short");
        try {
            broken.getOutputStream().write(new byte[] {30, 0, 0, 0, 100, -1, -1});
            broken.getOutputStream().close();
            check(broken.waitFor(5, TimeUnit.SECONDS) && broken.exitValue() != 0, "actual process rejects mid-frame EOF");
        } finally { broken.destroyForcibly(); }
    }
    private static void symlinkRace(Path root) throws Exception {
        Path outside = root.getParent().resolve("outside-process-fixture");
        Files.createDirectories(outside); Files.writeString(outside.resolve("secret.txt"), "OUTSIDE_SYNTHETIC_SENTINEL");
        Path victim = root.resolve("victim");
        AtomicBoolean stopped = new AtomicBoolean(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; !stopped.get(); i++) {
                    Path replacement = root.resolve("replacement");
                    Files.deleteIfExists(replacement);
                    if ((i & 1) == 0) Files.createSymbolicLink(replacement, outside.resolve("secret.txt"));
                    else Files.writeString(replacement, "inside synthetic fixture");
                    Files.move(replacement, victim, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (Throwable error) { failure.set(error); }
        }, "synthetic-symlink-replacement");
        Process process = server(root, "race"); int reads = 0, denials = 0;
        try {
            StandardNinePClient client = new StandardNinePClient(process.getInputStream(), process.getOutputStream());
            client.version(); client.attach(1, "downloads"); writer.start();
            for (int i = 0; i < 400; i++) {
                try {
                    client.walk(1, 2, "victim"); client.lopen(2);
                    String text = new String(client.read(2, 0, 128), java.nio.charset.StandardCharsets.UTF_8);
                    check(text.equals("inside synthetic fixture"), "symlink race must never read outside sentinel"); reads++;
                } catch (StandardNinePClient.NinePError refused) {
                    check(Set.of(2, 13).contains(refused.errno), "deterministic race refusal"); denials++;
                } finally {
                    try { client.clunk(2); } catch (StandardNinePClient.NinePError absent) { check(absent.errno == 9, "missing fid error"); }
                }
            }
            process.getOutputStream().close(); check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "race process EOF");
        } finally { stopped.set(true); writer.join(5000); process.destroyForcibly(); }
        check(!writer.isAlive() && failure.get() == null, "replacement thread shutdown");
        check(reads + denials == 400, "every race iteration accounted");
        System.out.println("NINEP_PROCESS_RACE_OK iterations=400 reads=" + reads + " refusals=" + denials);
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--serve")) {
            new Avf9pShareServer(SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(args[1]))
                    .serve(System.in, System.out);
            return;
        }
        Path root = Paths.get(args[0]); Files.createDirectories(root);
        check(StandardReadonlyNinePTest.secureProvider(root), "process tests require actual Linux secure directory operations");
        resourceAndSessionReset(root); processEofAndReadonly(root); symlinkRace(root);
        System.out.println("STANDARD_READONLY_9P_PROCESS_OK fd_reclaim=1 actual_process_eof=1 symlink_race=1");
    }
}
