// libclang-backed engine (built only with FL_PVM_COMPILER_ENGINE=CLANG).
//
// The compile sequence is the DV-PVM-01 01b proven route: CompilerInvocation
// built from -cc1 style args, input as a MemoryBufferRef, object captured by
// setOutputStream(raw_svector_ostream) — zero file I/O, zero subprocesses.
// The staged libclang is RTTI=ON / EH=OFF, so this TU compiles with
// -fno-exceptions and never unwinds into LLVM.

#ifndef FASTLANE_PVM_CLANG_ENGINE_H_
#define FASTLANE_PVM_CLANG_ENGINE_H_

#include <memory>
#include <string>

#include "compile_service.h"

namespace fl {
namespace pvm {

struct ClangEngineConfig {
    std::string target_triple = "aarch64-unknown-linux-android";
};

std::unique_ptr<CompileEngine> make_clang_engine(const ClangEngineConfig& config);

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_CLANG_ENGINE_H_
