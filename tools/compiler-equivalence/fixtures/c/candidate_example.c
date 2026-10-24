/* Candidate example for fixtures/c. Compile with:
 *   cc -c candidate_example.c -o candidate.o
 * then compare two builds of it with compare_objects.py --fixtures fixtures/c */
int fixture_entry(void) { return 6 * 7; }
