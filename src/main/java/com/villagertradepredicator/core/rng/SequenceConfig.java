package com.villagertradepredicator.core.rng;

/**
 * Mirrors the configurable fields of the vanilla {@code RandomSequences} saved data.
 * Vanilla defaults are salt=0, includeWorldSeed=true, includeSequenceId=true; a world
 * that changed them would produce different sequences, so the client layer reads the
 * real values (accessor mixin) instead of assuming this default.
 */
public record SequenceConfig(long salt, boolean includeWorldSeed, boolean includeSequenceId) {
    public static final SequenceConfig DEFAULT = new SequenceConfig(0L, true, true);
}
