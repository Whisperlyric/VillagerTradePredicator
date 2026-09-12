package com.villagertradepredicator.core.model;

import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Loot-number providers as consumed by trade generation. The float semantics are the
 * vanilla ones: {@code uniform} draws via {@link Mth#nextFloat(RandomSource, float, float)}
 * (one {@code nextFloat} draw of the stream), {@code sum} accumulates its summands in
 * order, and {@code getInt} is {@code Math.round(getFloat)} — so a randomized price
 * consumes exactly the same stream draws as the game.
 */
public interface NumberProvider {
    float getFloat(RandomSource rng);

    default int getInt(RandomSource rng) {
        return Math.round(getFloat(rng));
    }

    static NumberProvider constant(float value) {
        return rng -> value;
    }

    /** Argument order matters: vanilla evaluates min, then max, then draws. */
    static NumberProvider uniform(NumberProvider min, NumberProvider max) {
        return rng -> Mth.nextFloat(rng, min.getFloat(rng), max.getFloat(rng));
    }

    static NumberProvider sum(List<NumberProvider> summands) {
        return rng -> {
            float total = 0f;
            for (NumberProvider summand : summands) {
                total += summand.getFloat(rng);
            }
            return total;
        };
    }
}
