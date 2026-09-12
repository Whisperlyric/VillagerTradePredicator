package com.villagertradepredicator.core.rng;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A state-capturable replica of the vanilla Xoroshiro128++ stream. The vanilla generator
 * cannot be snapshotted through its classes (state fields are private, reading them needs
 * a mixin), and offset prediction fundamentally requires capturing/resuming the 128-bit
 * state ("start from round N"). Every algorithm here was transcribed from the 26.2
 * bytecode of {@code Xoroshiro128PlusPlus}/{@code XoroshiroRandomSource} and is locked to
 * the vanilla classes by {@code XoroshiroParityTest} (identical draw sequences for
 * identical seeds and states), so a future vanilla change fails loudly instead of silently.
 */
public final class XoroshiroStream implements RandomSource {
    // Vanilla's all-zero substitution inside Xoroshiro128PlusPlus(long, long)
    private static final long ZERO_SUBSTITUTE_LO = -7046029254386353131L; // 0x9E3779B97F4A7C15
    private static final long ZERO_SUBSTITUTE_HI = 7640891576956012809L;  // 0x6A09E667F3BCC909

    private long lo;
    private long hi;
    private final MarsagliaPolarGaussian gaussianSource = new MarsagliaPolarGaussian(this);

    public XoroshiroStream(long lo, long hi) {
        if ((lo | hi) == 0L) {
            lo = ZERO_SUBSTITUTE_LO;
            hi = ZERO_SUBSTITUTE_HI;
        }
        this.lo = lo;
        this.hi = hi;
    }

    /** Current 128-bit internal state — persist it to resume the stream at this exact position. */
    public long[] state() {
        return new long[]{lo, hi};
    }

    public long lo() {
        return lo;
    }

    public long hi() {
        return hi;
    }

    public void restore(long lo, long hi) {
        this.lo = lo;
        this.hi = hi;
        this.gaussianSource.reset();
    }

    @Override
    public long nextLong() {
        long result = Long.rotateLeft(lo + hi, 17) + lo;
        long newHi = hi ^ lo;
        lo = Long.rotateLeft(lo, 49) ^ newHi ^ (newHi << 21);
        hi = Long.rotateLeft(newHi, 28);
        return result;
    }

    @Override
    public int nextInt() {
        return (int) nextLong();
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        long product = Integer.toUnsignedLong(nextInt()) * bound;
        long low = product & 0xFFFFFFFFL;
        int rejection = Integer.remainderUnsigned(-bound, bound);
        while (low < rejection) {
            product = Integer.toUnsignedLong(nextInt()) * bound;
            low = product & 0xFFFFFFFFL;
        }
        return (int) (product >>> 32);
    }

    @Override
    public boolean nextBoolean() {
        return (nextLong() & 1L) != 0L;
    }

    @Override
    public float nextFloat() {
        return (float) nextBits(24) * 5.9604645E-8f;
    }

    @Override
    public double nextDouble() {
        return (double) nextBits(53) * 1.1102230246251565E-16;
    }

    @Override
    public double nextGaussian() {
        return gaussianSource.nextGaussian();
    }

    private long nextBits(int bits) {
        return nextLong() >>> (64 - bits);
    }

    @Override
    public void setSeed(long seed) {
        throw new UnsupportedOperationException("XoroshiroStream is position-managed; construct a new stream instead");
    }

    @Override
    public RandomSource fork() {
        return new XoroshiroStream(nextLong(), nextLong());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        throw new UnsupportedOperationException("positional randomness is not used by trade generation");
    }

    /** Vanilla's Gaussian generator riding on this stream (never drawn by trade generation). */
    private static final class MarsagliaPolarGaussian {
        private final XoroshiroStream random;
        private double nextValue;
        private boolean hasNextValue;

        MarsagliaPolarGaussian(XoroshiroStream random) {
            this.random = random;
        }

        void reset() {
            hasNextValue = false;
        }

        double nextGaussian() {
            if (hasNextValue) {
                hasNextValue = false;
                return nextValue;
            }
            double x;
            double y;
            double square;
            do {
                x = 2.0 * random.nextDouble() - 1.0;
                y = 2.0 * random.nextDouble() - 1.0;
                square = x * x + y * y;
            } while (square >= 1.0 || square == 0.0);
            double scale = Math.sqrt(-2.0 * Math.log(square) / square);
            nextValue = y * scale; // vanilla keeps the SECOND value and returns the first
            hasNextValue = true;
            return x * scale;
        }
    }
}
