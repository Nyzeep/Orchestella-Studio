cmake_minimum_required(VERSION 3.22)

project(fastlane_payload LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(FL_BUILD_TESTS "Build the host-side unit and integration tests" ON)
option(FL_BUILD_HOST "Build the host-side driver and test targets" ON)
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

# --- PVM-02: in-process compiler kernel (prototype) --------------------------
# TinyCC (LGPL-2.1) backs the prototype only: sources are fetched at configure
# time from a hash-pinned tarball and never enter the repository, and the
# prototype must be replaced by libclang (PVM-04/PVM-03) before any payload
# ships. The static lib is not referenced by fastlane_payload, so the payload
# .so output is unchanged until PVM-03 wires it up.
enable_language(C)

set(FL_TINYCC_URL
    "https://github.com/TinyCC/tinycc/archive/e23dabed154a8495f3ac1ff3cc9d04fa1c207893.tar.gz"
    CACHE STRING "Pinned TinyCC source tarball (mob branch)")
set(FL_TINYCC_SHA256
    "d2470fdeed862ddb18f4ec4e6a405fa984580c3d85c38492516749c61931c30e"
    CACHE STRING "SHA-256 of FL_TINYCC_URL")

include(FetchContent)
FetchContent_Declare(tinycc URL "${FL_TINYCC_URL}" URL_HASH "SHA256=${FL_TINYCC_SHA256}")
if(NOT tinycc_POPULATED)
    FetchContent_Populate(tinycc)
endif()

set(FL_TINYCC_SOURCES
    libtcc.c
    tccpp.c
    tccgen.c
    tccdbg.c
    tccelf.c
    tccasm.c
    tccrun.c
)
if(ANDROID)
    if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "pvm_compiler prototype supports arm64-v8a only, got ${ANDROID_ABI}")
    endif()
    list(APPEND FL_TINYCC_SOURCES arm64-gen.c arm64-link.c arm64-asm.c)
elseif(WIN32)
    list(APPEND FL_TINYCC_SOURCES x86_64-gen.c x86_64-link.c i386-asm.c tccpe.c)
else()
    list(APPEND FL_TINYCC_SOURCES x86_64-gen.c x86_64-link.c i386-asm.c)
endif()

set(FL_TINYCC_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/tinycc")
file(MAKE_DIRECTORY "${FL_TINYCC_GEN_DIR}")
file(WRITE "${FL_TINYCC_GEN_DIR}/config.h"
"/* Minimal TinyCC config for the PVM-02 prototype build; object output only. */\n"
"#define TCC_VERSION \"0.9.28-mob-e23dabed\"\n")

list(TRANSFORM FL_TINYCC_SOURCES PREPEND "${tinycc_SOURCE_DIR}/")
add_library(pvm_compiler_tcc STATIC ${FL_TINYCC_SOURCES})
target_include_directories(pvm_compiler_tcc SYSTEM PRIVATE
    "${tinycc_SOURCE_DIR}"
    "${FL_TINYCC_GEN_DIR}"
)
if(ANDROID)
    target_compile_definitions(pvm_compiler_tcc PRIVATE TCC_TARGET_ARM64)
else()
    target_compile_definitions(pvm_compiler_tcc PRIVATE TCC_TARGET_X86_64)
endif()
if(WIN32)
    target_compile_definitions(pvm_compiler_tcc PRIVATE TCC_TARGET_PE)
endif()
if(FL_SANITIZE AND CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    # TinyCC upstream code is not sanitizer-clean (unaligned buffer reads);
    # keep the vendored prototype uninstrumented so one UBSan finding inside
    # TinyCC cannot fail the whole gate. The instrumented wrapper stays clean.
    target_compile_options(pvm_compiler_tcc PRIVATE
        -fno-sanitize=address -fno-sanitize=undefined)
endif()

add_library(pvm_compiler STATIC
    src/compiler/compiler.cpp
    src/compiler/tinycc_compiler.cpp
)
target_include_directories(pvm_compiler
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src/compiler
        ${tinycc_SOURCE_DIR}
        ${FL_TINYCC_GEN_DIR}
)
# TinyCC loads its builtin macro definitions with "#include <tccdefs.h>",
# which only searches system include paths; point it at the vendored include
# root at run time.
target_compile_definitions(pvm_compiler PRIVATE
    FL_TINYCC_INCLUDE="${tinycc_SOURCE_DIR}/include")
target_link_libraries(pvm_compiler PUBLIC pvm_compiler_tcc)
if(FL_ENABLE_WERROR)
    set_source_files_properties(src/compiler/compiler.cpp src/compiler/tinycc_compiler.cpp
        PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
endif()
# -----------------------------------------------------------------------------

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
elseif(FL_BUILD_HOST)
    add_executable(fastlane_payload_host
        src/main.cpp
        src/vm_payload_stub.cpp
    )
    target_link_libraries(fastlane_payload_host PRIVATE fastlane_payload_core)
    add_executable(pvm_compiler_host
        src/compiler/host_main.cpp
    )
    target_link_libraries(pvm_compiler_host PRIVATE pvm_compiler)
    if(FL_ENABLE_WERROR)
        target_compile_options(pvm_compiler_host PRIVATE -Wall -Wextra -Werror)
    endif()
endif()

if(FL_BUILD_TESTS AND FL_BUILD_HOST AND NOT ANDROID)
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
        tests/test_inprocess_compiler.cpp
        src/vm_payload_stub.cpp
    )
    target_link_libraries(fastlane_payload_tests PRIVATE fastlane_payload_core pvm_compiler)
    target_include_directories(fastlane_payload_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src/compiler
    )
    target_compile_definitions(fastlane_payload_tests PRIVATE
        FLN1_GOLDEN_PATH="${CMAKE_CURRENT_SOURCE_DIR}/../protocol/fln1-golden.json")
    target_compile_options(fastlane_payload_tests PRIVATE -Wall -Wextra -Werror -Wpedantic)
    if(WIN32)
        target_link_libraries(fastlane_payload_tests PRIVATE psapi)
    endif()
    add_test(NAME fastlane_payload_tests COMMAND fastlane_payload_tests)
    add_test(NAME inprocess_compiler_tests COMMAND fastlane_payload_tests)
    set_tests_properties(inprocess_compiler_tests PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=inprocess")
    if(UNIX)
        # Run through bash so the test does not depend on the script's exec bit.
        add_test(NAME inprocess_noexec
                 COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/tests/inprocess_noexec.sh
                         $<TARGET_FILE:pvm_compiler_host>)
    endif()
endif()
