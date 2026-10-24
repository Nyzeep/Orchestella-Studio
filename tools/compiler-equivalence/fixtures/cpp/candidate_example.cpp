/* Candidate example for fixtures/cpp: exercises template instantiation and
 * the exception unwind path. Compile with:
 *   c++ -c candidate_example.cpp -o candidate.o */
template <int N> struct Adder {
    static int apply(int x) { return x + N; }
};

extern "C" int fixture_entry() {
    try {
        throw Adder<6>{};
    } catch (const Adder<6>& adder) {
        return adder.apply(36);
    }
    return -1;
}
