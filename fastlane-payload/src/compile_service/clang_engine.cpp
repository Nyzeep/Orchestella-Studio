// libclang-backed compile engine. Built only when
// FL_PVM_COMPILER_ENGINE=CLANG; the default and CI builds use FakeEngine.
//
// Everything stays in memory (DV-PVM-01 01a: the payload domain has no
// writable file): the preprocessed source arrives as a MemoryBufferRef, the
// object leaves through CompilerInstance::setOutputStream, and diagnostics go
// to a collecting consumer instead of stderr.
//
// Compiled with -fno-exceptions: the staged libclang is RTTI=ON / EH=OFF, so
// an unwind crossing that boundary would be undefined behaviour (决策地图
// §11). Every failure below is a status, never a throw.

#include "clang_engine.h"

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "clang/Basic/Diagnostic.h"
#include "clang/Basic/Version.h"
#include "clang/CodeGen/CodeGenAction.h"
#include "clang/Frontend/CompilerInstance.h"
#include "clang/Frontend/CompilerInvocation.h"
#include "clang/Frontend/FrontendOptions.h"
#include "clang/Sema/Sema.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/Support/TargetSelect.h"
#include "llvm/Support/raw_ostream.h"

namespace fl {
namespace pvm {
namespace {

// EmitObjAction needs the AArch64 tables registered; idempotent and safe
// under the process-wide once flag.
void initialize_targets_once() {
    static std::atomic<bool> done{false};
    if (done.exchange(true)) {
        return;
    }
    LLVMInitializeAArch64TargetInfo();
    LLVMInitializeAArch64Target();
    LLVMInitializeAArch64TargetMC();
    LLVMInitializeAArch64AsmParser();
    LLVMInitializeAArch64AsmPrinter();
    LLVMInitializeAArch64Disassembler();
}

// Collects diagnostics in emission order. TextDiagnosticBuffer keeps four
// separate lists and lost the interleaving, which scrambled errors and their
// notes in SERR.
class CollectingConsumer final : public clang::DiagnosticConsumer {
public:
    void HandleDiagnostic(clang::DiagnosticsEngine::Level level,
                          const clang::Diagnostic& info) override {
        clang::DiagnosticConsumer::HandleDiagnostic(level, info);
        llvm::SmallString<256> text;
        info.FormatDiagnostic(text);
        text_.append(text.begin(), text.end());
        text_.push_back('\n');
    }

    const std::string& text() const { return text_; }

private:
    std::string text_;
};

}  // namespace

class ClangEngine : public CompileEngine {
public:
    explicit ClangEngine(const ClangEngineConfig& config) : config_(config) {}

    EngineResult compile(const EngineRequest& request) override {
        EngineResult result;
        if (request.source.empty()) {
            result.status = JobStatus::kCompileError;
            result.diagnostics = "error: empty preprocessed input\n";
            return result;
        }
        if (request.cancel != nullptr && request.cancel->cancelled.load()) {
            result.status = JobStatus::kJobTimeout;
            return result;
        }
        initialize_targets_once();

        clang::CompilerInstance instance;
        auto consumer = std::make_unique<CollectingConsumer>();
        CollectingConsumer* diagnostics = consumer.get();
        instance.createDiagnostics(consumer.release(), /*ShouldOwnClient=*/true);

        // Driver flags from the allow list map one-to-one onto -cc1; "-c" is
        // implied by EmitObjAction, "-x" becomes part of the input kind, and
        // "--target" is replaced by the triple the payload was configured for.
        std::vector<std::string> storage;
        storage.emplace_back("clang");
        storage.emplace_back("-cc1");
        storage.emplace_back("-triple");
        storage.push_back(config_.target_triple);
        storage.emplace_back("-emit-obj");
        storage.emplace_back("-x");
        storage.push_back(request.language == "c++" ? "c++-cpp-output" : "cpp-output");
        // No "-fno-crash-diagnostics" here: it is a driver option, and -cc1
        // rejects it as an unknown argument, which made CreateFromArgs fail for
        // every single job (DV-PVM-02). The payload has no writable directory
        // for crash diagnostics anyway.
        for (const std::string& flag : request.flags) {
            if (flag == "-c") {
                continue;
            }
            // The driver form is not a -cc1 form and would be rejected.
            storage.push_back(flag == "-pthread" ? "-pthread" : flag);
        }
        std::vector<const char*> args;
        args.reserve(storage.size());
        for (const std::string& entry : storage) {
            args.push_back(entry.c_str());
        }

        clang::CompilerInvocation& invocation = instance.getInvocation();
        if (!clang::CompilerInvocation::CreateFromArgs(invocation, args,
                                                       instance.getDiagnostics())) {
            // A rejected flag is an argv problem, not a load failure: the
            // allow list and the -cc1 surface drifted apart.
            result.status = JobStatus::kArgvRejected;
            result.diagnostics = diagnostics->text();
            return result;
        }

        // The payload domain has no writable file of any kind: the object is
        // captured through an in-memory stream (the clang-repl route).
        llvm::SmallVector<char, 0> object_buffer;
        instance.setOutputStream(
            std::make_unique<llvm::raw_svector_ostream>(object_buffer));

        clang::FrontendOptions& frontend = invocation.getFrontendOpts();
        frontend.OutputFile.clear();
        // No dependency file, no module cache, no serialized diagnostics:
        // every one of those is a file write even when the output stream is
        // in memory (CompilerInstance::createDependencyFileGen).
        frontend.Inputs.clear();
        const clang::Language language =
            request.language == "c++" ? clang::Language::CXX : clang::Language::C;
        frontend.Inputs.push_back(clang::FrontendInputFile(
            llvm::MemoryBufferRef(request.source, "job.i"),
            clang::InputKind(language, clang::InputKind::Source,
                             /*PP=*/true)));

        clang::EmitObjAction action;
        const bool executed = instance.ExecuteAction(action);
        if (!executed || instance.getDiagnostics().hasErrorOccurred()) {
            result.status = JobStatus::kCompileError;
            result.diagnostics = diagnostics->text();
            if (result.diagnostics.empty()) {
                result.diagnostics = "error: compilation failed\n";
            }
            return result;
        }
        if (std::unique_ptr<llvm::raw_pwrite_stream> stream =
                instance.takeOutputStream()) {
            stream->flush();
        }
        if (object_buffer.empty()) {
            result.status = JobStatus::kCompileError;
            result.diagnostics = "error: compiler produced no object\n";
            return result;
        }
        result.status = JobStatus::kOk;
        result.object.assign(object_buffer.begin(), object_buffer.end());
        // Warnings survive a successful compile and belong in SERR.
        result.diagnostics = diagnostics->text();
        return result;
    }

    std::string compiler_version() const override {
        return clang::getClangFullVersion();
    }

private:
    ClangEngineConfig config_;
};

std::unique_ptr<CompileEngine> make_clang_engine(const ClangEngineConfig& config) {
    return std::make_unique<ClangEngine>(config);
}

}  // namespace pvm
}  // namespace fl
