// DV-PVM-01b load diagnostic: resolve the three compiler sonames from the
// payload's linker namespace, nothing else.
//
// QA-only payload entry (name: libpvm_probe_clangload.so). It has no
// DT_NEEDED dependency on the compiler libraries, so it always starts even
// when the linked probe cannot load; the dlopen results and dlerror strings
// (path-scrubbed) are the evidence the ticket asks for when
// PAYLOAD_LOAD_FAILED hits the linked variant. Exit code 0 iff all three
// sonames resolve.

#include <dlfcn.h>

#include <string>
#include <vector>

#include "probe_common.h"

#include "fastlane/vm_payload_api.h"

namespace {

// Order matters for evidence readability: libc++ first (pure leaf), then
// libLLVM (leaf), then libclang-cpp (depends on both).
constexpr const char* const kLibraries[] = {
    "libc++_shared.so",
    "libLLVM.so",
    "libclang-cpp.so",
};

}  // namespace

int run_probe() {
    // Same ordering as 01a: the vsock channel must be live before any emit.
    pvmprobe::start_result_listener();
    AVmPayload_notifyPayloadReady();
    const long long started = pvmprobe::steady_now_ms();
    const int topology = pvmprobe::cpu_topology_online();

    int missing = 0;
    for (int iter = 0; iter < 3; ++iter) {
        const char* const name = kLibraries[iter];
        void* handle = dlopen(name, RTLD_NOW);
        if (handle == nullptr) {
            ++missing;
        }
        const char* error = handle == nullptr ? dlerror() : nullptr;
        pvmprobe::emit("load", handle != nullptr ? "OK" : "PAYLOAD_LOAD_FAILED",
                       iter + 1, pvmprobe::steady_ms_since(started),
                       pvmprobe::ru_maxrss_kb(), 0,
                       std::string(name) + (error == nullptr
                                                ? std::string()
                                                : std::string(": ") + error),
                       topology);
    }

    pvmprobe::emit("done", missing == 0 ? "OK" : "PAYLOAD_LOAD_FAILED", 0,
                   pvmprobe::steady_ms_since(started), pvmprobe::ru_maxrss_kb(), 0,
                   "dlopen probe", topology);
    pvmprobe::finish_result_sink();
    return missing == 0 ? 0 : 1;
}

#if defined(__ANDROID__)
extern "C" __attribute__((visibility("default"))) int AVmPayload_main(void) {
    return run_probe();
}
#else
int main() { return run_probe(); }
#endif
