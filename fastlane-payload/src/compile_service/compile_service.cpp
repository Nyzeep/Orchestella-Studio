#include "compile_service.h"

namespace fl {
namespace pvm {
namespace {

constexpr const char* kPathPlaceholder = "<path>";

bool is_path_separator(char value) {
    return value == '/' || value == '\\';
}

// A token is path-like when it contains a separator and is not already the
// placeholder, so sanitising twice is idempotent.
bool is_path_like(const std::string& token) {
    if (token == kPathPlaceholder) {
        return false;
    }
    for (const char value : token) {
        if (is_path_separator(value)) {
            return true;
        }
    }
    return false;
}

}  // namespace

const char* job_status_name(JobStatus status) {
    switch (status) {
        case JobStatus::kOk: return "OK";
        case JobStatus::kArgvRejected: return "ARGV_REJECTED";
        case JobStatus::kJobTooLarge: return "JOB_TOO_LARGE";
        case JobStatus::kJobTimeout: return "JOB_TIMEOUT";
        case JobStatus::kCompileError: return "COMPILE_ERROR";
        case JobStatus::kInternal: return "PAYLOAD_LOAD_FAILED";
    }
    return "PAYLOAD_LOAD_FAILED";
}

uint32_t stat_from_exit_code(DistccExitCode code) {
    return static_cast<uint32_t>(code) << 8;
}

// Diagnostics are tokenised on whitespace and every token that carries a
// separator is replaced wholesale: a truncated path would still leak the
// prefix, and the guest must never learn host layout.
std::string sanitize_diagnostics(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    std::string token;
    auto flush = [&out, &token]() {
        if (token.empty()) {
            return;
        }
        if (is_path_like(token)) {
            out += kPathPlaceholder;
        } else {
            out += token;
        }
        token.clear();
    };
    for (const char value : text) {
        if (value == ' ' || value == '\t' || value == '\n' || value == '\r') {
            flush();
            out.push_back(value);
        } else {
            token.push_back(value);
        }
    }
    flush();
    return out;
}

std::string truncate_utf8(const std::string& text, size_t max_bytes) {
    if (text.size() <= max_bytes) {
        return text;
    }
    size_t limit = max_bytes;
    // Back off over UTF-8 continuation bytes (10xxxxxx) so the cut lands on a
    // character boundary; never loops more than three times.
    while (limit > 0 && (static_cast<unsigned char>(text[limit]) & 0xC0) == 0x80) {
        --limit;
    }
    return text.substr(0, limit);
}

}  // namespace pvm
}  // namespace fl
