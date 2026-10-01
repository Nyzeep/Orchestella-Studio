package io.github.nyzeep.droidlayer.runtime.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Strategy-neutral shared directory contract and specification models.
 *
 * <p>Implements ADR-0009 ("控制面/数据面分离：文件共享走独立数据通道") and
 * ADR-0010 ("安全边界：暴露面白名单制：只读共享 Downloads"):
 * <ul>
 *   <li>Data plane channel is strictly separated from the Bridge control plane.</li>
 *   <li>Only read-only mode is permitted; any write operation is rejected by physical policy.</li>
 *   <li>Guest-side default mount point is {@value #DEFAULT_GUEST_MOUNT_POINT}.</li>
 *   <li>Strategy-neutral specification holds semantic directory and mount information;
 *       all hypervisor-specific CLI formatting (e.g. QEMU -fsdev and -device) is isolated
 *       in the engine layer.</li>
 * </ul>
 */
public final class SharedDirectoryContract {
    public static final String DEFAULT_FSDEV_ID = "fsdev0";
    public static final String DEFAULT_MOUNT_TAG = "downloads";
    public static final String DEFAULT_GUEST_MOUNT_POINT = "/mnt/downloads";
    public static final String ALTERNATIVE_GUEST_MOUNT_POINT = "/media/downloads";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[;&|`$\\n\\r]");

    private SharedDirectoryContract() {}

    /**
     * Supported virtual 9p filesystem interface device models (strategy-neutral).
     */
    public enum DeviceType {
        VIRTIO_9P_PCI,
        VIRTIO_9P_DEVICE
    }

    /**
     * Immutable specification for guest-shared directory configuration.
     */
    public static final class SharedDirectorySpec {
        private final String fsdevId;
        private final String hostPath;
        private final String mountTag;
        private final String guestMountPoint;
        private final MountOptions mountOptions;
        private final DeviceType deviceType;

        private SharedDirectorySpec(
                String fsdevId,
                String hostPath,
                String mountTag,
                String guestMountPoint,
                MountOptions mountOptions,
                DeviceType deviceType) {
            this.fsdevId = validateIdentifier(fsdevId, "fsdevId");
            this.hostPath = validateHostPath(hostPath);
            this.mountTag = validateIdentifier(mountTag, "mountTag");
            this.guestMountPoint = validateGuestMountPoint(guestMountPoint);
            this.mountOptions = Objects.requireNonNull(mountOptions, "mountOptions");
            if (!this.mountOptions.isReadOnly()) {
                throw new IllegalArgumentException(
                        "Shared directory must be read-only per ADR-0010 security boundary");
            }
            this.deviceType = Objects.requireNonNull(deviceType, "deviceType");
        }

        public static SharedDirectorySpec defaultDownloads(String hostPath) {
            return new Builder().hostPath(hostPath).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public String fsdevId() {
            return fsdevId;
        }

        public String hostPath() {
            return hostPath;
        }

        public String mountTag() {
            return mountTag;
        }

        public String guestMountPoint() {
            return guestMountPoint;
        }

        public MountOptions mountOptions() {
            return mountOptions;
        }

        public DeviceType deviceType() {
            return deviceType;
        }

        /**
         * Formats the guest-side mount command string.
         * e.g. {@code mount -t 9p -o trans=virtio,version=9p2000.L,ro downloads /mnt/downloads}
         */
        public String toGuestMountCommand() {
            return "mount -t 9p -o " + mountOptions.toGuestMountOptionsString()
                    + " " + mountTag + " " + guestMountPoint;
        }

        /**
         * Formats the guest-side /etc/fstab entry string.
         */
        public String toGuestFstabEntry() {
            return mountTag + "\t" + guestMountPoint + "\t9p\t"
                    + mountOptions.toGuestMountOptionsString() + ",nofail\t0\t0";
        }

        /**
         * Creates a read-only filesystem view modeling Guest access to the shared directory.
         */
        public ReadOnlyView createReadOnlyView() {
            return new DefaultReadOnlyView(Paths.get(hostPath));
        }

        public static final class Builder {
            private String fsdevId = DEFAULT_FSDEV_ID;
            private String hostPath;
            private String mountTag = DEFAULT_MOUNT_TAG;
            private String guestMountPoint = DEFAULT_GUEST_MOUNT_POINT;
            private MountOptions mountOptions = MountOptions.readOnly();
            private DeviceType deviceType = DeviceType.VIRTIO_9P_PCI;

            public Builder fsdevId(String fsdevId) {
                this.fsdevId = fsdevId;
                return this;
            }

            public Builder hostPath(String hostPath) {
                this.hostPath = hostPath;
                return this;
            }

            public Builder mountTag(String mountTag) {
                this.mountTag = mountTag;
                return this;
            }

            public Builder guestMountPoint(String guestMountPoint) {
                this.guestMountPoint = guestMountPoint;
                return this;
            }

            public Builder mountOptions(MountOptions mountOptions) {
                this.mountOptions = mountOptions;
                return this;
            }

            public Builder deviceType(DeviceType deviceType) {
                this.deviceType = deviceType;
                return this;
            }

            public SharedDirectorySpec build() {
                return new SharedDirectorySpec(
                        fsdevId, hostPath, mountTag, guestMountPoint, mountOptions, deviceType);
            }
        }
    }

    /**
     * Interface defining read-only operations accessible from the Guest perspective,
     * ensuring read operations succeed and write mutations are physically rejected with
     * {@link ReadOnlyFileSystemException}.
     */
    public interface ReadOnlyView {
        List<String> listRelativePaths() throws IOException;
        String readString(String relativePath) throws IOException;
        byte[] readBytes(String relativePath) throws IOException;
        InputStream openStream(String relativePath) throws IOException;

        void createFile(String relativePath, String content);
        void writeBytes(String relativePath, byte[] content);
        void delete(String relativePath);
        void createDirectory(String relativePath);
    }

    private static final class DefaultReadOnlyView implements ReadOnlyView {
        private final Path root;

        DefaultReadOnlyView(Path root) {
            this.root = Objects.requireNonNull(root, "root");
        }

        private Path resolveSafe(String relativePath) {
            Objects.requireNonNull(relativePath, "relativePath");
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root)) {
                throw new SecurityException("Path escapes shared directory boundary: " + relativePath);
            }
            return target;
        }

        @Override
        public List<String> listRelativePaths() throws IOException {
            if (!Files.exists(root)) {
                return Collections.emptyList();
            }
            List<String> results = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> !p.equals(root)).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    results.add(rel);
                });
            }
            Collections.sort(results);
            return Collections.unmodifiableList(results);
        }

        @Override
        public String readString(String relativePath) throws IOException {
            Path target = resolveSafe(relativePath);
            return Files.readString(target, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] readBytes(String relativePath) throws IOException {
            Path target = resolveSafe(relativePath);
            return Files.readAllBytes(target);
        }

        @Override
        public InputStream openStream(String relativePath) throws IOException {
            Path target = resolveSafe(relativePath);
            return Files.newInputStream(target);
        }

        @Override
        public void createFile(String relativePath, String content) {
            throw new ReadOnlyFileSystemException();
        }

        @Override
        public void writeBytes(String relativePath, byte[] content) {
            throw new ReadOnlyFileSystemException();
        }

        @Override
        public void delete(String relativePath) {
            throw new ReadOnlyFileSystemException();
        }

        @Override
        public void createDirectory(String relativePath) {
            throw new ReadOnlyFileSystemException();
        }
    }

    private static String validateIdentifier(String id, String fieldName) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }
        if (!IDENTIFIER_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must match [A-Za-z0-9_-]+: '" + id + "'");
        }
        return id;
    }

    private static String validateHostPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("hostPath cannot be null or empty");
        }
        if (SHELL_METACHARACTERS.matcher(path).find()) {
            throw new IllegalArgumentException("hostPath contains illegal shell characters: " + path);
        }
        boolean isPosixAbsolute = path.startsWith("/");
        boolean isWindowsAbsolute = path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && (path.charAt(2) == '/' || path.charAt(2) == '\\');
        if (!isPosixAbsolute && !isWindowsAbsolute) {
            throw new IllegalArgumentException("hostPath must be an absolute path: " + path);
        }
        return path;
    }

    private static String validateGuestMountPoint(String point) {
        if (point == null || point.trim().isEmpty()) {
            throw new IllegalArgumentException("guestMountPoint cannot be null or empty");
        }
        if (!point.startsWith("/")) {
            throw new IllegalArgumentException("guestMountPoint must start with '/': " + point);
        }
        return point;
    }
}
