package io.github.nyzeep.orchestella.runtime.engineuml;

import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Prepares and cleans up a disposable Alpine rootfs tree for standalone QA
 * UML execution. Completely isolated from the ride-along compile face's shared
 * {@code uml-rootfs/} tree.
 */
public final class UmlEphemeralRootFixture {
    private static final long MAX_METADATA_PAYLOAD_BYTES = 1024L * 1024L;
    private static final int TAR_BLOCK_BYTES = 512;

    /** Supplies the offline Alpine minirootfs {@code .tar.gz} stream. */
    @FunctionalInterface
    public interface TarSource {
        InputStream open() throws IOException;
    }

    private UmlEphemeralRootFixture() {}

    /**
     * Unpacks {@code tarSource} into {@code <runtimeDir>/ephemeral-rootfs-<generation>}
     * using slip-safe validation, injects the interactive console launcher and Bridge
     * v1 serial agent, and writes the 64-hex token file (owner-readable only).
     */
    public static Path prepare(
            TarSource tarSource,
            Path runtimeDir,
            String generation,
            String bridgeToken,
            String hostname)
            throws IOException {
        Objects.requireNonNull(tarSource, "tarSource");
        Objects.requireNonNull(runtimeDir, "runtimeDir");
        String safeGeneration = sanitizeSegment(generation);
        if (!BridgeHandshake.isCanonicalToken(bridgeToken)) {
            throw new IllegalArgumentException(
                    "bridge token must be 64 lowercase hexadecimal characters");
        }
        String safeHostname = sanitizeHostname(hostname);

        Path normalizedRuntime = runtimeDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRuntime);
        Path targetDir = normalizedRuntime.resolve("ephemeral-rootfs-" + safeGeneration);
        Path stagingDir = normalizedRuntime.resolve("ephemeral-rootfs-" + safeGeneration + ".part");

