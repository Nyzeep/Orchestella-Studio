#include "worker_pool.h"

#include <errno.h>
#include <pthread.h>

#include <new>

namespace fl {
namespace pvm {
namespace {

struct WorkerTicket {
    WorkerFn worker;
    void* owner;
    SocketHandle handle;
};

// Runs on the new thread. The thread detaches itself: pthread_detach() on your
// own joinable thread cannot fail, while detaching from the creator would be a
// second call that can fail after the worker is already running — either a
// leaked thread or a job whose handle the spawner has already reported as
// failed.
void* worker_entry(void* raw) {
    const WorkerTicket ticket = *static_cast<WorkerTicket*>(raw);
    delete static_cast<WorkerTicket*>(raw);
    (void)::pthread_detach(::pthread_self());
    ticket.worker(ticket.owner, ticket.handle);
    return nullptr;
}

}  // namespace

int spawn_worker_thread(WorkerFn worker, void* owner, SocketHandle handle) {
    if (worker == nullptr) {
        return EINVAL;
    }
    WorkerTicket* ticket =
            new (std::nothrow) WorkerTicket{worker, owner, handle};
    if (ticket == nullptr) {
        return ENOMEM;
    }
    pthread_t thread;
    const int started = ::pthread_create(&thread, nullptr, &worker_entry, ticket);
    if (started != 0) {
        // pthread_create reports the refusal (EAGAIN and friends) instead of
        // throwing, which is the only reason this build can survive it at all.
        delete ticket;
        return started;
    }
    return 0;
}

int WorkerPool::spawn(WorkerFn worker, void* owner, SocketHandle handle) {
    // Reserve the slot before the thread exists: a worker that finishes before
    // the count is published would decrement below zero. Every failure below
    // puts it back, so a refused thread can never leave wait_drained() waiting.
    active_.fetch_add(1);
    const int code = spawner_(worker, owner, handle);
    if (code != 0) {
        close_socket(handle);
        active_.fetch_sub(1);
        return code;
    }
    return 0;
}

void WorkerPool::finished() {
    active_.fetch_sub(1);
    {
        std::lock_guard<std::mutex> lock(mutex_);
    }
    drained_.notify_all();
}

void WorkerPool::wait_drained() {
    std::unique_lock<std::mutex> lock(mutex_);
    drained_.wait(lock, [this]() { return active_.load() == 0; });
}

}  // namespace pvm
}  // namespace fl
