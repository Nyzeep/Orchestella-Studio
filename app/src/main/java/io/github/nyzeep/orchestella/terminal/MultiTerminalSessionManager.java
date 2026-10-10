package io.github.nyzeep.orchestella.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe multi-terminal session manager for Orchestella Studio.
 *
 * <p>Aggregates concurrent terminal sessions per container instance:
 * <ul>
 *   <li>Maintains mapping of {@code instanceId -> (sessionId -> TerminalSessionInstance)};</li>
 *   <li>Enforces upper-bound session quotas to protect system memory and PTY handles;</li>
 *   <li>Guarantees smooth active session pointer migration upon closing active sessions;</li>
 *   <li>Zero Cross-session Bleed: Pure JVM isolation for output transcripts.</li>
 * </ul>
 */
public final class MultiTerminalSessionManager {

    private static final MultiTerminalSessionManager INSTANCE = new MultiTerminalSessionManager();

    public static MultiTerminalSessionManager getInstance() {
        return INSTANCE;
    }

    public static final int DEFAULT_MAX_SESSIONS = 16;

    private final int maxSessionsPerInstance;
    private final ConcurrentMap<String, Map<String, TerminalSessionInstance>> instanceSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeSessionIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> sessionCounters = new ConcurrentHashMap<>();

    public MultiTerminalSessionManager() {
        this(DEFAULT_MAX_SESSIONS);
    }

    public MultiTerminalSessionManager(int maxSessionsPerInstance) {
        if (maxSessionsPerInstance <= 0) {
            throw new IllegalArgumentException("maxSessionsPerInstance must be positive: " + maxSessionsPerInstance);
        }
        this.maxSessionsPerInstance = maxSessionsPerInstance;
    }

    /**
     * Creates a new terminal session for the given container instance.
     *
     * @param instanceId target instance ID
     * @param initialTitle optional initial title (defaults to "终端 N" if null or empty)
     * @return newly spawned session instance
     * @throws IllegalStateException if the maximum number of sessions for this instance is reached
     */
    public TerminalSessionInstance createSession(String instanceId, String initialTitle) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");

        Map<String, TerminalSessionInstance> sessionMap =
                instanceSessions.computeIfAbsent(instanceId, k -> Collections.synchronizedMap(new LinkedHashMap<>()));

