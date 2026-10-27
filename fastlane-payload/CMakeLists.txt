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

# --- PVM-03: compile service core -------------------------------------------
# Defined before the ANDROID/host split on purpose: the payload .so and the host
# twin link the same library. It depends only on libstdc++ and its own socket
# layer, never on fastlane_payload_core (the compiler payload must not carry the
# FLN1 transport, and the FLN1 core is Linux-only).
set(FL_PVM_PAYLOAD_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/pvm-compile-service.cmake)
fl_pvm_add_compile_service_library(pvm_compile_service)

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
        tests/test_argv_policy.cpp
        tests/test_distcc_server.cpp
        tests/test_axp1_server.cpp
        tests/test_compile_service_e2e.cpp
        tests/test_worker_pool.cpp
        src/vm_payload_stub.cpp
    )
    target_link_libraries(fastlane_payload_tests PRIVATE fastlane_payload_core pvm_compiler pvm_compile_service)
    target_include_directories(fastlane_payload_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src/compiler
        ${CMAKE_CURRENT_SOURCE_DIR}/src/compile_service
    )
    target_compile_definitions(fastlane_payload_tests PRIVATE
        FLN1_GOLDEN_PATH="${CMAKE_CURRENT_SOURCE_DIR}/../protocol/fln1-golden.json"
        FL_PVM_DISTCC_GOLDEN_DIR="${FL_PVM_DISTCC_GOLDEN_DIR}"
        FL_PVM_AXP1_GOLDEN_PATH="${FL_PVM_AXP1_GOLDEN_PATH}")
    target_compile_options(fastlane_payload_tests PRIVATE -Wall -Wextra -Werror -Wpedantic)
    if(WIN32)
        target_link_libraries(fastlane_payload_tests PRIVATE psapi)
    endif()
    add_test(NAME fastlane_payload_tests COMMAND fastlane_payload_tests)
    add_test(NAME inprocess_compiler_tests COMMAND fastlane_payload_tests)
    set_tests_properties(inprocess_compiler_tests PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=inprocess")
    add_test(NAME compile_service_argv_policy COMMAND fastlane_payload_tests)
    add_test(NAME compile_service_distcc_server COMMAND fastlane_payload_tests)
    add_test(NAME compile_service_axp1_server COMMAND fastlane_payload_tests)
    add_test(NAME compile_service_e2e COMMAND fastlane_payload_tests)
    add_test(NAME compile_service_worker_pool COMMAND fastlane_payload_tests)
    set_tests_properties(compile_service_argv_policy PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=argv_policy")
    set_tests_properties(compile_service_distcc_server PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=distcc_server")
    set_tests_properties(compile_service_axp1_server PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=axp1")
    set_tests_properties(compile_service_e2e PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=pvm_e2e")
    set_tests_properties(compile_service_worker_pool PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=worker_pool")
    if(UNIX)
        # Run through bash so the test does not depend on the script's exec bit.
        add_test(NAME inprocess_noexec
                 COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/tests/inprocess_noexec.sh
                         $<TARGET_FILE:pvm_compiler_host>)
    endif()
endif()

# --- DV-PVM-01: QA-only probe payloads ---------------------------------------
# Opt-in: default builds (CI, release payload path) never compile this block.
# See tools/pvm-compiler/build_probe.sh for the QA build invocation.
option(FL_BUILD_PVM_PROBE "Build QA-only DV-PVM-01 probe payloads" OFF)
# QA-only tcc fd sentinel (::fd::N): only the patched tcc copy produced by
# tools/pvm-compiler/build_probe.sh understands it. Default builds keep the
# unpatched /proc/self/fd/<fd> sink of PVM-02, so this must stay OFF
# everywhere outside build_probe.sh.
option(FL_TCC_FD_SENTINEL "QA-only: hand tcc an ::fd::N fd sentinel" OFF)

if(FL_BUILD_PVM_PROBE)
    if(FL_TCC_FD_SENTINEL)
        target_compile_definitions(pvm_compiler PRIVATE FL_TCC_FD_SENTINEL=1)
    endif()
    # TinyCC's builtin predefs are baked in as string literals
    # (CONFIG_TCC_PREDEFS) so the payload never consults a filesystem include
    # path; FL_TINYCC_INCLUDE above is a build-host path that cannot exist on
    # the device. tccdefs_.h is generated from the hash-pinned TinyCC tarball
    # by tools/pvm-compiler/build_probe.sh before configure.
    set(FL_PVM_PROBE_TCCDEFS_DIR "" CACHE PATH "Dir holding generated tccdefs_.h")
    if(FL_PVM_PROBE_TCCDEFS_DIR)
        target_compile_definitions(pvm_compiler_tcc PRIVATE CONFIG_TCC_PREDEFS=1)
        target_include_directories(pvm_compiler_tcc PRIVATE "${FL_PVM_PROBE_TCCDEFS_DIR}")
    endif()
    set(FL_PVM_PROBE_CLANG_LIBS "" CACHE PATH "Dir with staged libLLVM.so/libclang-cpp.so")
    set(FL_PVM_PROBE_LLVM_SRC "" CACHE PATH "llvm-project source root")
    set(FL_PVM_PROBE_LLVM_BUILD "" CACHE PATH "arm64 cross-build tree with generated headers")

    if(ANDROID)
        add_library(pvm_probe SHARED src/probe/pvm_probe_main.cpp)
        target_include_directories(pvm_probe PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/include
            ${CMAKE_CURRENT_SOURCE_DIR}/src/compiler
            ${CMAKE_CURRENT_SOURCE_DIR}/src/probe)
        # Link-time stub only; the device resolves libvm_payload.so from
        # the AVF APEX, and the stub must never be packaged into the APK.
        target_link_libraries(pvm_probe PRIVATE pvm_compiler vm_payload_stub log)
        target_compile_options(pvm_probe PRIVATE -Wall -Wextra -Werror)
        target_link_options(pvm_probe PRIVATE -Wl,-z,max-page-size=16384)
        set_target_properties(pvm_probe PROPERTIES
            OUTPUT_NAME "pvm_probe"
            CXX_VISIBILITY_PRESET hidden
            VISIBILITY_INLINES_HIDDEN ON)

        # Load diagnostic: no DT_NEEDED on the compiler sonames, so it always
        # starts even when the linked probe cannot; its dlopen evidence is
        # what the ticket asks for on PAYLOAD_LOAD_FAILED.
        add_library(pvm_probe_clangload SHARED src/probe/pvm_probe_clangload_main.cpp)
        target_include_directories(pvm_probe_clangload PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/include
            ${CMAKE_CURRENT_SOURCE_DIR}/src/probe)
        # Link-time stub only; the device resolves libvm_payload.so from the
        # AVF APEX, and the stub must never be packaged into the APK.
        target_link_libraries(pvm_probe_clangload PRIVATE vm_payload_stub log)
        target_compile_options(pvm_probe_clangload PRIVATE -Wall -Wextra -Werror)
        target_link_options(pvm_probe_clangload PRIVATE -Wl,-z,max-page-size=16384)

        if(FL_PVM_PROBE_CLANG_LIBS AND FL_PVM_PROBE_LLVM_SRC AND FL_PVM_PROBE_LLVM_BUILD)
            add_library(pvm_probe_clang SHARED src/probe/pvm_probe_clang_main.cpp)
            target_include_directories(pvm_probe_clang SYSTEM PRIVATE
                "${FL_PVM_PROBE_LLVM_SRC}/llvm/include"
                "${FL_PVM_PROBE_LLVM_BUILD}/include"
                "${FL_PVM_PROBE_LLVM_SRC}/clang/include"
                "${FL_PVM_PROBE_LLVM_BUILD}/tools/clang/include")
            target_include_directories(pvm_probe_clang PRIVATE
                ${CMAKE_CURRENT_SOURCE_DIR}/include
                ${CMAKE_CURRENT_SOURCE_DIR}/src/probe)
            target_compile_definitions(pvm_probe_clang PRIVATE NDEBUG)
            # The staged libclang is RTTI=ON / EH=OFF: this TU must not unwind
            # into it, hence -fno-exceptions.
            target_compile_options(pvm_probe_clang PRIVATE -fno-exceptions -Wall -Wextra)
            target_link_libraries(pvm_probe_clang PRIVATE vm_payload_stub log
                "${FL_PVM_PROBE_CLANG_LIBS}/libclang-cpp.so"
                "${FL_PVM_PROBE_CLANG_LIBS}/libLLVM.so")
            target_link_options(pvm_probe_clang PRIVATE -Wl,-z,max-page-size=16384)
        endif()
    else()
        # Host smoke: same source as the payload probe, proves the PREDEFS
        # path and the fixture compile on the build machine before any device
        # run.
        add_executable(pvm_probe_host src/probe/pvm_probe_main.cpp src/vm_payload_stub.cpp)
        target_include_directories(pvm_probe_host PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/include
            ${CMAKE_CURRENT_SOURCE_DIR}/src/compiler
            ${CMAKE_CURRENT_SOURCE_DIR}/src/probe)
        target_link_libraries(pvm_probe_host PRIVATE pvm_compiler)
        target_compile_options(pvm_probe_host PRIVATE -Wall -Wextra -Werror)
        if(WIN32)
            target_link_libraries(pvm_probe_host PRIVATE psapi)
        endif()
    endif()
endif()

# --- PVM-03: compiler-service payload ----------------------------------------
# Opt-in like FL_BUILD_PVM_PROBE: default builds (CI, release payload path)
# never compile this block. The engine is a second gate: CI has no Android
# libclang, so FL_PVM_COMPILER_ENGINE defaults to FAKE and the CLANG variant
# additionally requires the staged LLVM paths.
option(FL_BUILD_PVM_COMPILER_PAYLOAD "Build the PVM-03 compile-service payload" OFF)
set(FL_PVM_COMPILER_ENGINE "FAKE" CACHE STRING "Compile engine: FAKE or CLANG")

if(FL_BUILD_PVM_COMPILER_PAYLOAD)
    if(NOT FL_PVM_COMPILER_ENGINE MATCHES "^(FAKE|CLANG)$")
        message(FATAL_ERROR "FL_PVM_COMPILER_PAYLOAD engine must be FAKE or CLANG")
    endif()
    set(FL_PVM_COMPILER_SOURCES src/pvm_compiler_payload_main.cpp)
    if(FL_PVM_COMPILER_ENGINE STREQUAL "CLANG")
        set(FL_PVM_COMPILER_LLVM_LIBS "" CACHE PATH "Dir with staged libLLVM.so/libclang-cpp.so")
        set(FL_PVM_COMPILER_LLVM_SRC "" CACHE PATH "llvm-project source root")
        set(FL_PVM_COMPILER_LLVM_BUILD "" CACHE PATH "arm64 cross-build tree with generated headers")
        if(NOT (FL_PVM_COMPILER_LLVM_LIBS AND FL_PVM_COMPILER_LLVM_SRC AND FL_PVM_COMPILER_LLVM_BUILD))
            message(FATAL_ERROR "CLANG engine needs FL_PVM_COMPILER_LLVM_LIBS/SRC/BUILD")
        endif()
        add_library(pvm_compile_service_clang STATIC src/compile_service/clang_engine.cpp)
        target_include_directories(pvm_compile_service_clang SYSTEM PRIVATE
            "${FL_PVM_COMPILER_LLVM_SRC}/llvm/include"
            "${FL_PVM_COMPILER_LLVM_BUILD}/include"
            "${FL_PVM_COMPILER_LLVM_SRC}/clang/include"
            "${FL_PVM_COMPILER_LLVM_BUILD}/tools/clang/include")
        target_include_directories(pvm_compile_service_clang PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/src/compile_service)
        # Staged libclang is RTTI=ON / EH=OFF: this TU must not unwind into it.
        # Same visibility contract as fl_pvm_add_compile_service_library: a
        # static library keeps default visibility otherwise and leaks
        # make_clang_engine/ClangEngine into the .so's dynamic symbol table,
        # where AVmPayload_main must be the only project symbol.
        target_compile_options(pvm_compile_service_clang PRIVATE
            -fno-exceptions -fvisibility=hidden -fvisibility-inlines-hidden
            -Wall -Wextra)
    endif()

    if(ANDROID)
        add_library(pvm_compiler_payload SHARED ${FL_PVM_COMPILER_SOURCES})
        target_include_directories(pvm_compiler_payload PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/include
            ${CMAKE_CURRENT_SOURCE_DIR}/src/compile_service)
        if(FL_PVM_COMPILER_ENGINE STREQUAL "CLANG")
            target_compile_definitions(pvm_compiler_payload PRIVATE FL_PVM_COMPILER_ENGINE_CLANG=1)
            target_link_libraries(pvm_compiler_payload PRIVATE pvm_compile_service
                pvm_compile_service_clang vm_payload_stub log
                "${FL_PVM_COMPILER_LLVM_LIBS}/libclang-cpp.so"
                "${FL_PVM_COMPILER_LLVM_LIBS}/libLLVM.so")
        else()
            target_link_libraries(pvm_compiler_payload PRIVATE pvm_compile_service
                vm_payload_stub log)
        endif()
        # EH=OFF ABI: nothing in the payload may rely on unwinding.
        target_compile_options(pvm_compiler_payload PRIVATE -Wall -Wextra -Werror
            -fno-exceptions)
        target_link_options(pvm_compiler_payload PRIVATE -Wl,-z,max-page-size=16384)
        set_target_properties(pvm_compiler_payload PROPERTIES
            OUTPUT_NAME "pvm_compiler_payload"
            CXX_VISIBILITY_PRESET hidden
            VISIBILITY_INLINES_HIDDEN ON)
    else()
        # Host twin: same session over loopback TCP / Unix socket for the
        # zero-device e2e chain (real distcc client -> Java bridge -> here).
        add_executable(pvm_compiler_payload_host ${FL_PVM_COMPILER_SOURCES})
        target_include_directories(pvm_compiler_payload_host PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/include
            ${CMAKE_CURRENT_SOURCE_DIR}/src/compile_service)
        # No fastlane_payload_core: the twin carries its own portable listener.
        target_link_libraries(pvm_compiler_payload_host PRIVATE pvm_compile_service)
        # Same flags as the device target: the twin is the proof this TU needs
        # no exceptions, not a different program.
        target_compile_options(pvm_compiler_payload_host PRIVATE -Wall -Wextra -Werror
            -fno-exceptions)
    endif()
endif()
