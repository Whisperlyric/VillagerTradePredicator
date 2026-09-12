package com.villagertradepredicator.core.locate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import com.villagertradepredicator.core.rng.XoroshiroStream;

/**
 * Recovers "which round of the shared sequence is this villager consuming" (the offset)
 * from observed offers. Observations must be consecutive rounds — e.g. one villager
 * re-observed after each lectern cycle; a single screen is a one-round window. Multiple
 * matches mean the observation window was too small; the caller widens it and rescans.
 *
 * <p>Matching basis: {@link MatchMode#ORDERED} compares the on-screen order of offers
 * (strictly more evidence — prefer it when the observer records order);
 * {@link MatchMode#UNORDERED} compares sorted multisets (tolerates order mistakes).</p>
 */
public final class OffsetLocator {
    private OffsetLocator() {}

    public enum MatchMode {
        ORDERED(RoundIterator.Round::orderedFingerprints),
        UNORDERED(RoundIterator.Round::fingerprints);

        final Function<RoundIterator.Round, List<OfferFingerprint>> extractor;

        MatchMode(Function<RoundIterator.Round, List<OfferFingerprint>> extractor) {
            this.extractor = extractor;
        }
    }

    /** Result of one inference: every offset whose window matches all observations. */
    public record InferenceResult(List<Integer> candidates) {
        public boolean unique() {
            return candidates.size() == 1;
        }

        public boolean found() {
            return !candidates.isEmpty();
        }

        public boolean contains(int offset) {
            return candidates.contains(offset);
        }
    }

    /**
     * @param observedRounds fingerprints of consecutive observed rounds,
     *        {@code observedRounds[0]} being the earliest; must use the same match mode
     * @return every offset {@code o} where rounds {@code o..o+n-1} match the observations
     */
    public static InferenceResult infer(TradeSetDef set, SequenceConfig cfg, long seed,
            SimContext ctx, MatchMode mode, List<List<OfferFingerprint>> observedRounds, int maxOffset) {
        return new InferenceResult(findOffsets(set, cfg, seed, ctx, mode, observedRounds, maxOffset));
    }

    public static List<Integer> findOffsets(TradeSetDef set, SequenceConfig cfg, long seed,
            SimContext ctx, MatchMode mode, List<List<OfferFingerprint>> observedRounds, int maxOffset) {
        if (observedRounds.isEmpty()) {
            return List.of();
        }
        XoroshiroStream rng = TradeSequences.create(seed, cfg, set.randomSequence());
        return scan(new RoundIterator(set, rng, ctx), mode, observedRounds, maxOffset);
    }

    /** Legacy entry point: unordered matching. */
    public static List<Integer> findOffsets(long worldSeed, SequenceConfig cfg, TradeSetDef set,
            SimContext ctx, List<List<OfferFingerprint>> observedRounds, int maxOffset) {
        return findOffsets(set, cfg, worldSeed, ctx, MatchMode.UNORDERED, observedRounds, maxOffset);
    }

    private static List<Integer> scan(RoundIterator iterator, MatchMode mode,
            List<List<OfferFingerprint>> observedRounds, int maxOffset) {
        int window = observedRounds.size();
        Deque<List<OfferFingerprint>> history = new ArrayDeque<>(window);
        List<Integer> hits = new ArrayList<>();
        for (int offset = 0; offset <= maxOffset; offset++) {
            RoundIterator.Round round = iterator.next();
            history.addLast(mode.extractor.apply(round));
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
