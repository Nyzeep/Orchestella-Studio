/* Fixture contract (PVM-05): every candidate translation unit defines
 * fixture_entry(); the fixture's main() links against it. See
 * README.zh-CN.md for the candidate example and the tool usage. */
extern int fixture_entry(void);

int main(void) { return fixture_entry(); }
