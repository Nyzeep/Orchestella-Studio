package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Pure-JVM contract for the streamed guest provisioning marker. */
public final class ProvisioningSignalTest {
    private static final String MARKER = "DROIDLAYER_PROVISIONING_COMPLETE version=1";
    private static final String COMPLETE_LINE =
            MARKER + " distro=ubuntu phase=rootfs runtime=pending";

    @Test
    public void detectsWholeMarkerFollowedByWhitespace() {
        ProvisioningSignal signal = new ProvisioningSignal();
        assertFalse(signal.detected());
        assertTrue(signal.accept(COMPLETE_LINE + "\n"));
        assertTrue(signal.detected());
    }

    @Test
    public void detectsMarkerSplitAtEverySingleBoundary() {
        for (int split = 0; split <= MARKER.length(); split++) {
            ProvisioningSignal signal = new ProvisioningSignal();
            boolean first = signal.accept(MARKER.substring(0, split));
            assertFalse("a boundary split must not complete the marker early", first);
            assertTrue("split at " + split, signal.accept(MARKER.substring(split) + "\n"));
            assertTrue(signal.detected());
        }
    }

    @Test
    public void detectsMarkerAcrossEverySegmentationIntoUpToFourChunks() {
        for (int chunks = 1; chunks <= 4; chunks++) {
            List<int[]> segmentations = new ArrayList<>();
            enumerateSegmentations(MARKER.length(), chunks, new int[chunks - 1], 0, 0, segmentations);
            for (int[] cuts : segmentations) {
                assertDetectedInChunks(MARKER, cuts);
            }
        }
    }

    @Test
    public void detectsMarkerAcrossEveryChunkCount() {
        // One character per chunk is the worst case: every boundary is exercised.
        assertDetectedInChunks(MARKER, everyBoundary(MARKER.length()));
        // Vary the number of chunks from 1 to the marker length with a long tail chunk.
        for (int chunks = 1; chunks <= MARKER.length(); chunks++) {
            int[] cuts = new int[chunks - 1];
            for (int i = 0; i < cuts.length; i++) {
                cuts[i] = i + 1;
            }
            assertDetectedInChunks(MARKER, cuts);
        }
    }

    @Test
    public void detectsMarkerAfterLineBreaksInTheSameChunk() {
        for (String lineBreak : new String[] {"\n", "\r", "\r\n"}) {
            ProvisioningSignal signal = new ProvisioningSignal();
            assertTrue(lineBreak, signal.accept("kernel boot log" + lineBreak + COMPLETE_LINE + "\n"));
        }
    }

    @Test
    public void detectsMarkerWhenTheLineBreakItselfIsSplitAcrossChunks() {
        ProvisioningSignal signal = new ProvisioningSignal();
        assertFalse(signal.accept("boot log\r"));
        assertFalse("the end of the chunk is not a delimiter yet", signal.accept("\n" + MARKER));
        assertTrue(signal.finish());
        assertTrue(signal.detected());
    }

    @Test
    public void detectsMarkerAtTheStartOfTheStream() {
        ProvisioningSignal signal = new ProvisioningSignal();
        assertTrue(signal.accept(COMPLETE_LINE));
    }

    @Test
    public void acceptsTabAndEndOfInputAsDelimiters() {
        ProvisioningSignal tabbed = new ProvisioningSignal();
        assertTrue(tabbed.accept(MARKER + "\tphase=rootfs\n"));

        ProvisioningSignal truncated = new ProvisioningSignal();
        assertFalse("the bare prefix needs an end-of-input confirmation", truncated.accept(MARKER));
        assertTrue(truncated.finish());
    }

    @Test
    public void rejectsWrongVersions() {
        for (String rejected : new String[] {
                "DROIDLAYER_PROVISIONING_COMPLETE version=10",
                "DROIDLAYER_PROVISIONING_COMPLETE version=2",
                "DROIDLAYER_PROVISIONING_COMPLETE version=0",
                "DROIDLAYER_PROVISIONING_COMPLETE version=",
                "DROIDLAYER_PROVISIONING_COMPLETE version=01",
                "DROIDLAYER_PROVISIONING_COMPLETE version=1.0",
        }) {
            ProvisioningSignal signal = new ProvisioningSignal();
            assertFalse(rejected, signal.accept(rejected + "\n"));
            assertFalse(rejected, signal.finish());
        }
    }

