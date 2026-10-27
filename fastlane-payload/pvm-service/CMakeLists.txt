# Portable verification build for the PVM-03 compile service.
#
# Why this exists: the product build (../CMakeLists.txt) compiles the whole
# FLN1 payload, whose transport includes <linux/vm_sockets.h> and therefore
# cannot build on a non-Linux host. The compile service itself has no such
# dependency (it carries its own thin socket layer), so this project builds and
# runs it — library, host twin and tests — anywhere a C++17 compiler lives.
#
#   cmake -S fastlane-payload/pvm-service -B build/pvm-service
#   cmake --build build/pvm-service
#   ctest --test-dir build/pvm-service --output-on-failure
#
# It is a verification entry point, not a second product: both this file and
# the product build take targets and sources from cmake/pvm-compile-service.cmake.

cmake_minimum_required(VERSION 3.22)

project(pvm_compile_service_standalone LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(FL_ENABLE_WERROR "Treat compiler warnings as errors" ON)

find_package(Threads REQUIRED)

get_filename_component(FL_PVM_PAYLOAD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)

include("${FL_PVM_PAYLOAD_ROOT}/cmake/pvm-compile-service.cmake")

fl_pvm_add_compile_service_library(pvm_compile_service)

add_executable(pvm_compiler_payload_host
    ${FL_PVM_PAYLOAD_ROOT}/src/pvm_compiler_payload_main.cpp)
target_link_libraries(pvm_compiler_payload_host PRIVATE pvm_compile_service)
target_compile_options(pvm_compiler_payload_host PRIVATE -Wall -Wextra -Werror -Wpedantic
    -fno-exceptions)

enable_testing()
fl_pvm_add_test_target(pvm_compile_service_tests pvm_compile_service)
add_test(NAME pvm_compile_service_tests COMMAND pvm_compile_service_tests)

foreach(suite argv_policy cc1_defaults distcc_server axp1_server pvm_e2e worker_pool)
    add_test(NAME pvm_compile_service_${suite} COMMAND pvm_compile_service_tests)
    set_tests_properties(pvm_compile_service_${suite} PROPERTIES
        ENVIRONMENT "FL_TEST_FILTER=${suite}")
endforeach()
