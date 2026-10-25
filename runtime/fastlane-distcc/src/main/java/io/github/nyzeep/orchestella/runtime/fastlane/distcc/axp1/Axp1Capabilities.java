package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AXP1 capability set. Known TLVs are typed; unknown ids are ignored for
 * behaviour but retained as ids so a remote can be diagnosed without logging
 * their values.
 */
public final class Axp1Capabilities {
    public static final int ID_DISTCC = 1;
    public static final int ID_MAX_FRAME = 2;
    public static final int ID_COMPILER_TRIPLE = 3;
    public static final int ID_ARCH = 4;
    public static final int ID_FEATURES = 5;
    /**
     * Compiler version string (UTF-8). Not part of the original 1..5 set: it was
     * added for PVM-03 so the host can decide {@code payloadCompilerVersion}
     * without a second round trip. Older decoders ignore it as an unknown id,
     * which is exactly the compatibility rule this class already implements.
     */
    public static final int ID_COMPILER_VERSION = 6;
    private static final int MAX_FEATURES = 32;
    private static final int MAX_STRING_BYTES = 128;
    private static final int MAX_ENTRIES = 64;

    private final boolean distcc;
    private final int maxFrame;
    private final String compilerTriple;
    private final String compilerVersion;
    private final String arch;
    private final List<String> features;
    private final Map<Integer, byte[]> unknown;

