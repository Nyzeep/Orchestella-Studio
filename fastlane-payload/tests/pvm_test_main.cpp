// Test entry point for the standalone PVM-03 verification build.
//
// fastlane-payload/tests/test_main.cpp belongs to the FLN1 suite and pulls in
// fastlane/error.h; the compile service only needs the registry and the
// throwing assertion helpers, so this is the same harness with the payload
// includes removed.

#include <csignal>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <iostream>
#include <string>
#include <vector>

#include "test_support.h"

namespace fl {
namespace test {

std::vector<Case>& registry() {
    static std::vector<Case> cases;
    return cases;
}

Registrar::Registrar(const char* name, void (*function)()) {
    registry().push_back(Case{name, function});
}

void fail(const char* file, int line, const std::string& message) {
    throw std::runtime_error(std::string(file) + ":" + std::to_string(line) + ": " +
                             message);
}

}  // namespace test
}  // namespace fl

int main() {
#if !defined(_WIN32)
    std::signal(SIGPIPE, SIG_IGN);
#endif
    const char* filter = std::getenv("FL_TEST_FILTER");
    int failures = 0;
    int executed = 0;
    for (const auto& test_case : fl::test::registry()) {
        if (filter != nullptr && std::strstr(test_case.name, filter) == nullptr) {
            continue;
        }
        ++executed;
        try {
            test_case.function();
            std::cout << "PASS " << test_case.name << "\n";
        } catch (const std::exception& failure) {
            ++failures;
            std::cout << "FAIL " << test_case.name << ": " << failure.what() << "\n";
        }
    }
    // A filter that matches nothing is a broken invocation, not a pass: a typo
    // in the ctest property must never turn into a green build.
    if (executed == 0) {
        std::cout << "NO_TESTS_MATCHED\n";
        return 1;
    }
    std::cout << executed - failures << "/" << executed << " passed\n";
    return failures == 0 ? 0 : 1;
}
