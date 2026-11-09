package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.UmlChannel.State;
import io.github.nyzeep.orchestella.UmlChannel.UmlChannelException;
import org.junit.Test;

/**
 * C lane: the {@link UmlChannel} seam's lifecycle contract — the D-1
 * (ACR-bound) face↔host compile channel abstraction, locked implementation-
 * first on the recording stub so any future real transport (the distcc-over-
 * TCP channel the device window injects once the ACR clears the bridge
 * change) can be held to the same rules. Locked: open-before-execute, no
 * reopen after close, idempotent close, fixed refusal details on every
 * refusal path, and the pass-through adapter onto the launcher's channel seam
 * with the fixed {@code uml-channel:} failure mapping. The marker channel
 * itself ({@code runtime/fastlane-distcc}) stays ACR-governed and untouched —
 * these tests run entirely above it.
 */
public final class UmlChannelContractTest {

    private static final FabricOrchestrator.WorkItem ITEM = new FabricOrchestrator.WorkItem() {
    };

    private static void assertDetail(UmlChannelException failure, String detail) {
        assertEquals(detail, failure.detail());
        assertEquals(detail, failure.getMessage());
    }

    // --- lifecycle ------------------------------------------------------------------

    @Test
    public void openMovesNewToOpenAndCounts() throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        assertSame(State.NEW, channel.state());

        channel.open();

        assertSame(State.OPEN, channel.state());
        assertEquals(1, channel.opens());
    }

    @Test
    public void executeBeforeOpenIsRefusedWithTheFixedDetailAndKeepsState() {
        UmlChannels.Stub channel = new UmlChannels.Stub();

        try {
            channel.execute(4, ITEM);
            fail("execute before open must refuse");
        } catch (UmlChannelException refusal) {
            assertDetail(refusal, UmlChannel.NOT_OPEN_DETAIL);
        }
        assertSame(State.NEW, channel.state());
        assertTrue(channel.executions().isEmpty());
    }

    @Test
    public void doubleOpenIsRefusedWithTheFixedDetail() throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();

        try {
            channel.open();
            fail("second open must refuse");
        } catch (UmlChannelException refusal) {
            assertDetail(refusal, UmlChannel.ALREADY_OPEN_DETAIL);
        }
        assertEquals(1, channel.opens());
    }

    @Test
    public void executeAfterCloseIsRefusedWithTheFixedDetail() throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();
        channel.close();

        try {
            channel.execute(2, ITEM);
            fail("execute after close must refuse");
        } catch (UmlChannelException refusal) {
            assertDetail(refusal, UmlChannel.CLOSED_DETAIL);
        }
    }

    @Test
    public void closedChannelDoesNotReopen() throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();
        channel.close();

        try {
            channel.open();
            fail("reopen must refuse");
        } catch (UmlChannelException refusal) {
            assertDetail(refusal, UmlChannel.CLOSED_DETAIL);
        }
        assertSame(State.CLOSED, channel.state());
        assertEquals(1, channel.opens());
    }

    @Test
    public void closeIsIdempotent() throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();

        channel.close();
        channel.close();

        assertSame(State.CLOSED, channel.state());
        assertEquals(2, channel.closes());
    }

    // --- execution ------------------------------------------------------------------

    @Test
    public void openChannelExecutesAndRecordsTheGrantedWidth()
            throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();

        channel.execute(4, ITEM);
        channel.execute(2, null);

        assertEquals(2, channel.executions().size());
        assertEquals("4:item", channel.executions().get(0));
        assertEquals("2:null", channel.executions().get(1));
        assertSame(State.OPEN, channel.state());
    }

    @Test
    public void programmedExecuteFailureCarriesTheFixedDetailAndTheCause()
            throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();
        IllegalStateException transport = new IllegalStateException("transport");
        channel.failingNextExecuteWith(transport);

        try {
            channel.execute(4, ITEM);
            fail("programmed failure must throw");
        } catch (UmlChannelException failure) {
            assertDetail(failure, UmlChannel.EXECUTE_FAILED_DETAIL);
            assertSame(transport, failure.getCause());
        }
        // A failure leaves the channel open — eviction is the orchestrator's call.
        assertSame(State.OPEN, channel.state());
        assertTrue(channel.executions().isEmpty());
    }

    // --- the fabric-slot adapter ----------------------------------------------------

    @Test
    public void adapterPassesExecutionsThroughToTheChannel()
            throws UmlChannelException, FabricOrchestrator.FaceException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();
        UmlFaceLauncher.Channel faceChannel = UmlChannels.asFaceChannel(channel);

        faceChannel.execute(4, ITEM);

        assertEquals(1, channel.executions().size());
        assertEquals("4:item", channel.executions().get(0));
    }

    @Test
    public void adapterMapsChannelRefusalOntoTheFaceExceptionContract() {
        UmlChannels.Stub channel = new UmlChannels.Stub(); // never opened
        UmlFaceLauncher.Channel faceChannel = UmlChannels.asFaceChannel(channel);

        try {
            faceChannel.execute(4, ITEM);
            fail("unopened channel must fail the face dispatch");
        } catch (FabricOrchestrator.FaceException mapped) {
            assertEquals("uml-channel:" + UmlChannel.NOT_OPEN_DETAIL, mapped.getMessage());
            assertTrue(mapped.getCause() instanceof UmlChannelException);
            assertEquals(UmlChannel.NOT_OPEN_DETAIL,
                    ((UmlChannelException) mapped.getCause()).detail());
        }
    }

    @Test
    public void adapterMapsExecuteFailureTheSameWay()
            throws UmlChannelException {
        UmlChannels.Stub channel = new UmlChannels.Stub();
        channel.open();
        channel.failingNextExecuteWith(new IllegalStateException("guest dead"));
        UmlFaceLauncher.Channel faceChannel = UmlChannels.asFaceChannel(channel);

        try {
            faceChannel.execute(2, ITEM);
            fail("programmed failure must fail the face dispatch");
        } catch (FabricOrchestrator.FaceException mapped) {
            assertEquals("uml-channel:" + UmlChannel.EXECUTE_FAILED_DETAIL, mapped.getMessage());
        }
    }

    @Test
    public void factoryStubIsTheRecordingImplementation() throws UmlChannelException {
        UmlChannel channel = UmlChannels.stub();
        assertSame(UmlChannels.Stub.class, channel.getClass());

        channel.open();
        channel.close();

        assertSame(State.CLOSED, channel.state());
    }
}
