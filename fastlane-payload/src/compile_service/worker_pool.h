// Detached worker bookkeeping for the compile-service payload.
//
// One accepted connection becomes one detached thread, and two things have to
// hold when the kernel refuses that thread:
//
//   * the in-flight count must not leak, or serve_forever()'s drain waits
//     forever for a worker that will never run;
//   * the accepted handle must be closed, or the peer waits on a socket no one
//     owns;
//
// and the accept loop has to keep serving. Counting and starting therefore live
// in one place — spawn() — instead of an `active_++` at the call site, so the
// two can only move together.
//
// Why a spawner seam instead of std::thread: this service is built with
// -fno-exceptions (EH=OFF ABI, 决策地图 §11), and std::thread reports "no thread
// available" by throwing std::system_error. An exception that reaches an
// EH-disabled frame terminates the payload rather than failing one job, so the
// start is a hook that returns an errno-style code. spawn_worker_thread is that
// hook's real implementation (pthread_create, which reports the same condition
// by return value); tests substitute one that fails on demand, because nothing
// else can make the kernel run out of threads on purpose.
//
// The handle is owned by whoever wins: on success the worker closes it (via its
// transport), on failure spawn() closes it.

#ifndef FASTLANE_PVM_WORKER_POOL_H_
#define FASTLANE_PVM_WORKER_POOL_H_

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>

#include "net.h"

namespace fl {
namespace pvm {

/** Worker body: `owner` is opaque to the pool, `handle` is the accepted peer. */
using WorkerFn = void (*)(void* owner, SocketHandle handle);

/** 0 on success, an errno-style code when no thread could be started. */
using WorkerSpawner = int (*)(WorkerFn worker, void* owner, SocketHandle handle);

/** Real spawner: starts `worker(owner, handle)` on a detached thread. */
int spawn_worker_thread(WorkerFn worker, void* owner, SocketHandle handle);

class WorkerPool {
public:
    explicit WorkerPool(WorkerSpawner spawner = &spawn_worker_thread)
        : spawner_(spawner) {}

    WorkerPool(const WorkerPool&) = delete;
    WorkerPool& operator=(const WorkerPool&) = delete;

    /**
     * Starts one worker. On failure the slot is released and `handle` is
     * closed before returning, so the caller only has to log and keep
     * accepting; the returned code is the spawner's.
     */
    int spawn(WorkerFn worker, void* owner, SocketHandle handle);

    /** Called by the worker when its job is over. */
    void finished();

    /** In-flight workers: incremented by spawn(), decremented by finished(). */
    unsigned active() const { return active_.load(); }

    /** Blocks until every started worker has called finished(). */
    void wait_drained();

private:
    WorkerSpawner spawner_;
    std::atomic<unsigned> active_{0};
    std::mutex mutex_;
    std::condition_variable drained_;
};

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_WORKER_POOL_H_
