/* FakeEngine fixture: the object compiled from this file is embedded verbatim
   in fastlane-payload/src/compile_service/fake_engine.cpp, so the zero-device
   e2e chain has a deterministic, genuinely linkable ELF object.

   It satisfies the PVM-05 equivalence fixture contract
   (tools/compiler-equivalence/fixtures/c/main.c calls fixture_entry), which is
   what lets compare_objects.py reach the LINK level instead of stopping at
   INCONCLUSIVE. No includes, no libc, no relocation against anything. */
int pvm_fake_engine_answer(void) {
    return 42;
}

int fixture_entry(void) {
    return pvm_fake_engine_answer();
}
