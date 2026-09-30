package io.github.nyzeep.droidlayer.runtime.distro;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, pinned description of one official rootfs input.
 *
 * <p>The module verifies a caller-supplied stream and returns metadata only after the expected byte
 * count and SHA-256 match. It performs no download, storage, provisioning, or execution.
 */
public final class DistroManifest {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String id;
    private final String version;
    private final String architecture;
    private final URI source;
    private final String rootfsFileName;
    private final String sha256;
    private final long sizeBytes;

    public DistroManifest(
            String id,
            String version,
            String architecture,
            String source,
            String rootfsFileName,
            String sha256,
            long sizeBytes) {
        this.id = requirePattern("id", id, ID);
        this.version = requireNonBlank("version", version);
        this.architecture = requireArchitecture(architecture);
        this.rootfsFileName = requireFileName(rootfsFileName);
        this.source = requireFixedHttpsSource(source, this.rootfsFileName);
        this.sha256 = requirePattern("sha256", sha256, SHA256);
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        this.sizeBytes = sizeBytes;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public String architecture() {
        return architecture;
    }

    public URI source() {
        return source;
    }

    public String rootfsFileName() {
        return rootfsFileName;
    }

    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /**
     * Verifies a complete rootfs input stream before a caller may mark it available for provisioning.
     *
     * @param input immutable input stream supplied by the caller
     * @param declaredSize fixed size from the source response or already-staged file
     * @return a value proving this manifest's identity, SHA-256, and size all matched
     * @throws InputRejectedException for every malformed, truncated, oversized, or altered input
     */
    public void requireExpectedSize(long declaredSize) throws InputRejectedException {
        if (declaredSize != sizeBytes) {
            throw new InputRejectedException("rootfs-size-mismatch");
        }
    }

    public VerifiedInput requireVerified(InputStream input, long declaredSize) throws IOException {
        Objects.requireNonNull(input, "input");
        requireExpectedSize(declaredSize);

        MessageDigest digest = sha256Digest();
        long observedSize = 0L;
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                continue;
            }
            if (count > sizeBytes - observedSize) {
                throw new InputRejectedException("rootfs-size-mismatch");
            }
            observedSize += count;
            digest.update(buffer, 0, count);
        }
        if (observedSize != sizeBytes) {
            throw new InputRejectedException("rootfs-size-mismatch");
        }

        String observedSha256 = toLowerHex(digest.digest());
        if (!MessageDigest.isEqual(
                sha256.getBytes(StandardCharsets.US_ASCII),
                observedSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new InputRejectedException("rootfs-checksum-mismatch");
        }
        return new VerifiedInput(id, observedSha256, observedSize);
    }

    /** Fail-closed exception whose reason may be shown as a stable, non-secret UI diagnostic. */
    public static final class InputRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String reason;

        private InputRejectedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Proof object returned only after this manifest's complete input verification succeeds. */
    public static final class VerifiedInput {
        private final String distroId;
        private final String sha256;
        private final long sizeBytes;

        private VerifiedInput(String distroId, String sha256, long sizeBytes) {
            this.distroId = distroId;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }

        public String distroId() {
            return distroId;
        }

        public String sha256() {
            return sha256;
        }

        public long sizeBytes() {
            return sizeBytes;
        }
    }

    private static URI requireFixedHttpsSource(String value, String expectedFileName) {
        requireNonBlank("source", value);
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPath() == null
                    || !uri.getPath().endsWith("/" + expectedFileName)) {
                throw new IllegalArgumentException("source must be a fixed HTTPS rootfs URL");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid source URL", exception);
        }
    }

    private static String requireArchitecture(String value) {
        if (!"aarch64".equals(value)) {
            throw new IllegalArgumentException("architecture must be aarch64");
        }
        return value;
    }

    private static String requireFileName(String value) {
        requireNonBlank("rootfsFileName", value);
        if (value.contains("/") || value.contains("\\") || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("rootfsFileName must be one ordinary file name");
        }
        return value;
    }

    private static String requirePattern(String field, String value, Pattern pattern) {
        requireNonBlank(field, value);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