    private Axp1Capabilities(Builder builder) {
        this.distcc = builder.distcc;
        this.maxFrame = builder.maxFrame;
        this.compilerTriple = builder.compilerTriple;
        this.compilerVersion = builder.compilerVersion;
        this.arch = builder.arch;
        this.features = List.copyOf(builder.features);
        Map<Integer, byte[]> copy = new LinkedHashMap<>();
        builder.unknown.forEach((id, value) -> copy.put(id, value.clone()));
        this.unknown = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean distcc() {
        return distcc;
    }

    public int maxFrame() {
        return maxFrame;
    }

    public String compilerTriple() {
        return compilerTriple;
    }

    public String arch() {
        return arch;
    }

    /** Compiler version reported by the payload; empty when it omits id 6. */
    public String compilerVersion() {
        return compilerVersion;
    }

    public List<String> features() {
        return features;
    }

    /** Unknown capability ids only; their bytes are never surfaced or logged. */
    public Set<Integer> unknownIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(unknown.keySet()));
    }

    /** Encodes the TLV block that follows the role and nonce in HELLO. */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            int count = 2;
            if (!compilerTriple.isEmpty()) count++;
            if (!compilerVersion.isEmpty()) count++;
            if (!arch.isEmpty()) count++;
            if (!features.isEmpty()) count++;
            out.writeShort(count);
            writeTlv(out, ID_DISTCC, new byte[] {(byte) (distcc ? 1 : 0)});
            writeTlv(out, ID_MAX_FRAME, intBytes(maxFrame));
            if (!compilerTriple.isEmpty()) {
                writeTlv(out, ID_COMPILER_TRIPLE, utf8(compilerTriple));
            }
            if (!compilerVersion.isEmpty()) {
                writeTlv(out, ID_COMPILER_VERSION, utf8(compilerVersion));
            }
            if (!arch.isEmpty()) {
                writeTlv(out, ID_ARCH, utf8(arch));
            }
            if (!features.isEmpty()) {
                ByteArrayOutputStream featureBytes = new ByteArrayOutputStream();
                DataOutputStream featureOut = new DataOutputStream(featureBytes);
                featureOut.writeShort(features.size());
                for (String feature : features) {
                    byte[] encoded = utf8(feature);
                    featureOut.writeShort(encoded.length);
                    featureOut.write(encoded);
                }
                featureOut.flush();
                writeTlv(out, ID_FEATURES, featureBytes.toByteArray());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory capability encoding failed", impossible);
        }
    }

    public static Axp1Capabilities decode(byte[] payload) throws Axp1Exception {
        Builder builder = builder();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readUnsignedShort();
            if (count > MAX_ENTRIES) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "too many capability entries");
            }
            for (int index = 0; index < count; index++) {
                int id = in.readUnsignedByte();
                int length = in.readUnsignedShort();
                if (length > payload.length) {
                    throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "capability length out of range");
                }
                byte[] value = new byte[length];
                in.readFully(value);
                switch (id) {
                    case ID_DISTCC -> {
                        if (length != 1) throw protocol("distcc capability has the wrong length");
                        builder.distcc(value[0] != 0);
                    }
                    case ID_MAX_FRAME -> {
                        if (length != 4) throw protocol("max_frame capability has the wrong length");
                        int frame = ((value[0] & 0xff) << 24) | ((value[1] & 0xff) << 16)
                                | ((value[2] & 0xff) << 8) | (value[3] & 0xff);
                        if (frame < 1 || frame > Axp1Codec.ABSOLUTE_MAX_FRAME) {
                            throw protocol("max_frame outside the AXP1 range");
                        }
                        builder.maxFrame(frame);
                    }
                    case ID_COMPILER_TRIPLE -> builder.compilerTriple(decodeString(value));
                    case ID_COMPILER_VERSION -> builder.compilerVersion(decodeString(value));
                    case ID_ARCH -> builder.arch(decodeString(value));
                    case ID_FEATURES -> builder.features(decodeFeatures(value));
                    default -> builder.unknown(id, value);
                }
            }
        } catch (IOException failure) {
            throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "truncated capabilities", failure);
        }
        return builder.build();
    }

    /**
     * Intersects peer capabilities. The peer's triple/arch win when present,
     * the smaller frame limit wins, and features must exist on both sides.
     */
    public static Axp1Capabilities negotiate(Axp1Capabilities local, Axp1Capabilities peer) {
        Builder builder = builder()
                .distcc(local.distcc && peer.distcc)
                .maxFrame(Math.min(local.maxFrame, peer.maxFrame))
                .compilerTriple(peer.compilerTriple.isEmpty()
                        ? local.compilerTriple : peer.compilerTriple)
                .compilerVersion(peer.compilerVersion.isEmpty()
                        ? local.compilerVersion : peer.compilerVersion)
                .arch(peer.arch.isEmpty() ? local.arch : peer.arch);
        for (String feature : local.features) {
            if (peer.features.contains(feature)) {
                builder.feature(feature);
            }
        }
        local.unknown.forEach(builder::unknown);
        peer.unknown.forEach(builder::unknown);
        return builder.build();
    }

    private static Axp1Exception protocol(String message) {
        return new Axp1Exception(Axp1Error.PROTOCOL_ERROR, message);
    }

    private static void writeTlv(DataOutputStream out, int id, byte[] value) throws IOException {
        out.writeByte(id);
        out.writeShort(value.length);
        out.write(value);
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeString(byte[] value) throws Axp1Exception {
        if (value.length > MAX_STRING_BYTES) {
            throw protocol("capability string is too long");
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    private static List<String> decodeFeatures(byte[] value) throws Axp1Exception {
        List<String> result = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(value))) {
            int count = in.readUnsignedShort();
            if (count > MAX_FEATURES) {
                throw protocol("too many features");
            }
            for (int index = 0; index < count; index++) {
                int length = in.readUnsignedShort();
                if (length > MAX_STRING_BYTES || length > value.length) {
                    throw protocol("feature length out of range");
                }
                byte[] encoded = new byte[length];
                in.readFully(encoded);
                result.add(new String(encoded, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "truncated features", failure);
        }
        return result;
    }

    /** Builder; max frame defaults to one MiB. */
    public static final class Builder {
        private boolean distcc;
        private int maxFrame = Axp1Codec.DEFAULT_MAX_FRAME;
        private String compilerTriple = "";
        private String compilerVersion = "";
        private String arch = "";
        private final List<String> features = new ArrayList<>();
        private final Map<Integer, byte[]> unknown = new LinkedHashMap<>();

        public Builder distcc(boolean value) {
            this.distcc = value;
            return this;
        }

        public Builder maxFrame(int value) {
            if (value < 1 || value > Axp1Codec.ABSOLUTE_MAX_FRAME) {
                throw new IllegalArgumentException("maxFrame outside the AXP1 range");
            }
            this.maxFrame = value;
            return this;
        }

        public Builder compilerTriple(String value) {
            this.compilerTriple = Objects.requireNonNull(value, "compilerTriple");
            return this;
        }

        public Builder compilerVersion(String value) {
            this.compilerVersion = Objects.requireNonNull(value, "compilerVersion");
            return this;
        }

        public Builder arch(String value) {
            this.arch = Objects.requireNonNull(value, "arch");
            return this;
        }

        public Builder feature(String value) {
            features.add(Objects.requireNonNull(value, "feature"));
            return this;
        }

        public Builder features(List<String> values) {
            features.clear();
            values.forEach(this::feature);
            return this;
        }

        public Builder unknown(int id, byte[] value) {
            unknown.put(id, value.clone());
            return this;
        }

        public Axp1Capabilities build() {
            return new Axp1Capabilities(this);
        }
    }
}
