package com.villagertradepredicator.core.rng;

import java.util.Optional;

import net.minecraft.resources.Identifier;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Derives the named random sequence behind a trade set, mirroring
 * {@code RandomSequences.get(id, worldSeed)}.
 *
 * <p>Every {@code minecraft:trade_set/<profession>/level_<n>} set owns one Xoroshiro128++
 * stream seeded from the world seed; all villagers of that profession and level in a world
 * consume the same stream in generation order. The vanilla {@link RandomSequence} pipeline
 * is reused wholesale so the derivation stays byte-identical to the game (the sequence-id
 * hash itself changed between 26.1 and 26.2 — raw MD5 layout details differ from the
 * deprecated {@code UUID.nameUUIDFromBytes} route — which is exactly why no part of the
 * chain is reimplemented here).</p>
 */
public final class TradeSequences {
    private TradeSequences() {}

    public static XoroshiroRandomSource create(long worldSeed, SequenceConfig cfg, Identifier sequenceId) {
        if (cfg.equals(SequenceConfig.DEFAULT)) {
            return (XoroshiroRandomSource) new RandomSequence(worldSeed, Optional.of(sequenceId)).random();
        }
        // Custom sequence config (salt / world-seed inclusion tweaked by a datapack or command):
        // same building blocks the vanilla RandomSequences codec applies.
        long base = (cfg.includeWorldSeed() ? worldSeed : 0L) ^ cfg.salt();
        RandomSupport.Seed128bit seed = RandomSupport.upgradeSeedTo128bitUnmixed(base);
        if (cfg.includeSequenceId()) {
            seed = seed.xor(RandomSequence.seedForKey(sequenceId));
        }
        RandomSupport.Seed128bit mixed = seed.mixed();
        return new XoroshiroRandomSource(mixed.seedLo(), mixed.seedHi());
    }

    /** Named-sequence id for a profession/level trade set, e.g. {@code minecraft:trade_set/librarian/level_1}. */
    public static Identifier sequenceId(Identifier profession, int level) {
        return Identifier.fromNamespaceAndPath(profession.getNamespace(),
                "trade_set/" + profession.getPath() + "/level_" + level);
    }
}
