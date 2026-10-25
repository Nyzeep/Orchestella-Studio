// Deterministic compile engine (see fake_engine.cpp).

#ifndef FASTLANE_PVM_FAKE_ENGINE_H_
#define FASTLANE_PVM_FAKE_ENGINE_H_

#include <memory>

#include "compile_service.h"

namespace fl {
namespace pvm {

/** Engine that always returns the fixed linkable ELF object. */
std::unique_ptr<CompileEngine> make_fake_engine();

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_FAKE_ENGINE_H_
