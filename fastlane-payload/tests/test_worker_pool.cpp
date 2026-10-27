// Worker-pool tests (PVM-03): the accounting that used to leak when the kernel
// refused a thread.
//
// The bug these pin down: serve_forever() did `active_++` and then constructed a
// std::thread. If that constructor failed, the count was never decremented and
// the drain wait at the end of the loop never returned, so one refused thread
// wedged the payload forever with a handle nobody closed.
//
// The real spawner cannot be made to fail on demand (that needs the kernel to
// run out of threads), so the failure branch is driven through the pool's
// injectable spawner. What is checked is the pool's own contract — count back to
// zero, handle closed, drain still reachable — not the accept loop itself.

#include "worker_pool.h"

#include <errno.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <thread>

#include "net.h"
#include "test_support.h"

namespace {

using fl::pvm::kInvalidSocket;
using fl::pvm::Listener;
using fl::pvm::SocketHandle;
using fl::pvm::SocketTransport;
using fl::pvm::WorkerFn;
using fl::pvm::WorkerPool;

/** Loopback pair: the accepted handle is the one the pool would own. */
struct SocketPair {
    std::unique_ptr<Listener> listener;
    SocketHandle peer = kInvalidSocket;  // accepted side, handed to the pool
    SocketHandle client = kInvalidSocket;
};

SocketPair make_pair() {
    SocketPair pair;
    pair.listener = fl::pvm::make_loopback_tcp_listener(0);
    FL_CHECK(pair.listener != nullptr);
    pair.client = fl::pvm::connect_loopback_tcp(pair.listener->port());
    FL_CHECK(pair.client != kInvalidSocket);
    pair.peer = pair.listener->accept();
    FL_CHECK(pair.peer != kInvalidSocket);
    return pair;
}

int spawn_attempts = 0;

/** Injected spawner: never starts anything, reports "no thread available". */
int refuse_spawn(WorkerFn worker, void* owner, SocketHandle handle) {
    (void)worker;
    (void)owner;
    (void)handle;
    ++spawn_attempts;
    return EAGAIN;
}

/** Never runs: the injected spawner refuses before any thread exists. */
void unused_worker(void* owner, SocketHandle handle) {
    (void)owner;
    SocketTransport transport(handle);
}

struct Probe {
    WorkerPool* pool = nullptr;
    std::atomic<int>* ran = nullptr;
};

/** Real worker body: does the job, then releases its slot like serve_one(). */
void counted_worker(void* owner, SocketHandle handle) {
    Probe* probe = static_cast<Probe*>(owner);
    probe->ran->fetch_add(1);
    SocketTransport transport(handle);  // owns, and therefore closes, the handle
    probe->pool->finished();
}

/** Fails the first start only: the accept loop must survive exactly that. */
int refuse_once(WorkerFn worker, void* owner, SocketHandle handle) {
    if (++spawn_attempts == 1) {
        return EAGAIN;
    }
    return fl::pvm::spawn_worker_thread(worker, owner, handle);
}

/** True when the peer sees the connection end rather than a read deadline. */
bool peer_sees_close(SocketHandle handle) {
    SocketTransport client(handle);
    client.set_read_timeout_ms(2000);
    uint8_t byte = 0;
    const long read = client.read(&byte, 1);
    if (read == -2) {
        return false;  // deadline: nothing closed the other end
    }
    return read <= 0;
}

}  // namespace

FL_TEST(worker_pool_refused_spawn_releases_slot_and_closes_handle) {
    SocketPair pair = make_pair();
    spawn_attempts = 0;

    WorkerPool pool(&refuse_spawn);
    const int code = pool.spawn(&unused_worker, nullptr, pair.peer);
    FL_CHECK_EQ(code, EAGAIN);
    FL_CHECK_EQ(spawn_attempts, 1);
    // The slot is back the moment spawn() returns: a later drain cannot wait
    // for a worker that was never started.
    FL_CHECK_EQ(pool.active(), 0u);
    // ...and the handle is closed instead of held by a job that will never run.
    FL_CHECK(peer_sees_close(pair.client));

    pool.wait_drained();  // must return immediately, not block
    pair.listener->close();
}

FL_TEST(worker_pool_started_worker_runs_and_drains) {
    SocketPair pair = make_pair();
    std::atomic<int> ran{0};

    WorkerPool pool;  // real spawner: pthread_create
    Probe probe{&pool, &ran};
    const int code = pool.spawn(&counted_worker, &probe, pair.peer);
    FL_CHECK_EQ(code, 0);

    // Bounded wait first: a missing decrement must fail this test instead of
    // hanging it.
    for (int attempt = 0; attempt < 500 && pool.active() != 0; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    FL_CHECK_EQ(pool.active(), 0u);
    FL_CHECK_EQ(ran.load(), 1);
    pool.wait_drained();
    FL_CHECK(peer_sees_close(pair.client));

    pair.listener->close();
}

FL_TEST(worker_pool_refused_thread_does_not_stop_later_accepts) {
    std::atomic<int> ran{0};
    WorkerPool pool(&refuse_once);
    Probe probe{&pool, &ran};
    spawn_attempts = 0;

    SocketPair refused = make_pair();
    FL_CHECK_EQ(pool.spawn(&unused_worker, &probe, refused.peer), EAGAIN);
    FL_CHECK_EQ(pool.active(), 0u);
    FL_CHECK(peer_sees_close(refused.client));
    refused.listener->close();

    // The next accept round still gets a worker: one refused thread costs one
    // peer, not the service.
    SocketPair served = make_pair();
    FL_CHECK_EQ(pool.spawn(&counted_worker, &probe, served.peer), 0);
    pool.wait_drained();
    FL_CHECK_EQ(ran.load(), 1);
    FL_CHECK_EQ(pool.active(), 0u);
    FL_CHECK(peer_sees_close(served.client));
    served.listener->close();
}

FL_TEST(worker_pool_rejects_missing_worker_without_counting) {
    WorkerPool pool;  // real spawner
    FL_CHECK_EQ(pool.spawn(nullptr, nullptr, kInvalidSocket), EINVAL);
    FL_CHECK_EQ(pool.active(), 0u);
    pool.wait_drained();
}
