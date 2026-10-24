/* Error candidate: compiles but never links (missing_function has no
 * definition). Use it to exercise the judgement paths around link failures:
 * one broken object -> DIFFERENT, two broken objects -> INCONCLUSIVE. */
extern void missing_function(void);

int fixture_entry(void) {
    missing_function();
    return 0;
}
