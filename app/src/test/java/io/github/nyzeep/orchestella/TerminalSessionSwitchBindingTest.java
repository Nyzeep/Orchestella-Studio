package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import io.github.nyzeep.orchestella.terminal.MultiTerminalSessionManager;
import io.github.nyzeep.orchestella.terminal.TerminalSessionInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * W1-05 (T-14): pins the tab-switch binding contract of
 * {@link TerminalActivity#bindSessionScreenForSwitch} — the piece of the
 * single-stream ownership fix that keeps a secondary pane live. The old design
 * bound a private mirror that nobody parsed anymore, which froze the pane after
 * a switch and double-parsed the primary model on the UI thread.
 */
public final class TerminalSessionSwitchBindingTest {

    private PersistentTerminalSessionPool pool;
    private MultiTerminalSessionManager multiManager;
    private PersistentTerminalSessionPool.SessionHolder holder;

    @Before
    public void setUp() {
        pool = new PersistentTerminalSessionPool();
        multiManager = new MultiTerminalSessionManager();
    }

    @After
    public void tearDown() {
        if (holder != null) {
            holder.close();
        }
        pool.clear();
    }

    private PersistentTerminalSessionPool.SessionHolder newHolder(String instanceId) throws IOException {
        holder = pool.register(instanceId, new EmptyExecutionSession());
        return holder;
    }

    @Test
    public void switchToSecondaryTabBindsTheCanonicalModelSoThePaneStaysLive() throws Exception {
        PersistentTerminalSessionPool.SessionHolder local = newHolder("switch-vm");
        String instanceId = "switch-vm";
        TerminalSessionInstance primary = multiManager.createSession(instanceId, "终端 1");
        TerminalSessionInstance secondary = multiManager.createSession(instanceId, "终端 2");

        io.github.nyzeep.orchestella.terminal.TerminalScreenModel canonical = local.getScreenModel();
        io.github.nyzeep.orchestella.terminal.TerminalScreenModel bound =
                TerminalActivity.bindSessionScreenForSwitch(local, secondary, primary);

        assertSame("the secondary tab must render the canonical model", canonical, bound);
        assertSame("the switch must bind the session to the canonical model",
                canonical, secondary.getScreenModel());

        // What the pump thread does while the secondary tab is on screen: append
        // to the canonical model only. The bound pane must reflect it immediately —
        // no freeze, no separate feed path.
        canonical.append("live-after-switch\r\n");
        assertTrue("the bound secondary pane must stay live after the switch",
                secondary.getScreenModel().displaySnapshot().text().contains("live-after-switch"));
    }

    @Test
    public void switchToPrimaryTabReturnsTheCanonicalModelForTheReattachPath() throws Exception {
        PersistentTerminalSessionPool.SessionHolder local = newHolder("primary-switch-vm");
        String instanceId = "primary-switch-vm";
        TerminalSessionInstance primary = multiManager.createSession(instanceId, "终端 1");

        assertSame("the primary tab hands the canonical model to the re-attach path",
                local.getScreenModel(),
                TerminalActivity.bindSessionScreenForSwitch(local, primary, primary));
    }

    @Test
    public void switchWithoutLiveConsoleFallsBackToTranscriptReplay() throws Exception {
        TerminalSessionInstance primary = multiManager.createSession("offline-vm", "终端 1");
        TerminalSessionInstance secondary = multiManager.createSession("offline-vm", "终端 2");
        io.github.nyzeep.orchestella.terminal.TerminalScreenModel privateModel = secondary.getScreenModel();

        assertNull("no live console must keep the transcript-replay path",
                TerminalActivity.bindSessionScreenForSwitch(null, secondary, primary));
        assertSame("the session keeps its private model for the offline render state",
                privateModel, secondary.getScreenModel());
    }

    /** Minimal live-pipe session: both streams stay open, nothing ever flows. */
    private static final class EmptyExecutionSession implements ExecutionSession {
        private final OutputStream input = new ByteArrayOutputStream();

        @Override public int waitFor() { return 0; }
        @Override public OutputStream terminalInput() { return input; }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void close() { }
    }
}