        cleanup(stagingDir);
        cleanup(targetDir);
        Files.createDirectories(stagingDir);
        try {
            try (InputStream raw = tarSource.open()) {
                if (raw == null) {
                    throw new IOException("ephemeral Alpine rootfs tar stream unavailable");
                }
                extractSlipSafeTarGz(raw, stagingDir);
            }
            ensureVimAlias(stagingDir);
            injectGuestRuntime(stagingDir, bridgeToken, safeHostname);
            moveDirectoryIntoPlace(stagingDir, targetDir);
            return targetDir;
        } catch (IOException | RuntimeException failure) {
            try {
                cleanup(stagingDir);
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    /**
     * Recursively deletes an ephemeral rootfs directory without following symbolic links.
     * Overwrites any Bridge token file before unlinking.
     */
    public static void cleanup(Path rootDir) throws IOException {
        if (rootDir == null || !Files.exists(rootDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path tokenFile = rootDir.resolve("etc/orchestella/bridge.token");
        if (Files.isRegularFile(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.write(tokenFile, new byte[65], StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
                // Best-effort zeroization before deletion.
            }
        }
        List<Path> paths;
        try (var walk = Files.walk(rootDir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path entry : paths) {
            Files.deleteIfExists(entry);
        }
    }

    private static void injectGuestRuntime(Path rootDir, String bridgeToken, String hostname)
            throws IOException {
        Path etcOrch = rootDir.resolve("etc/orchestella");
        requireTraversableParent(rootDir, etcOrch);
        Files.createDirectories(etcOrch);
        applyModeBestEffort(etcOrch, 0755);

        writeUtf8File(etcOrch.resolve("bridge.token"), bridgeToken + "\n", 0600, false);
        writeUtf8File(etcOrch.resolve("bridge-agent.sh"), bridgeAgentScript(), 0755, true);
        writeUtf8File(
                etcOrch.resolve("forward-connection.sh"),
                UmlPersistentGuestScripts.forwardConnectionScript(), 0755, true);
        writeUtf8File(
                etcOrch.resolve("login-shell.sh"), loginShellScript(hostname), 0755, true);
        writeUtf8File(etcOrch.resolve("ashrc"), ashRcScript(), 0644, false);
        writeUtf8File(rootDir.resolve("um-command.sh"), umCommandScript(), 0755, true);
    }

    private static void ensureVimAlias(Path rootDir) throws IOException {
        Path usrBin = rootDir.resolve("usr/bin");
        Path vi = usrBin.resolve("vi");
        Path vim = usrBin.resolve("vim");
        requireTraversableParent(rootDir, usrBin);
        if (Files.exists(vi, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(vim, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(usrBin);
            try {
                Files.createSymbolicLink(vim, Paths.get("vi"));
            } catch (IOException | UnsupportedOperationException failure) {
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    throw failure;
                }
                Files.copy(vi, vim, StandardCopyOption.REPLACE_EXISTING);
                applyModeBestEffort(vim, 0755L);
            }
        }
    }

    private static void writeUtf8File(
            Path target, String content, int mode, boolean executable) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("refusing write through symlink target: " + target);
        }
        String lfContent = content.replace("\r\n", "\n");
        Files.write(
                target,
                lfContent.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        applyModeBestEffort(target, mode);
        File file = target.toFile();
        if (executable) {
            file.setReadable(true, false);
            file.setExecutable(true, false);
        } else {
            file.setReadable(true, (mode & 0077) == 0);
        }
    }

    private static String umCommandScript() {
        return """
                #!/bin/busybox sh
                set -u
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export PATH

                mkdir -p /tmp /run /root 2>/dev/null || :
                chmod 1777 /tmp 2>/dev/null || :
                if ! grep -q ' /tmp ' /proc/mounts 2>/dev/null; then
                    mount -t tmpfs -o mode=1777 tmpfs /tmp 2>/dev/null || :
                fi
                if ! grep -q ' /run ' /proc/mounts 2>/dev/null; then
                    mount -t tmpfs -o mode=0755 tmpfs /run 2>/dev/null || :
                fi

                /etc/orchestella/bridge-agent.sh &
                agent_pid=$!

                """ + UmlPersistentGuestScripts.startGuestNetwork() + """
                setsid_bin=/usr/bin/setsid
                if [ ! -x "$setsid_bin" ]; then
                    setsid_bin=$(command -v setsid 2>/dev/null || printf /bin/busybox)
                fi
                if [ "$(basename "$setsid_bin")" = "busybox" ]; then
                    "$setsid_bin" setsid /bin/sh -c 'exec </dev/tty0 >/dev/tty0 2>&1; exec /etc/orchestella/login-shell.sh'
                else
                    "$setsid_bin" /bin/sh -c 'exec </dev/tty0 >/dev/tty0 2>&1; exec /etc/orchestella/login-shell.sh'
                fi
                shell_rc=$?

                kill "$agent_pid" "$net_pid" 2>/dev/null || :
                umount /etc/resolv.conf 2>/dev/null || :
                sync 2>/dev/null || :
                poweroff -f 2>/dev/null || :
                exit "$shell_rc"
                """;
    }

    private static String loginShellScript(String hostname) {
        return "#!/bin/sh\n"
                + "export TERM=xterm-256color\n"
                + "export HOME=/root\n"
                + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                + "export ENV=/etc/orchestella/ashrc\n"
                + "mkdir -p /root /run 2>/dev/null || :\n"
                + "hostname " + hostname + " 2>/dev/null || :\n"
                + "cd /root 2>/dev/null || :\n"
                + "if [ -r /run/orchestella-winsize ]; then\n"
                + "    read -r _rows _cols </run/orchestella-winsize 2>/dev/null || :\n"
                + "    if [ -n \"${_rows:-}\" ] && [ -n \"${_cols:-}\" ]; then\n"
                + "        stty rows \"$_rows\" cols \"$_cols\" </dev/tty0 2>/dev/null || :\n"
                + "    fi\n"
                + "fi\n"
                + "exec /bin/sh -i\n";
    }

    private static String ashRcScript() {
        return """
                # Orchestella interactive Alpine shell readiness (no OSC 133;A block mode; BusyBox ash lacks PS0/DEBUG)
                case "$-" in
                    *i*)
                        PS1='\\u@\\h:\\w\\$ '
                        printf '\\033]133;B;orchestella=prompt\\007'
                        ;;
                esac
                """;
    }

    private static String bridgeAgentScript() {
        return """
                #!/bin/sh
                set -u
                TOKEN_FILE=/etc/orchestella/bridge.token
                DEV=/dev/ttyS0

                i=0
                while [ ! -e "$DEV" ] && [ "$i" -lt 20 ]; do
                    sleep 0.05 2>/dev/null || sleep 1
                    i=$((i + 1))
                done
                [ -e "$DEV" ] || exit 1

                stty raw -echo -icrnl -onlcr -isig <"$DEV" 2>/dev/null || :
                exec 3<"$DEV" 4>"$DEV" || exit 1

                EXPECTED=$(tr -d '\\r\\n' <"$TOKEN_FILE" 2>/dev/null || :)
                [ ${#EXPECTED} -eq 64 ] || exit 1

                is_uint() {
                    case "$1" in
                        ''|*[!0-9]*) return 1 ;;
                        *) return 0 ;;
                    esac
                }

                is_hex64() {
                    [ ${#1} -eq 64 ] || return 1
                    case "$1" in
                        *[!0-9a-f]*) return 1 ;;
                        *) return 0 ;;
                    esac
                }

                authed=0
                while IFS= read -r raw_line <&3; do
                    line=$(printf '%s' "$raw_line" | tr -d '\\r')
                    [ -n "$line" ] || continue
                    case "$line" in
                        ORCHESTELLA/HELLO\\ *|DROIDLAYER/HELLO\\ *|ORCHESTELLA/HELLO|DROIDLAYER/HELLO)
                            set -- $line
                            if [ $# -ne 4 ] || ! is_uint "$2" || ! is_uint "$3" || ! is_hex64 "$4"; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR malformed-handshake\\n' >&4
                                exit 1
                            fi
                            if [ "$2" -gt "$3" ]; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR malformed-handshake\\n' >&4
                                exit 1
                            fi
                            if [ "$4" != "$EXPECTED" ]; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR unauthorized\\n' >&4
                                exit 3
                            fi
                            if [ "$2" -gt 1 ] || [ "$3" -lt 1 ]; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR unsupported-version\\n' >&4
                                exit 2
                            fi
                            authed=1
                            printf 'ORCHESTELLA/READY 1\\n' >&4
                            ;;
                        ORCHESTELLA/PING|DROIDLAYER/PING)
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                                continue
                            fi
                            printf 'ORCHESTELLA/PONG 1\\n' >&4
                            ;;
                        ORCHESTELLA/RESIZE\\ *|DROIDLAYER/RESIZE\\ *)
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                                continue
                            fi
                            set -- $line
                            if [ $# -ne 3 ] || ! is_uint "$2" || ! is_uint "$3" || [ "$2" -lt 1 ] || [ "$2" -gt 1000 ] || [ "$3" -lt 1 ] || [ "$3" -gt 1000 ]; then
                                printf 'ORCHESTELLA/ERROR malformed-command\\n' >&4
                                continue
                            fi
                            mkdir -p /run 2>/dev/null || :
                            printf '%s %s\\n' "$3" "$2" >/run/orchestella-winsize 2>/dev/null || :
                            stty rows "$3" cols "$2" </dev/tty0 2>/dev/null || :
                            printf 'ORCHESTELLA/OK resize\\n' >&4
                            ;;
                        "ORCHESTELLA/POWER shutdown"|"ORCHESTELLA/POWER poweroff"|"DROIDLAYER/POWER shutdown"|"DROIDLAYER/POWER poweroff")
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                                continue
                            fi
                            printf 'ORCHESTELLA/OK power\\n' >&4
                            (sleep 0.05 2>/dev/null || :; sync 2>/dev/null || :; poweroff -f 2>/dev/null || :) &
                            exit 0
                            ;;
                        *)
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                            else
                                printf 'ORCHESTELLA/ERROR unsupported-command\\n' >&4
                            fi
                            ;;
                    esac
                done
                """;
    }

    private static void extractSlipSafeTarGz(InputStream rawStream, Path stagingRoot)
            throws IOException {
        Set<String> seen = new HashSet<>();
        String pendingName = null;
        String pendingLinkName = null;
        try (GZIPInputStream gzip = new GZIPInputStream(rawStream, 64 * 1024)) {
            byte[] header = new byte[TAR_BLOCK_BYTES];
            byte[] block = new byte[TAR_BLOCK_BYTES];
            while (true) {
                if (!readBlock(gzip, header)) {
                    throw new IOException("truncated tar archive before end block");
                }
                if (isZeroBlock(header)) {
                    break;
                }
                verifyHeaderChecksum(header);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0xFF;
                boolean metadata = type == 'x' || type == 'g' || type == 'L' || type == 'K';
                if (metadata && size > MAX_METADATA_PAYLOAD_BYTES) {
                    throw new IOException("oversized tar metadata entry");
                }
                if (type == 'x' || type == 'g') {
                    byte[] payload = new byte[(int) size];
                    readPayload(gzip, payload, block);
                    if (type == 'x') {
                        String paxPath = paxRecord(payload, "path");
                        if (paxPath != null) {
                            pendingName = paxPath;
                        }
                    }
                    continue;
                }
                if (type == 'L') {
                    byte[] payload = new byte[(int) size];
                    readPayload(gzip, payload, block);
                    pendingName = trimNul(payload, 0, payload.length);
                    continue;
                }
                if (type == 'K') {
                    byte[] payload = new byte[(int) size];
                    readPayload(gzip, payload, block);
                    pendingLinkName = trimNul(payload, 0, payload.length);
                    continue;
                }

                String name = pendingName != null ? pendingName : trimNul(header, 0, 100);
                String prefix = pendingName == null ? trimNul(header, 345, 155) : "";
                if (!prefix.isEmpty() && !name.isEmpty()) {
                    name = prefix + "/" + name;
                }
                String linkName =
                        pendingLinkName != null
                                ? pendingLinkName
                                : (type == '1' || type == '2') ? trimNul(header, 157, 100) : null;
                pendingName = null;
                pendingLinkName = null;

                long mode = parseOctal(header, 100, 8);
                String normalized = normalizeEntryName(name);
                if (normalized.isEmpty()) {
                    applyModeBestEffort(stagingRoot, mode);
                    skipPayload(gzip, size, block);
                    continue;
                }
                if (!seen.add(normalized)) {
                    throw new IOException("duplicate tar entry: " + normalized);
                }
                Path target = stagingRoot.resolve(normalized);
                requireTraversableParent(stagingRoot, target.getParent());

                if (type == '0' || type == 0) {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out =
                            Files.newOutputStream(
                                    target,
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE)) {
                        copyPayload(gzip, out, size, block);
                    }
                    applyModeBestEffort(target, mode);
                    if ((mode & 0111L) != 0L) {
                        target.toFile().setExecutable(true, false);
                    }
                } else if (type == '5') {
                    Files.createDirectories(target);
                    applyModeBestEffort(target, mode);
                } else if (type == '2') {
                    Files.createDirectories(target.getParent());
                    if (linkName == null || linkName.isEmpty()) {
                        throw new IOException("malformed symlink target for " + normalized);
                    }
                    Files.createSymbolicLink(target, Paths.get(linkName));
                } else if (type == '1') {
                    String linkTarget = normalizeEntryName(linkName);
                    if (linkTarget.isEmpty() || !seen.contains(linkTarget)) {
                        throw new IOException("invalid hard link target: " + linkName);
                    }
                    Files.createDirectories(target.getParent());
                    Path resolvedTarget = stagingRoot.resolve(linkTarget);
                    try {
                        Files.createLink(target, resolvedTarget);
                    } catch (IOException linkDenied) {
                        if (Files.isRegularFile(resolvedTarget, LinkOption.NOFOLLOW_LINKS)) {
                            Files.copy(resolvedTarget, target);
                            applyModeBestEffort(target, mode);
                        } else {
                            throw linkDenied;
                        }
                    }
                } else {
                    throw new IOException("unsupported tar entry type=" + type + " name=" + name);
                }
                skipPayload(gzip, size, block);
            }
        } catch (ZipException malformed) {
            throw new IOException("corrupt gzip archive: " + malformed.getMessage(), malformed);
        } catch (EOFException truncated) {
            throw new IOException("truncated gzip archive", truncated);
        }
    }

    private static String normalizeEntryName(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) {
            throw new IOException("tar entry escapes extract root: " + raw);
        }
        String trimmed = raw;
        while (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        if (".".equals(trimmed) || "./".equals(trimmed) || trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("/")) {
            throw new IOException("tar entry escapes extract root: " + raw);
        }
        StringBuilder cleaned = new StringBuilder();
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IOException("tar entry escapes extract root: " + raw);
            }
            if (cleaned.length() > 0) {
                cleaned.append('/');
            }
            cleaned.append(segment);
        }
        return cleaned.toString();
    }

    private static void requireTraversableParent(Path root, Path parent) throws IOException {
        if (parent == null || parent.equals(root)) {
            return;
        }
        Path relative = root.relativize(parent);
        Path cursor = root;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("symlink parent traversal rejected: " + cursor);
            }
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int read = 0;
        while (read < block.length) {
            int n = in.read(block, read, block.length - read);
            if (n < 0) {
                if (read == 0) {
                    return false;
                }
                throw new IOException("truncated tar header block");
            }
            read += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyHeaderChecksum(byte[] header) throws IOException {
        long expected = parseOctal(header, 148, 8);
        long actual = 0L;
        for (int i = 0; i < TAR_BLOCK_BYTES; i++) {
            actual += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xFF);
        }
        if (expected != actual) {
            throw new IOException("invalid tar header checksum");
        }
    }

    private static long parseOctal(byte[] buf, int offset, int length) throws IOException {
        long value = 0L;
        boolean sawDigit = false;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') {
                if (sawDigit) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                throw new IOException("invalid tar octal field");
            }
            sawDigit = true;
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static String trimNul(byte[] buf, int offset, int length) {
        int end = offset;
        int limit = Math.min(buf.length, offset + length);
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String paxRecord(byte[] payload, String key) {
        String text = new String(payload, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            int space = line.indexOf(' ');
            if (space <= 0) {
                continue;
            }
            String kv = line.substring(space + 1);
            int eq = kv.indexOf('=');
            if (eq > 0 && key.equals(kv.substring(0, eq))) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    private static void readPayload(InputStream in, byte[] payload, byte[] scratch)
            throws IOException {
        int read = 0;
        while (read < payload.length) {
            int n = in.read(payload, read, payload.length - read);
            if (n < 0) {
                throw new IOException("truncated tar metadata payload");
            }
            read += n;
        }
        skipPayload(in, payload.length, scratch);
    }

    private static void copyPayload(
            InputStream in, OutputStream out, long size, byte[] buffer) throws IOException {
        long remaining = size;
        while (remaining > 0L) {
            int toRead = (int) Math.min(remaining, buffer.length);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
                throw new IOException("truncated tar file payload");
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static void skipPayload(InputStream in, long size, byte[] buffer) throws IOException {
        long padding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES;
        while (padding > 0L) {
            int toRead = (int) Math.min(padding, buffer.length);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
                throw new IOException("truncated tar padding");
            }
            padding -= n;
        }
    }

    private static void applyModeBestEffort(Path target, long mode) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400L) != 0L) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200L) != 0L) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100L) != 0L) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040L) != 0L) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020L) != 0L) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010L) != 0L) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004L) != 0L) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002L) != 0L) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001L) != 0L) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(target, perms);
        } catch (RuntimeException | IOException ignored) {
            // Non-POSIX or restricted filesystems fall back to File.setExecutable/Readable.
        }
    }

    private static void moveDirectoryIntoPlace(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staging, target);
        }
    }

    private static String sanitizeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generation must not be blank");
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (".".equals(sanitized) || "..".equals(sanitized)) {
            return "gen";
        }
        return sanitized;
    }

    private static String sanitizeHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return "orchestella-uml";
        }
        String cleaned = hostname.replaceAll("[^A-Za-z0-9-]", "");
        return cleaned.isEmpty() ? "orchestella-uml" : cleaned;
    }
}
