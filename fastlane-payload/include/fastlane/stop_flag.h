#pragma once

#include <atomic>

namespace fl {

/**
 * Stop bookkeeping shared by Runtime::run(), Runtime::stop() and the
 * SIGTERM/SIGINT handler.
 *
 * A single process-wide flag cannot survive a second run: after run()->stop()
 * the flag stayed set, so the next run() in the same process — a test, an
 * embedding host, or a host that relaunches the payload — saw "stop requested"
 * before its first loop iteration and returned immediately, and its stop() was
 * short-circuited by the latched `stopped_` as well.
 *
 * Two flags fix that without losing a signal:
 *
 *   requested_  the per-run flag. Cleared at the top of a run, set by stop()
 *               and by the signal handler. This is what the run loop polls.
 *   pending_    set *only* by the signal handler. begin_run() leaves it alone,
 *               so a SIGTERM/SIGINT that arrives before run() starts still ends
 *               that run instead of being swallowed by the reset; end_run()
 *               clears it once a run has actually honored it, so one signal
 *               stops exactly one run.
 *
 * Trade-off, stated on purpose: an explicit stop() is not sticky across run
 * boundaries — it belongs to the run that is in progress (or the next one to
 * observe it), because a fresh run() must start un-stopped. Only an
 * asynchronous signal outlives a run boundary. Each flag has exactly one
 * writer, so the handler never clears state and begin_run() never clears the
 * signal: neither side can lose a concurrent request.
 */
class StopFlag {
public:
    /** Stop request from runtime code (Runtime::stop()). */
    void request() { requested_.store(true); }

    /** Stop request from a signal handler: no allocation, one atomic store. */
    void request_from_signal() { pending_.store(true); }

    /** True while the current run must stop, for either reason. */
    bool requested() const {
        return requested_.load() || pending_.load();
    }

    /** A signal arrived and no run has honored it yet. */
    bool signal_pending() const { return pending_.load(); }

    /** Top of a run: forget the previous run's stop, keep an unhonored signal. */
    void begin_run() { requested_.store(false); }

    /** End of a run: the stop it observed is spent. */
    void end_run() {
        requested_.store(false);
        pending_.store(false);
    }

private:
    std::atomic<bool> requested_{false};
    std::atomic<bool> pending_{false};
};

/**
 * The one instance the signal handler and every Runtime share. Namespace scope,
 * not a function-local static: std::atomic's default constructor is constexpr,
 * so this is constant-initialized and a handler can touch it before any user
 * code has run (a function-local static would need the guard lock).
 */
inline StopFlag g_process_stop_flag;

}  // namespace fl
