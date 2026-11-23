package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UmlEphemeralRootFixtureTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void preparesScriptsTokenAndCleansUpOnEveryHost() throws Exception {
        byte[] tarGz = buildMinimalAlpineTarGz(false, false);
        Path runtimeDir = tmp.newFolder("runtime").toPath();

        Path rootDir = UmlEphemeralRootFixture.prepare(
                () -> new ByteArrayInputStream(tarGz),
                runtimeDir,
                "gen-42",
                TOKEN,
                "uml-qa");

        assertTrue(Files.isDirectory(rootDir));
        assertTrue(Files.isRegularFile(rootDir.resolve("bin/busybox")));
        assertTrue(rootDir.resolve("bin/busybox").toFile().canExecute());

        Path tokenFile = rootDir.resolve("etc/orchestella/bridge.token");
        assertTrue(Files.isRegularFile(tokenFile));
        assertEquals(TOKEN + "\n", Files.readString(tokenFile, StandardCharsets.UTF_8));

        String umCommand = Files.readString(rootDir.resolve("um-command.sh"), StandardCharsets.UTF_8);
        String agentScript = Files.readString(
                rootDir.resolve("etc/orchestella/bridge-agent.sh"), StandardCharsets.UTF_8);
        String loginShell = Files.readString(
                rootDir.resolve("etc/orchestella/login-shell.sh"), StandardCharsets.UTF_8);
        String forwardScript = Files.readString(
                rootDir.resolve("etc/orchestella/forward-connection.sh"), StandardCharsets.UTF_8);
        String ashrc = Files.readString(
                rootDir.resolve("etc/orchestella/ashrc"), StandardCharsets.UTF_8);

        for (String script : new String[] {umCommand, agentScript, loginShell, forwardScript, ashrc}) {
            assertFalse("script must use LF line endings only", script.contains("\r"));
        }
        assertTrue(rootDir.resolve("um-command.sh").toFile().canExecute());
        assertTrue(rootDir.resolve("etc/orchestella/bridge-agent.sh").toFile().canExecute());
        assertTrue(rootDir.resolve("etc/orchestella/forward-connection.sh").toFile().canExecute());
        assertTrue(rootDir.resolve("etc/orchestella/login-shell.sh").toFile().canExecute());

        assertTrue(umCommand.contains("/etc/orchestella/bridge-agent.sh"));
        assertTrue(umCommand.contains("tmpfs /run"));
        assertTrue(umCommand.contains("net_pid=$!"));
        assertTrue(umCommand.contains("kill \"$agent_pid\" \"$net_pid\""));
        assertTrue(umCommand.contains("umount /etc/resolv.conf"));
        assertTrue(umCommand.contains("/dev/tty0"));
        assertTrue(umCommand.contains("setsid"));
        assertTrue(umCommand.contains("poweroff"));

        assertTrue(forwardScript.contains(Integer.toString(UmlNetworkProfile.GUEST_RELAY_PORT)));
        assertTrue(forwardScript.contains("nc -w 60 127.0.0.1 \"$port\""));
        assertFalse(forwardScript.contains("nc -w 3"));
        assertFalse(forwardScript.contains("timeout -k 1 300"));

        assertTrue(agentScript.contains("stty raw -echo"));
        assertTrue(agentScript.indexOf("stty raw -echo") < agentScript.indexOf("exec 3<\"$DEV\""));
        assertTrue(agentScript.contains("/dev/ttyS0"));
        assertTrue(agentScript.contains("ORCHESTELLA/HELLO"));
        assertTrue(agentScript.contains("ORCHESTELLA/READY 1"));
        assertTrue(agentScript.contains("ORCHESTELLA/RESIZE"));
        assertTrue(agentScript.contains("ORCHESTELLA/POWER"));

        assertFalse(ashrc.contains("133;A;orchestella=1"));
        assertTrue(ashrc.contains("133;B;orchestella=prompt"));
        assertTrue(ashrc.contains("PS1='\\u@\\h:\\w\\$ '"));

        UmlEphemeralRootFixture.cleanup(rootDir);
        assertFalse(Files.exists(rootDir));
    }

    @Test
    public void extractsSymlinksAddsVimAliasAndCleanupDoesNotFollowSymlinks() throws Exception {
        assumeTrue("symbolic links unavailable on this host", symlinksSupported(tmp.getRoot().toPath()));
        byte[] tarGz = buildMinimalAlpineTarGz(true, false);
        Path runtimeDir = tmp.newFolder("runtime-sym").toPath();

        Path rootDir = UmlEphemeralRootFixture.prepare(
                () -> new ByteArrayInputStream(tarGz),
                runtimeDir,
                "gen-sym",
                TOKEN,
                "uml-qa");

        assertTrue(Files.isSymbolicLink(rootDir.resolve("bin/sh")));
        assertTrue(Files.isSymbolicLink(rootDir.resolve("usr/bin/vim")));
        assertEquals("vi", Files.readSymbolicLink(rootDir.resolve("usr/bin/vim")).toString());

        Path externalSentinel = tmp.newFile("external-sentinel.txt").toPath();
        Files.writeString(externalSentinel, "keep", StandardCharsets.UTF_8);
        Files.createSymbolicLink(rootDir.resolve("tmp/escape-link"), externalSentinel);

        UmlEphemeralRootFixture.cleanup(rootDir);
        assertFalse(Files.exists(rootDir));
        assertTrue(Files.exists(externalSentinel));
        assertEquals("keep", Files.readString(externalSentinel, StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsTarSlipTraversalEntriesFailClosed() throws Exception {
        byte[] malicious = buildMinimalAlpineTarGz(false, true);
        Path runtimeDir = tmp.newFolder("runtime-slip").toPath();
        try {
            UmlEphemeralRootFixture.prepare(
                    () -> new ByteArrayInputStream(malicious),
                    runtimeDir,
                    "gen-bad",
                    TOKEN,
                    "uml-qa");
            fail("expected slip rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
        assertFalse(Files.exists(runtimeDir.resolve("ephemeral-rootfs-gen-bad")));
    }

    @Test
    public void rejectsSymlinkEtcDirectoryDuringRuntimeInjectionFailClosed() throws Exception {
        assumeTrue("symbolic links unavailable on this host", symlinksSupported(tmp.getRoot().toPath()));
        Path externalDir = tmp.newFolder("external-etc").toPath();
        ByteArrayOutputStream rawTar = new ByteArrayOutputStream();
        writeDirEntry(rawTar, "./", 0755);
        writeDirEntry(rawTar, "./bin/", 0755);
        writeFileEntry(rawTar, "./bin/busybox", "ELF".getBytes(StandardCharsets.UTF_8), 0755);
        writeSymlinkEntry(rawTar, "./bin/sh", "busybox");
        writeSymlinkEntry(rawTar, "./etc", externalDir.toAbsolutePath().toString());
        rawTar.write(new byte[1024]);
        ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzOut)) {
            gzip.write(rawTar.toByteArray());
        }

        Path runtimeDir = tmp.newFolder("runtime-etc-link").toPath();
        try {
            UmlEphemeralRootFixture.prepare(
                    () -> new ByteArrayInputStream(gzOut.toByteArray()),
                    runtimeDir,
                    "gen-etc-sym",
                    TOKEN,
                    "uml-qa");
            fail("expected symlink directory rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symlink"));
        }
        assertFalse(Files.exists(externalDir.resolve("orchestella")));
        assertFalse(Files.exists(runtimeDir.resolve("ephemeral-rootfs-gen-etc-sym")));
    }

    private static boolean symlinksSupported(Path dir) {
        try {
            Path probe = Files.createSymbolicLink(dir.resolve("symlink-probe"), dir);
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            return false;
        }
    }

    private static byte[] buildMinimalAlpineTarGz(boolean includeSymlinks, boolean includeTraversal)
            throws IOException {
        ByteArrayOutputStream rawTar = new ByteArrayOutputStream();
        writeDirEntry(rawTar, "./", 0755);
        writeDirEntry(rawTar, "./bin/", 0755);
        writeDirEntry(rawTar, "./usr/", 0755);
        writeDirEntry(rawTar, "./usr/bin/", 0755);
        writeDirEntry(rawTar, "./etc/", 0755);
        writeDirEntry(rawTar, "./tmp/", 0777);
        writeFileEntry(rawTar, "./bin/busybox", "ELF-busybox".getBytes(StandardCharsets.UTF_8), 0755);
        if (includeSymlinks) {
            writeSymlinkEntry(rawTar, "./bin/sh", "busybox");
            writeSymlinkEntry(rawTar, "./usr/bin/vi", "../../bin/busybox");
            writeSymlinkEntry(rawTar, "./usr/bin/top", "../../bin/busybox");
        } else {
            writeFileEntry(rawTar, "./bin/sh", "ELF-sh".getBytes(StandardCharsets.UTF_8), 0755);
        }
        if (includeTraversal) {
            writeFileEntry(rawTar, "../outside.txt", "bad".getBytes(StandardCharsets.UTF_8), 0644);
        }
        rawTar.write(new byte[1024]);

        ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzOut)) {
            gzip.write(rawTar.toByteArray());
        }
        return gzOut.toByteArray();
    }

    private static void writeDirEntry(ByteArrayOutputStream out, String name, int mode)
            throws IOException {
        writeHeader(out, name, mode, 0L, (byte) '5', null);
    }

    private static void writeFileEntry(
            ByteArrayOutputStream out, String name, byte[] data, int mode) throws IOException {
        writeHeader(out, name, mode, data.length, (byte) '0', null);
        out.write(data);
        int rem = data.length % 512;
        if (rem != 0) {
            out.write(new byte[512 - rem]);
        }
    }

    private static void writeSymlinkEntry(ByteArrayOutputStream out, String name, String target)
            throws IOException {
        writeHeader(out, name, 0777, 0L, (byte) '2', target);
    }

    private static void writeHeader(
            ByteArrayOutputStream out,
            String name,
            int mode,
            long size,
            byte typeFlag,
            String linkName)
            throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = typeFlag;
        if (linkName != null) {
            byte[] linkBytes = linkName.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(linkBytes, 0, header, 157, Math.min(linkBytes.length, 100));
        }
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, header, 263, 2);
        long checksum = 0;
        for (byte b : header) {
            checksum += (b & 0xFF);
        }
        writeOctal(header, 148, 7, checksum);
        header[155] = 0;
        out.write(header);
    }

    private static void writeOctal(byte[] buf, int offset, int length, long value) {
        String formatted = String.format(java.util.Locale.ROOT, "%0" + (length - 1) + "o", value);
        byte[] bytes = formatted.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, length - 1));
        buf[offset + length - 1] = 0;
    }
}
