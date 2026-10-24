/* Fixture contract (PVM-05), C++ variant: candidates define an
 * extern "C" fixture_entry(). Linking goes through the C++ driver. */
extern "C" int fixture_entry(void);

int main() { return fixture_entry(); }
