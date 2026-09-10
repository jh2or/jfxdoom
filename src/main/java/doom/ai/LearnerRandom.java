package doom.ai;

/** SplitMix64 stream owned and persisted by the learner, independent of Doom's RNG. */
final class LearnerRandom {
    long state;
    LearnerRandom(long seed) { state = seed; }
    long nextLong() {
        long z = (state += 0x9e3779b97f4a7c15L);
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
    double nextDouble() { return (nextLong() >>> 11) * 0x1.0p-53; }
    int nextInt(int bound) { return (int) (nextDouble() * bound); }
}
