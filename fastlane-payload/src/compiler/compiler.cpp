#include "compiler.h"

namespace fl {

const char* compile_status_name(CompileStatus status) {
    switch (status) {
        case CompileStatus::kOk:
            return "OK";
        case CompileStatus::kArgvRejected:
            return "ARGV_REJECTED";
        case CompileStatus::kJobTooLarge:
            return "JOB_TOO_LARGE";
        case CompileStatus::kJobTimeout:
            return "JOB_TIMEOUT";
        case CompileStatus::kCompileError:
            return "COMPILE_ERROR";
        case CompileStatus::kInternal:
            return "INTERNAL";
    }
    return "UNKNOWN";
}

}  // namespace fl
