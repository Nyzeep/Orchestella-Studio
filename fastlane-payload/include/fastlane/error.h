#pragma once

#include <stdexcept>
#include <string>

namespace fl {

/** Wire-visible error codes; values 1..14 are the protocol ERROR payload codes. */
enum class Code : int {
    kNone = 0,
    kProtocolMalformed = 1,
    kUnsupportedVersion = 2,
    kBadToken = 3,
    kUnknownCommand = 4,
    kSessionNotFound = 5,
    kSessionLimit = 6,
    kExecFailed = 7,
    kStorageFailed = 8,
    kPathRejected = 9,
    kExportBusy = 10,
    kExportIo = 11,
    kPayloadBusy = 12,
    kUnsupported = 13,
    kInternal = 14,

    // Host-side/transport failures that are never sent on the wire.
    kIo = 100,
    kPermission = 101,
    kCancelled = 102,
    kLimitExceeded = 103,
    kUnsupportedFormat = 104,
    kLayoutInvalid = 105,
};

const char* code_name(Code code);

class Error : public std::runtime_error {
public:
    Error(Code code, const std::string& message);
    Code code() const { return code_; }

private:
    Code code_;
};

}  // namespace fl
