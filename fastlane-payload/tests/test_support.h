#pragma once

#include <sstream>
#include <string>
#include <vector>

#include "fastlane/error.h"

namespace fl {
namespace test {

struct Case {
    const char* name;
    void (*function)();
};

std::vector<Case>& registry();

struct Registrar {
    Registrar(const char* name, void (*function)());
};

void fail(const char* file, int line, const std::string& message);

template <typename T>
std::string show(const T& value) {
    std::ostringstream stream;
    stream << value;
    return stream.str();
}

}  // namespace test
}  // namespace fl

#define FL_TEST(name)                                                        \
    static void name();                                                      \
    static ::fl::test::Registrar registrar_##name(#name, name);              \
    static void name()

#define FL_CHECK(condition)                                                  \
    do {                                                                     \
        if (!(condition)) {                                                  \
            ::fl::test::fail(__FILE__, __LINE__, "CHECK failed: " #condition); \
        }                                                                    \
    } while (false)

#define FL_CHECK_EQ(actual, expected)                                        \
    do {                                                                     \
        const auto& actual_value = (actual);                                 \
        const auto& expected_value = (expected);                             \
        if (!(actual_value == expected_value)) {                             \
            ::fl::test::fail(__FILE__, __LINE__,                             \
                             "CHECK_EQ failed: " #actual " == " #expected    \
                             " (actual=" + ::fl::test::show(actual_value) +  \
                             ", expected=" + ::fl::test::show(expected_value) + ")"); \
        }                                                                    \
    } while (false)

#define FL_CHECK_THROWS_CODE(expression, expected_code)                      \
    do {                                                                     \
        bool threw = false;                                                  \
        try {                                                                \
            (void)(expression);                                              \
        } catch (const ::fl::Error& error) {                                 \
            threw = true;                                                    \
            if (error.code() != (expected_code)) {                           \
                ::fl::test::fail(__FILE__, __LINE__,                         \
                                 "wrong error code: " +                      \
                                     std::string(::fl::code_name(error.code())) + \
                                     " expected " +                          \
                                     std::string(::fl::code_name(expected_code))); \
            }                                                                \
        }                                                                    \
        if (!threw) {                                                        \
            ::fl::test::fail(__FILE__, __LINE__,                             \
                             "expected " #expression " to throw " +          \
                                 std::string(::fl::code_name(expected_code))); \
        }                                                                    \
    } while (false)
