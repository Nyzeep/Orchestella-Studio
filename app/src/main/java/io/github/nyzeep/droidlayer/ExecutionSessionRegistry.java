package io.github.nyzeep.droidlayer;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process handoff of live {@link ExecutionSession}s to {@link
 * TerminalActivity}: an Intent cannot carry streams, so the strategy launchers
 * register the neutral session under the instance id and the terminal activity
 * consumes (and unregisters) it. Registry state dies with the host process by
 * construction; launches always re-register before starting the activity.
 */
public final class ExecutionSessionRegistry {
    private static final ConcurrentMap<String, ExecutionSession> SESSIONS =
            new ConcurrentHashMap<>();

    private ExecutionSessionRegistry() {}

    public static void register(String instanceId, ExecutionSession session) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(session, "session");
        SESSIONS.put(instanceId, session);
    }

    /** Null when no live session exists for the id. */
    public static ExecutionSession get(String instanceId) {
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
