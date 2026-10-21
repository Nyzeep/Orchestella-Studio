#include "test_support.h"

#include <exception>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <iostream>

#include "fastlane/error.h"

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
    throw std::runtime_error(std::string(file) + ":" + std::to_string(line) + ": " + message);
}

}  // namespace test
}  // namespace fl

int main() {
    // The payload is a server: a vanished peer must be an EPIPE, not process death.
    std::signal(SIGPIPE, SIG_IGN);
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
    std::cout << executed - failures << "/" << executed << " passed\n";
    return failures == 0 ? 0 : 1;
}
