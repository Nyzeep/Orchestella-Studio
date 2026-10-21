#include "fastlane/error.h"

namespace fl {

Error::Error(Code code, const std::string& message)
        : std::runtime_error(message), code_(code) {}

const char* code_name(Code code) {
    switch (code) {
        case Code::kNone:
            return "NONE";
        case Code::kProtocolMalformed:
            return "PROTOCOL_MALFORMED";
        case Code::kUnsupportedVersion:
            return "UNSUPPORTED_VERSION";
        case Code::kBadToken:
            return "BAD_TOKEN";
        case Code::kUnknownCommand:
            return "UNKNOWN_COMMAND";
        case Code::kSessionNotFound:
            return "SESSION_NOT_FOUND";
        case Code::kSessionLimit:
            return "SESSION_LIMIT";
        case Code::kExecFailed:
            return "EXEC_FAILED";
        case Code::kStorageFailed:
            return "STORAGE_FAILED";
        case Code::kPathRejected:
            return "PATH_REJECTED";
        case Code::kExportBusy:
            return "EXPORT_BUSY";
        case Code::kExportIo:
            return "EXPORT_IO";
        case Code::kPayloadBusy:
            return "PAYLOAD_BUSY";
        case Code::kUnsupported:
            return "UNSUPPORTED";
        case Code::kInternal:
            return "INTERNAL";
        case Code::kIo:
            return "IO";
        case Code::kPermission:
            return "PERMISSION";
        case Code::kCancelled:
            return "CANCELLED";
        case Code::kLimitExceeded:
            return "LIMIT_EXCEEDED";
        case Code::kUnsupportedFormat:
            return "UNSUPPORTED_FORMAT";
        case Code::kLayoutInvalid:
            return "LAYOUT_INVALID";
    }
    return "UNKNOWN";
}

}  // namespace fl
