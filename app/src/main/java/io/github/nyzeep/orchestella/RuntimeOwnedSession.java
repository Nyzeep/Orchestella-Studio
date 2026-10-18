package io.github.nyzeep.orchestella;

/** A session whose backing runtime can be stopped and observed by the supervisor. */
public interface RuntimeOwnedSession extends ExecutionSession {
    /** Bounded stop of the backing process, gateway and channels. */
    RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs);

    /** True while the backing runtime may still be alive. */
    boolean isRuntimeAlive();
}