        synchronized (sessionMap) {
            if (sessionMap.size() >= maxSessionsPerInstance) {
                throw new IllegalStateException("Maximum terminal sessions limit reached ("
                        + maxSessionsPerInstance + ") for instance " + instanceId);
            }

            AtomicInteger counter = sessionCounters.computeIfAbsent(instanceId, k -> new AtomicInteger(0));
            int nextIndex = counter.incrementAndGet();
            String sessionId = "term-" + nextIndex;
            String effectiveTitle = (initialTitle != null && !initialTitle.trim().isEmpty())
                    ? initialTitle.trim()
                    : ("终端 " + nextIndex);

            TerminalSessionInstance session = new TerminalSessionInstance(sessionId, instanceId, effectiveTitle);
            sessionMap.put(sessionId, session);

            // If there is no active session yet, set this new session as active
            activeSessionIds.putIfAbsent(instanceId, sessionId);

            return session;
        }
    }

    /**
     * Retrieves the currently active session for the given instance.
     *
     * @param instanceId target instance ID
     * @return active session or null if none exists
     */
    public TerminalSessionInstance getActiveSession(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        if (sessionMap == null) {
            return null;
        }

        synchronized (sessionMap) {
            String activeId = activeSessionIds.get(instanceId);
            if (activeId != null && sessionMap.containsKey(activeId)) {
                return sessionMap.get(activeId);
            }

            // Fallback to first available session if active pointer is stale or null
            if (!sessionMap.isEmpty()) {
                TerminalSessionInstance fallback = sessionMap.values().iterator().next();
                activeSessionIds.put(instanceId, fallback.getSessionId());
                return fallback;
            }

            activeSessionIds.remove(instanceId);
            return null;
        }
    }

    /**
     * Switches the active terminal session for the specified instance.
     *
     * @param instanceId target instance ID
     * @param sessionId target session ID to activate
     * @return true if switched successfully, false if session not found
     */
    public boolean switchActiveSession(String instanceId, String sessionId) {
        if (instanceId == null || sessionId == null) {
            return false;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        if (sessionMap == null) {
            return false;
        }

        synchronized (sessionMap) {
            if (sessionMap.containsKey(sessionId)) {
                activeSessionIds.put(instanceId, sessionId);
                return true;
            }
            return false;
        }
    }

    /**
     * Renames an existing terminal session.
     *
     * @param instanceId target instance ID
     * @param sessionId target session ID
     * @param newTitle new display title
     * @return true if renamed, false if session does not exist
     */
    public boolean renameSession(String instanceId, String sessionId, String newTitle) {
        if (instanceId == null || sessionId == null || newTitle == null || newTitle.trim().isEmpty()) {
            return false;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        if (sessionMap == null) {
            return false;
        }

        TerminalSessionInstance session = sessionMap.get(sessionId);
        if (session != null) {
            session.setTitle(newTitle);
            return true;
        }
        return false;
    }

    /**
     * Closes and removes a session from the manager.
     *
     * <p>If the closed session is the currently active session, the active pointer
     * automatically smoothly transfers to a remaining available session.
     *
     * @param instanceId target instance ID
     * @param sessionId session ID to close
     * @return true if closed and removed, false if not found
     */
    public boolean closeSession(String instanceId, String sessionId) {
        if (instanceId == null || sessionId == null) {
            return false;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        if (sessionMap == null) {
            return false;
        }

        synchronized (sessionMap) {
            TerminalSessionInstance removed = sessionMap.remove(sessionId);
            if (removed != null) {
                removed.close();

                // Smoothly migrate active pointer if closed session was active
                String currentActive = activeSessionIds.get(instanceId);
                if (sessionId.equals(currentActive)) {
                    if (!sessionMap.isEmpty()) {
                        // Pick next available session (e.g. latest or first)
                        TerminalSessionInstance nextActive = null;
                        for (TerminalSessionInstance candidate : sessionMap.values()) {
                            nextActive = candidate;
                        }
                        if (nextActive != null) {
                            activeSessionIds.put(instanceId, nextActive.getSessionId());
                        }
                    } else {
                        activeSessionIds.remove(instanceId);
                    }
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Lists all current sessions for the given instance in creation order.
     *
     * @param instanceId target instance ID
     * @return unmodifiable list of session instances
     */
    public List<TerminalSessionInstance> listSessions(String instanceId) {
        if (instanceId == null) {
            return Collections.emptyList();
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        if (sessionMap == null) {
            return Collections.emptyList();
        }

        synchronized (sessionMap) {
            return Collections.unmodifiableList(new ArrayList<>(sessionMap.values()));
        }
    }

    /**
     * Gets a specific session without changing active pointer.
     */
    public TerminalSessionInstance getSession(String instanceId, String sessionId) {
        if (instanceId == null || sessionId == null) {
            return null;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        return sessionMap != null ? sessionMap.get(sessionId) : null;
    }

    /**
     * Returns the total session count for the instance.
     */
    public int getSessionCount(String instanceId) {
        if (instanceId == null) {
            return 0;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.get(instanceId);
        return sessionMap != null ? sessionMap.size() : 0;
    }

    /**
     * Closes all sessions associated with an instance.
     */
    public void closeAllSessions(String instanceId) {
        if (instanceId == null) {
            return;
        }
        Map<String, TerminalSessionInstance> sessionMap = instanceSessions.remove(instanceId);
        if (sessionMap != null) {
            synchronized (sessionMap) {
                for (TerminalSessionInstance s : sessionMap.values()) {
                    s.close();
                }
                sessionMap.clear();
            }
        }
        activeSessionIds.remove(instanceId);
    }

    /**
     * Clears all sessions across all instances.
     */
    public void clear() {
        for (String id : new ArrayList<>(instanceSessions.keySet())) {
            closeAllSessions(id);
        }
        instanceSessions.clear();
        activeSessionIds.clear();
        sessionCounters.clear();
    }
}