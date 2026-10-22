cmake_minimum_required(VERSION 3.22)

project(fastlane_payload LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(FL_BUILD_TESTS "Build the host-side unit and integration tests" ON)
option(FL_ENABLE_WERROR "Treat compiler warnings as errors" ON)
option(FL_SANITIZE "Build host targets with AddressSanitizer and UndefinedBehaviorSanitizer" OFF)

if(FL_SANITIZE)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
        message(FATAL_ERROR "FL_SANITIZE requires GCC or Clang")
    endif()
    add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer
                        -fno-sanitize-recover=all)
    add_link_options(-fsanitize=address,undefined)
endif()

find_package(Threads REQUIRED)

add_library(fastlane_payload_core STATIC
    src/distcc_bridge.cpp
    src/error.cpp
    src/paths.cpp
    src/process.cpp
    src/protocol.cpp
    src/proxy_bridge.cpp
    src/pty.cpp
    src/runtime.cpp
    src/sha256.cpp
    src/storage.cpp
    src/tar.cpp
    src/transport.cpp
)

target_include_directories(fastlane_payload_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(fastlane_payload_core PUBLIC Threads::Threads)

if(FL_ENABLE_WERROR)
    target_compile_options(fastlane_payload_core PRIVATE -Wall -Wextra -Werror -Wpedantic)
endif()

if(ANDROID)
    # Link-time stub only. The device resolves libvm_payload.so from the AVF
    # APEX; this stub must never be packaged into the APK.
    add_library(vm_payload_stub SHARED src/vm_payload_stub.cpp)
    target_include_directories(vm_payload_stub PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    set_target_properties(vm_payload_stub PROPERTIES OUTPUT_NAME vm_payload)
    add_library(fastlane_payload SHARED src/main.cpp)
    target_link_libraries(fastlane_payload PRIVATE fastlane_payload_core vm_payload_stub log)
    # Android 16 KB page compatibility: every PT_LOAD must be 16 KB aligned.
    target_link_options(vm_payload_stub PRIVATE -Wl,-z,max-page-size=16384)
    target_link_options(fastlane_payload PRIVATE -Wl,-z,max-page-size=16384)
    target_compile_options(fastlane_payload PRIVATE -Wall -Wextra -Werror)
    set_target_properties(fastlane_payload PROPERTIES
        OUTPUT_NAME "fastlane_payload"
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
else()
    add_executable(fastlane_payload_host
        src/main.cpp
        src/vm_payload_stub.cpp
    )
    target_link_libraries(fastlane_payload_host PRIVATE fastlane_payload_core)
endif()

if(FL_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
    add_executable(fastlane_payload_tests
        tests/test_main.cpp
        tests/test_protocol.cpp
        tests/test_sha256.cpp
        tests/test_storage.cpp
        tests/test_process.cpp
        tests/test_bridges.cpp
        tests/test_runtime.cpp
        tests/test_golden_fixture.cpp
        src/vm_payload_stub.cpp
    )
    target_link_libraries(fastlane_payload_tests PRIVATE fastlane_payload_core)
    target_include_directories(fastlane_payload_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    target_compile_definitions(fastlane_payload_tests PRIVATE
        FLN1_GOLDEN_PATH="${CMAKE_CURRENT_SOURCE_DIR}/../protocol/fln1-golden.json")
    target_compile_options(fastlane_payload_tests PRIVATE -Wall -Wextra -Werror -Wpedantic)
    add_test(NAME fastlane_payload_tests COMMAND fastlane_payload_tests)
endif()
