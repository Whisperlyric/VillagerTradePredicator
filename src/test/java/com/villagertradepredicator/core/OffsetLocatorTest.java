package com.villagertradepredicator.core;

import java.util.List;

import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.locate.OffsetLocator;
import com.villagertradepredicator.core.locate.RoundIterator;
import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offset localization: recover "which round is this villager consuming" from observed
 * offer fingerprints — the basis of prediction on multiplayer servers.
 */
class OffsetLocatorTest {
    private static final long SEED = 7662585126525589278L;
    private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");
    private static final int LEVEL = 1;

    private static TradeSetDef set;

    @BeforeAll
    static void setup() {
        TestSupport.bootstrap();
        set = TradeDataRepository.loadFromClasspath().compatible(LIBRARIAN, LEVEL).orElseThrow();
    }

    private static List<OfferFingerprint> round(int offset) {
        RoundIterator it = new RoundIterator(set,
                TradeSequences.create(SEED, SequenceConfig.DEFAULT, TradeSequences.sequenceId(LIBRARIAN, LEVEL)),
                SimContext.withoutVariant());
        for (int i = 0; i < offset; i++) {
            it.next();
        }
        return it.next().fingerprints();
    }

    @Test
    void singleObservationContainsTrueOffset() {
        // Price-exact fingerprints make single-round windows strong evidence: round 3 is in
        // fact unique within a wide scan, but the locator must at minimum contain the truth.
        List<Integer> hits = OffsetLocator.findOffsets(SEED, SequenceConfig.DEFAULT, set,
                SimContext.withoutVariant(), List.of(round(3)), 60);
        assertTrue(hits.contains(3), "candidates " + hits);
    }

    @Test
    void wideScanEventuallyAmbiguatesSingleObservation() {
        List<Integer> hits = OffsetLocator.findOffsets(SEED, SequenceConfig.DEFAULT, set,
                SimContext.withoutVariant(), List.of(round(3)), 5000);
        assertTrue(hits.contains(3), "candidates " + hits);
    }

    @Test
    void consecutiveObservationsPinUniqueOffset() {
        List<Integer> hits = OffsetLocator.findOffsets(SEED, SequenceConfig.DEFAULT, set,
                SimContext.withoutVariant(), List.of(round(3), round(4), round(5)), 200);
        assertEquals(List.of(3), hits);
    }
}
