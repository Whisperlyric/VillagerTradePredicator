package com.villagertradepredicator.core.locate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import com.villagertradepredicator.core.rng.XoroshiroStream;

/**
 * Recovers "which round of the shared sequence is this villager consuming" (the offset)
 * from observed offers. Observations must be consecutive rounds — e.g. one villager
 * screenshotted after each lectern cycle, or a single screen (one-round window). Multiple
 * matches mean the observation window was too small; the caller widens it and rescans.
 */
public final class OffsetLocator {
    private OffsetLocator() {}

    /**
     * @param observedRounds fingerprints of consecutive observed rounds,
     *        {@code observedRounds[0]} being the earliest
     * @return every offset {@code o} where rounds {@code o..o+n-1} match the observations
     */
    public static List<Integer> findOffsets(long worldSeed, SequenceConfig cfg, TradeSetDef set,
            SimContext ctx, List<List<OfferFingerprint>> observedRounds, int maxOffset) {
        if (observedRounds.isEmpty()) {
            return List.of();
        }
        int window = observedRounds.size();
        XoroshiroStream rng = TradeSequences.create(worldSeed, cfg, set.randomSequence());
        RoundIterator iterator = new RoundIterator(set, rng, ctx);

        Deque<List<OfferFingerprint>> history = new ArrayDeque<>(window);
        List<Integer> hits = new ArrayList<>();
        for (int offset = 0; offset <= maxOffset; offset++) {
            RoundIterator.Round round = iterator.next();
            history.addLast(round.fingerprints());
            if (history.size() > window) {
                history.removeFirst();
            }
            if (history.size() == window && matchesAt(history, observedRounds)) {
                hits.add(offset - window + 1);
            }
        }
        return hits;
    }

    private static boolean matchesAt(Deque<List<OfferFingerprint>> history,
            List<List<OfferFingerprint>> observedRounds) {
        int i = 0;
        for (List<OfferFingerprint> round : history) {
            if (!round.equals(observedRounds.get(i++))) {
                return false;
            }
        }
        return true;
    }
}
