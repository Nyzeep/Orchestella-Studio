package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.engineavf.AvfVirtualMachineEngine;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process handoff of live AVF sessions to {@link TerminalActivity}: an
 * Intent cannot carry streams, so the launcher registers the session under
 * the instance id and the terminal activity consumes (and unregisters) it.
 * Registry state dies with the host process by construction; launches always
 * re-register before starting the activity.
 */
public final class AvfTerminalStreamsRegistry {
    private static final ConcurrentMap<String, AvfVirtualMachineEngine.AvfVmSession> SESSIONS =
            new ConcurrentHashMap<>();

    private AvfTerminalStreamsRegistry() {}

    public static void register(
            String instanceId, AvfVirtualMachineEngine.AvfVmSession session) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(session, "session");
        SESSIONS.put(instanceId, session);
    }

    /** Null when no live AVF session exists for the id (QEMU path). */
    public static AvfVirtualMachineEngine.AvfVmSession get(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        return SESSIONS.get(instanceId);
    }

    public static void unregister(String instanceId) {
        if (instanceId != null) {
            SESSIONS.remove(instanceId);
        }
    }
}
