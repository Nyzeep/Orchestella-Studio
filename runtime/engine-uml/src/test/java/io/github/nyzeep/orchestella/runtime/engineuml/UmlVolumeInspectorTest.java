package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class UmlVolumeInspectorTest {
    @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void classifiesAbsentZeroByteAndCreationSignatureAsReadyToProvision() throws Exception {
        Path dir = tempFolder.newFolder("inspect").toPath();

        UmlVolumeInspector.VolumeInspection absent =
                UmlVolumeInspector.inspect(dir.resolve("missing.raw"));
        assertEquals(UmlVolumeInspector.VolumeState.ABSENT, absent.state());

        Path zeroByte = dir.resolve("zero.raw");
        Files.createFile(zeroByte);
        assertEquals(
                UmlVolumeInspector.VolumeState.EMPTY_OR_PLACEHOLDER,
                UmlVolumeInspector.inspect(zeroByte).state());

        Path placeholder = dir.resolve("placeholder.raw");
        byte[] bytes = new byte[4096];
        byte[] sig = "ORCHESTELLA_EMPTY_ROOT_VOLUME_V2\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sig, 0, bytes, 0, sig.length);
        Files.write(placeholder, bytes);
        assertEquals(
                UmlVolumeInspector.VolumeState.EMPTY_OR_PLACEHOLDER,
                UmlVolumeInspector.inspect(placeholder).state());
    }

    @Test
    public void detectsExt4SuperblockMagicAtOffset0x438() throws Exception {
        Path dir = tempFolder.newFolder("ext4").toPath();
        Path ext4Image = dir.resolve("root.raw");
        byte[] image = new byte[4096];
        image[0x438] = 0x53;
        image[0x439] = (byte) 0xEF;
        Files.write(ext4Image, image);

        UmlVolumeInspector.VolumeInspection inspection = UmlVolumeInspector.inspect(ext4Image);
        assertEquals(UmlVolumeInspector.VolumeState.EXT4_READY, inspection.state());
        assertEquals(4096L, inspection.sizeBytes());
        UmlVolumeInspector.requireValidExt4Superblock(ext4Image);
    }

    @Test
    public void rejectsFormatConflictsAndUnrecognizedNonEmptyData() throws Exception {
        Path dir = tempFolder.newFolder("conflicts").toPath();

        Path subDir = Files.createDirectory(dir.resolve("dir.raw"));
        assertEquals(
                UmlVolumeInspector.VolumeState.FORMAT_CONFLICT,
                UmlVolumeInspector.inspect(subDir).state());

        Path qcow2 = dir.resolve("qcow2.raw");
        Files.write(qcow2, new byte[] {0x51, 0x46, 0x49, (byte) 0xFB, 0x00, 0x00});
        assertEquals(
                UmlVolumeInspector.VolumeState.FORMAT_CONFLICT,
                UmlVolumeInspector.inspect(qcow2).state());

        Path unknown = dir.resolve("unknown.raw");
        byte[] payload = new byte[4096];
        payload[0] = 'U';
        payload[1] = 'S';
        payload[2] = 'E';
        payload[3] = 'R';
        Files.write(unknown, payload);
        assertEquals(
                UmlVolumeInspector.VolumeState.UNRECOGNIZED_NON_EMPTY,
                UmlVolumeInspector.inspect(unknown).state());
        assertThrows(IOException.class, () -> UmlVolumeInspector.requireValidExt4Superblock(unknown));
    }

    @Test
    public void rejectsTooSmallImageEvenWithMagicAndRequiresOffset0x438() throws Exception {
        Path dir = tempFolder.newFolder("small").toPath();
        Path small = dir.resolve("small.raw");
        Files.write(small, new byte[1082]);
        IOException error =
                assertThrows(IOException.class, () -> UmlVolumeInspector.requireValidExt4Superblock(small));
        assertTrue(error.getMessage().contains("ext4"));
    }
}
