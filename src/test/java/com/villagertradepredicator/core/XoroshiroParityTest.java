package com.villagertradepredicator.core;

import java.util.Optional;

import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.locate.RoundIterator;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.StreamSnapshot;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.rng.XoroshiroStream;
import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks {@link XoroshiroStream} (and the derivation behind it) to the vanilla classes
 * bit-for-bit: identical seeds/states must produce identical draw sequences across every
 * primitive the simulator touches. This is what makes a snapshot captured anywhere
 * (scanner, singleplayer accessor) resume to exactly the game's position.
 */
class XoroshiroParityTest {
    private static final long SEED = 7662585126525589278L;
    private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");

    @BeforeAll
    static void setup() {
        TestSupport.bootstrap();
    }

    private static void assertSameDraws(RandomSource ours, RandomSource vanilla, int draws) {
        for (int i = 0; i < draws; i++) {
            switch (i % 6) {
                case 0 -> assertEquals(vanilla.nextLong(), ours.nextLong(), "nextLong @" + i);
                case 1 -> assertEquals(vanilla.nextInt(), ours.nextInt(), "nextInt @" + i);
                case 2 -> assertEquals(vanilla.nextInt(1 + (i % 64)), ours.nextInt(1 + (i % 64)), "nextInt(b) @" + i);
                case 3 -> assertEquals(vanilla.nextFloat(), ours.nextFloat(), 0f, "nextFloat @" + i);
                case 4 -> assertEquals(vanilla.nextDouble(), ours.nextDouble(), 0d, "nextDouble @" + i);
                default -> assertEquals(vanilla.nextBoolean(), ours.nextBoolean(), "nextBoolean @" + i);
            }
        }
    }

    @Test
    void drawSequencesMatchVanillaFromArbitraryStates() {
        long[][] states = {
                {0xDDF7BE1789D3DABEL, 0x48F9718A062D2DDAL}, // librarian L1 golden state (mixed)
                {0L, 1L},
                {12345L, -98765L},
                {-1L, -1L},
                {0L, 0L}, // all-zero substitution path
        };
        for (long[] state : states) {
            assertSameDraws(new XoroshiroStream(state[0], state[1]),
                    new XoroshiroRandomSource(state[0], state[1]), 50_000);
        }
    }

    @Test
    void gaussiansMatchVanilla() {
        XoroshiroStream ours = new XoroshiroStream(SEED, -SEED);
        XoroshiroRandomSource vanilla = new XoroshiroRandomSource(SEED, -SEED);
        for (int i = 0; i < 2_000; i++) {
            assertEquals(vanilla.nextGaussian(), ours.nextGaussian(), 0d, "gaussian @" + i);
        }
    }

    @Test
    void derivationMatchesVanillaRandomSequencePipeline() {
        Identifier id = TradeSequences.sequenceId(LIBRARIAN, 1);
        RandomSource vanilla = new RandomSequence(SEED, Optional.of(id)).random();
        assertSameDraws(TradeSequences.create(SEED, SequenceConfig.DEFAULT, id), vanilla, 100_000);
    }

    @Test
    void snapshotResumeIsTransparent() {
        TradeDataRepository repo = TradeDataRepository.loadFromClasspath();
        TradeSetDef set = repo.compatible(LIBRARIAN, 1).orElseThrow();

        RoundIterator continuous = new RoundIterator(set,
                TradeSequences.create(SEED, SequenceConfig.DEFAULT, TradeSequences.sequenceId(LIBRARIAN, 1)),
                SimContext.withoutVariant());
        for (int i = 0; i < 37; i++) {
            continuous.next();
        }
        StreamSnapshot snapshot = continuous.snapshot();

        RoundIterator resumed = RoundIterator.resume(set, SimContext.withoutVariant(), snapshot);
        for (int i = 0; i < 100; i++) {
            assertEquals(continuous.next().offers(), resumed.next().offers(), "round " + (37 + i));
        }
        assertEquals(continuous.snapshot().nextOffset(), resumed.snapshot().nextOffset());
        assertEquals(continuous.snapshot().lo(), resumed.snapshot().lo());
        assertEquals(continuous.snapshot().hi(), resumed.snapshot().hi());
    }
}
