package io.github.nyzeep.orchestella;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Gate 1 (ACR 2026-11-13 方案 A, fabric-route doc D-1): the shared
 * {@link FabricOrchestrator.WorkItem} shape that carries one admitted compile
 * job's byte pipe. The fabric routes work; the byte pipe is what the winning
 * face's worker actually executes.
 *
 * <p><b>Two faces, one shape.</b> The orchestrator's face workers consume this
 * item identically: they pump {@link #request()} (the job's distcc request
 * bytes, client → face) through their own channel to the face's distccd and
 * pump the answer back into {@link #response()} (face → client) — the
 * HOST_NATIVE face through its pool-lease AXP1 channel, the UML face through
 * the bridge's UML-TCP lane ({@code UmlChannels.overTcpLane}). Neither face
 * parses the bytes; the pump ends at EOF on either side, exactly the bridge's
 * per-connection semantics.
 *
 * <p><b>Stream ownership.</b> The submitter builds the pipe and owns its
 * ends; the executing worker may close both streams when its pump is done
 * (best-effort, so a cancelled job never leaves the client hanging). The item
 * itself carries no job identity and never logs bytes — the orchestrator
 * routes it opaquely.
 */
public final class FabricCompileItem implements FabricOrchestrator.WorkItem {

    private final InputStream request;
    private final OutputStream response;

    public FabricCompileItem(InputStream request, OutputStream response) {
        this.request = Objects.requireNonNull(request, "request");
        this.response = Objects.requireNonNull(response, "response");
    }

    /** The job's request bytes, read client → face; EOF is the request terminator. */
    public InputStream request() {
        return request;
    }

    /** The face writes the job's answer here; closing it delivers the client's EOF. */
    public OutputStream response() {
        return response;
    }
}
