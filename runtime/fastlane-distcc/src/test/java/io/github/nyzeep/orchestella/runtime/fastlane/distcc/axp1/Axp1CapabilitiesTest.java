package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Capability TLV codec, including id 6 (COMPILER_VERSION), which PVM-03 added
 * so the host can read the payload compiler identity in the same handshake.
 *
 * <p>The compatibility rule under test: a decoder must keep working when it
 * meets an id it does not know, and must keep it visible as an id without
 * surfacing the bytes.
 */
public final class Axp1CapabilitiesTest {

    @Test
    public void compilerVersionRoundTrips() throws Exception {
        Axp1Capabilities local = Axp1Capabilities.builder()
                .distcc(true)
                .compilerTriple("aarch64-unknown-linux-android")
                .compilerVersion("clang 22.1.8")
                .build();

        Axp1Capabilities decoded = Axp1Capabilities.decode(local.encode());

        assertEquals("clang 22.1.8", decoded.compilerVersion());
        assertEquals("aarch64-unknown-linux-android", decoded.compilerTriple());
        assertTrue(decoded.distcc());
        assertEquals(4, entryCount(local.encode()));
    }

    @Test
    public void compilerVersionIsOmittedWhenEmpty() {
        Axp1Capabilities bare = Axp1Capabilities.builder().distcc(true).build();

        assertEquals(2, entryCount(bare.encode()));
        assertEquals("", bare.compilerVersion());
    }

    @Test
    public void unknownIdsAreStillRetained() throws Exception {
        // A peer that only speaks ids above 5 must not break the decoder.
        byte[] block = tlvBlockIgnoringVersion();

        Axp1Capabilities decoded = Axp1Capabilities.decode(block);

        assertTrue(decoded.unknownIds().contains(99));
        assertEquals("", decoded.compilerVersion());
    }

    @Test
    public void compilerVersionObeysTheStringCeiling() {
        byte[] tooLong = new byte[129];
        java.util.Arrays.fill(tooLong, (byte) 'v');

        assertThrows(Axp1Exception.class, () -> Axp1Capabilities.decode(
                rawBlock(Axp1Capabilities.ID_COMPILER_VERSION, tooLong)));
    }

    @Test
    public void negotiationPrefersThePeerVersion() {
        Axp1Capabilities local = Axp1Capabilities.builder()
                .distcc(true)
                .compilerVersion("clang 22.1.8")
                .build();
        Axp1Capabilities peer = Axp1Capabilities.builder()
                .distcc(true)
                .compilerVersion("clang 22.1.3")
                .build();

        assertEquals("clang 22.1.3",
                Axp1Capabilities.negotiate(local, peer).compilerVersion());
        assertEquals("clang 22.1.8",
                Axp1Capabilities.negotiate(local, Axp1Capabilities.builder().build())
                        .compilerVersion());
    }

    @Test
    public void aDistccFalsePeerNeverYieldsDistcc() {
        // Guard against a "merge by OR" regression: the host factory refuses to
        // open a channel unless the negotiated value is true.
        Axp1Capabilities local = Axp1Capabilities.builder().distcc(true).build();
        Axp1Capabilities peer = Axp1Capabilities.builder().distcc(false).build();

        assertFalse(Axp1Capabilities.negotiate(local, peer).distcc());
    }

    private static int entryCount(byte[] block) {
        return ((block[0] & 0xff) << 8) | (block[1] & 0xff);
    }

    /** count=2, id 1 distcc=1, id 99 with a 3-byte value. */
    private static byte[] tlvBlockIgnoringVersion() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(2);
        out.writeByte(Axp1Capabilities.ID_DISTCC);
        out.writeShort(1);
        out.writeByte(1);
        out.writeByte(99);
        out.writeShort(3);
        out.write(new byte[] {1, 2, 3});
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] rawBlock(int id, byte[] value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(1);
            out.writeByte(id);
            out.writeShort(value.length);
            out.write(value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    public void versionBytesAreUtf8() throws Exception {
        String version = "clang 22.1.8 (构建 2026)";
        Axp1Capabilities decoded = Axp1Capabilities.decode(
                Axp1Capabilities.builder().compilerVersion(version).build().encode());

        assertEquals(version, decoded.compilerVersion());
        assertTrue(version.getBytes(StandardCharsets.UTF_8).length > version.length() - 5);
    }
}
