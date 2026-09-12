package com.villagertradepredicator.core.rng;

import net.minecraft.resources.Identifier;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.level.levelgen.RandomSupport;

/**
 * Derives the named random sequence behind a trade set, mirroring
 * {@code RandomSequences.get(id, worldSeed)}.
 *
 * <p>Every {@code minecraft:trade_set/<profession>/level_<n>} set owns one Xoroshiro128++
 * stream seeded from the world seed; all villagers of that profession and level in a world
 * consume the same stream in generation order. The derivation reuses the vanilla pure-math
 * building blocks ({@code RandomSupport}, {@code RandomSequence.seedForKey}, whose MD5
 * layout differs from {@code UUID.nameUUIDFromBytes} — a known trap) so it stays
 * byte-identical to the game, and returns a {@link XoroshiroStream} whose draw parity with
 * the vanilla generator is locked by {@code XoroshiroParityTest}.</p>
 */
public final class TradeSequences {
    private TradeSequences() {}

    public static XoroshiroStream create(long worldSeed, SequenceConfig cfg, Identifier sequenceId) {
        long base = (cfg.includeWorldSeed() ? worldSeed : 0L) ^ cfg.salt();
        RandomSupport.Seed128bit seed = RandomSupport.upgradeSeedTo128bitUnmixed(base);
        if (cfg.includeSequenceId()) {
            seed = seed.xor(RandomSequence.seedForKey(sequenceId));
        }
        RandomSupport.Seed128bit mixed = seed.mixed();
        return new XoroshiroStream(mixed.seedLo(), mixed.seedHi());
    }

    /** Named-sequence id for a profession/level trade set, e.g. {@code minecraft:trade_set/librarian/level_1}. */
    public static Identifier sequenceId(Identifier profession, int level) {
        return Identifier.fromNamespaceAndPath(profession.getNamespace(),
                "trade_set/" + profession.getPath() + "/level_" + level);
    }
}
