# Single definition of the PVM-03 compile-service target set.
#
# Included by fastlane-payload/CMakeLists.txt (the product build) and by
# fastlane-payload/pvm-service/CMakeLists.txt (the portable verification build
# used on hosts where the FLN1 core cannot compile — it pulls in
# <linux/vm_sockets.h>). Keeping the sources and flags in one place is what
# stops the two entry points from drifting.

set(FL_PVM_COMPILE_SERVICE_SOURCES
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/compile_service.cpp
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/argv_policy.cpp
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/distcc_server.cpp
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/axp1_server.cpp
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/fake_engine.cpp
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/net.cpp
    ${FL_PVM_PAYLOAD_ROOT}/src/compile_service/worker_pool.cpp
)

set(FL_PVM_COMPILE_SERVICE_TESTS
    ${FL_PVM_PAYLOAD_ROOT}/tests/pvm_test_main.cpp
    ${FL_PVM_PAYLOAD_ROOT}/tests/test_argv_policy.cpp
    ${FL_PVM_PAYLOAD_ROOT}/tests/test_distcc_server.cpp
    ${FL_PVM_PAYLOAD_ROOT}/tests/test_axp1_server.cpp
    ${FL_PVM_PAYLOAD_ROOT}/tests/test_compile_service_e2e.cpp
    ${FL_PVM_PAYLOAD_ROOT}/tests/test_worker_pool.cpp
)

# Golden fixtures: one definition, used by both test entry points (the product
# build and the portable verification build) so the paths cannot drift.
set(FL_PVM_DISTCC_GOLDEN_DIR
    "${FL_PVM_PAYLOAD_ROOT}/../runtime/fastlane-distcc/src/test/resources/distcc")
set(FL_PVM_AXP1_GOLDEN_PATH
    "${FL_PVM_PAYLOAD_ROOT}/../runtime/fastlane-distcc/src/test/resources/axp1/golden-v1.hex")

# The service core is deliberately independent of fastlane_payload_core: the
# payload .so must not carry the FLN1 transport, and the FLN1 core is
# Linux-only.
function(fl_pvm_add_compile_service_library target)
    add_library(${target} STATIC ${FL_PVM_COMPILE_SERVICE_SOURCES})
    target_include_directories(${target}
        PUBLIC
            ${FL_PVM_PAYLOAD_ROOT}/include
            ${FL_PVM_PAYLOAD_ROOT}/src/compile_service)
    target_link_libraries(${target} PUBLIC Threads::Threads)
    if(WIN32)
        # The host twin's loopback listener is plain Winsock.
        target_link_libraries(${target} PUBLIC ws2_32)
    endif()
    # The staged libclang is RTTI=ON / EH=OFF; an unwind must never cross into
    # it (决策地图 §11). Applies to the whole service, not just the engine TU.
    if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(${target} PRIVATE -fno-exceptions)
        # The payload's only intended ABI surface is AVmPayload_main; a static
        # library keeps default visibility otherwise and leaks every helper
        # into the .so's dynamic symbol table.
        target_compile_options(${target} PRIVATE -fvisibility=hidden
            -fvisibility-inlines-hidden)
    endif()
    if(FL_ENABLE_WERROR)
        target_compile_options(${target} PRIVATE -Wall -Wextra -Werror -Wpedantic)
    endif()
endfunction()

function(fl_pvm_add_test_target target library)
    add_executable(${target} ${FL_PVM_COMPILE_SERVICE_TESTS})
    target_link_libraries(${target} PRIVATE ${library})
    target_include_directories(${target} PRIVATE
        ${FL_PVM_PAYLOAD_ROOT}/src/compile_service
        ${FL_PVM_PAYLOAD_ROOT}/tests)
    target_compile_definitions(${target} PRIVATE
        FL_PVM_DISTCC_GOLDEN_DIR="${FL_PVM_DISTCC_GOLDEN_DIR}"
        FL_PVM_AXP1_GOLDEN_PATH="${FL_PVM_AXP1_GOLDEN_PATH}")
    target_compile_options(${target} PRIVATE -Wall -Wextra -Werror -Wpedantic)
endfunction()