    @Test
    public void rejectsCaseVariants() {
        for (String rejected : new String[] {
                "droidlayer_provisioning_complete version=1",
                "Droidlayer_provisioning_complete version=1",
                "DROIDLAYER_PROVISIONING_COMPLETE VERSION=1",
                "DROIDLAYER_PROVISIONING_COMPLETE Version=1",
        }) {
            ProvisioningSignal signal = new ProvisioningSignal();
            assertFalse(rejected, signal.accept(rejected + "\n"));
        }
        ProvisioningSignal upperVersion = new ProvisioningSignal();
        assertFalse(upperVersion.accept("DROIDLAYER_PROVISIONING_COMPLETE VERSION=1\n"));
    }

    @Test
    public void rejectsSimilarTextInsideLargerTokens() {
        for (String rejected : new String[] {
                "XDROIDLAYER_PROVISIONING_COMPLETE version=1",
                "DROIDLAYER_PROVISIONING_COMPLETE_X version=1",
                "DROIDLAYER_PROVISIONING_COMPLETE version=1abc",
                "DROIDLAYER_PROVISIONING_COMPLETE version=1.5",
                "DROIDLAYER_PROVISIONING_COMPLETE version=123",
                "DROIDLAYER_PROVISIONING_ERROR stage=rootfs",
                "DROIDLAYER_PROVISIONING_COMPLETE_version=1",
        }) {
            ProvisioningSignal signal = new ProvisioningSignal();
            assertFalse(rejected, signal.accept(rejected + "\n"));
        }
    }

    @Test
    public void rejectsMarkerThatDoesNotStartALine() {
        for (String rejected : new String[] {
                "prefix " + MARKER,
                "boot: " + MARKER,
                "root@host:~# " + MARKER,
                "0" + MARKER,
        }) {
            ProvisioningSignal signal = new ProvisioningSignal();
            assertFalse(rejected, signal.accept(rejected + "\n"));
        }
    }

    @Test
    public void ignoresStreamsWithoutMarker() {
        ProvisioningSignal signal = new ProvisioningSignal();
        assertFalse(signal.accept("Booting Linux...\n[  OK  ] Started shell\n"));
        assertFalse(signal.finish());
        assertFalse(signal.detected());
    }

    @Test
    public void incompletePrefixAtEndOfInputIsNotAMarker() {
        ProvisioningSignal cut = new ProvisioningSignal();
        assertFalse(cut.accept("log\nDROIDLAYER_PROVISIONING_COMPLETE version="));
        assertFalse(cut.finish());
        assertFalse(cut.detected());

        ProvisioningSignal prefixOnly = new ProvisioningSignal();
        assertFalse(prefixOnly.accept("log\n" + MARKER));
        assertTrue(prefixOnly.finish());
    }

    @Test
    public void emitsAtMostOnceForDuplicateMarkers() {
        ProvisioningSignal signal = new ProvisioningSignal();
        assertTrue(signal.accept(MARKER + "\n"));
        assertFalse(signal.accept(MARKER + "\n"));
        assertFalse(signal.finish());
        assertTrue(signal.detected());
    }

    @Test
    public void detectsOneMarkerInALargeTranscriptWithoutUnboundedBuffering() {
        ProvisioningSignal signal = new ProvisioningSignal();
        String noise = "A".repeat(1_000_000);
        assertFalse(signal.accept(noise));
        assertTrue(signal.accept("\n" + COMPLETE_LINE));
    }

    private static void assertDetectedInChunks(String text, int[] cuts) {
        ProvisioningSignal signal = new ProvisioningSignal();
        int previous = 0;
        for (int i = 0; i < cuts.length; i++) {
            assertFalse("must not complete before the delimiter", signal.accept(text.substring(previous, cuts[i])));
            previous = cuts[i];
        }
        assertTrue("chunks=" + (cuts.length + 1), signal.accept(text.substring(previous) + "\n"));
        assertTrue(signal.detected());
        assertFalse("a second marker must not be emitted", signal.accept(text + "\n"));
    }

    private static int[] everyBoundary(int length) {
        int[] cuts = new int[length - 1];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = i + 1;
        }
        return cuts;
    }

    /** Enumerates every strictly increasing set of {@code count} cut positions. */
    private static void enumerateSegmentations(
            int length, int chunks, int[] cuts, int depth, int next, List<int[]> out) {
        if (depth == cuts.length) {
            out.add(cuts.clone());
            return;
        }
        for (int cut = next; cut < length; cut++) {
            cuts[depth] = cut;
            enumerateSegmentations(length, chunks, cuts, depth + 1, cut + 1, out);
        }
    }
}
