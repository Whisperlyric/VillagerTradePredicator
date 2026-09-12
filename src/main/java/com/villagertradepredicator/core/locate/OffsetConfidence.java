package com.villagertradepredicator.core.locate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.XoroshiroStream;
import com.villagertradepredicator.core.sim.SimContext;

/**
 * Confidence model for offset localization from observed villager screens.
 *
 * <p>One "observation group" = one villager's one level, all offers with their on-screen
 * order (ordered fingerprints carry strictly more evidence than unordered ones). A scan
 * over {@code C} candidate offsets always hits the true one; a wrong candidate matches by
 * chance with probability {@code q(k)} — the probability that a random k-round window
 * reproduces the observed window. Treating stream positions as mutually independent
 * (empirically validated by {@code OffsetConfidenceAnalysisTest}'s Monte Carlo), the
 * probability that the localization is unique and correct is {@code (1-q)^(C-1)}.</p>
 *
 * <p>Sets whose rounds are indistinguishable (e.g. a pool of 1, or librarian L5's two
 * candles) have {@code q=1} for every k — they are fundamentally unlocalizable from
 * observation alone and must rely on manual counters instead.</p>
 */
public final class OffsetConfidence {
    private OffsetConfidence() {}

    /** Fingerprint frequency profile of one trade set, sampled over a long run of rounds. */
    public record Distribution(List<List<OfferFingerprint>> rounds,
            Map<List<OfferFingerprint>, Integer> counts, int total) {

        /** Distinct round fingerprints — the offset signal strength (1 = unlocalizable). */
        public int distinctRounds() {
            return counts.size();
        }
    }

    public static Distribution distribution(TradeSetDef set, XoroshiroStream rng, int rounds) {
        RoundIterator it = new RoundIterator(set, rng, SimContext.withoutVariant());
        List<List<OfferFingerprint>> roundList = new ArrayList<>(rounds);
        Map<List<OfferFingerprint>, Integer> counts = new HashMap<>();
        for (int i = 0; i < rounds; i++) {
            List<OfferFingerprint> fingerprints = it.next().orderedFingerprints();
            roundList.add(fingerprints);
            counts.merge(fingerprints, 1, Integer::sum);
        }
        return new Distribution(List.copyOf(roundList), Map.copyOf(counts), rounds);
    }

    /**
     * Per-wrong-candidate false-match probability for a window of {@code window}
     * consecutive rounds: the expected product of each round's fingerprint frequency.
     * Useful for the expected candidate count, but NOT for the unique-localization
     * probability — the window outcomes are heavy-tailed (a book round is essentially
     * unforgeable, plain rounds collide), so the expectation must stay inside the power
     * (see {@link #uniqueProbability(Distribution, int, int)}).
     */
    public static double falseMatchProbability(Distribution distribution, int window) {
        if (window <= 0) {
            return 1.0;
        }
        double total = distribution.total();
        double sum = 0.0;
        int samples = 0;
        for (int start = 0; start + window <= distribution.rounds().size(); start++) {
            double probability = 1.0;
            for (int i = 0; i < window; i++) {
                List<OfferFingerprint> round = distribution.rounds().get(start + i);
                probability *= distribution.counts().getOrDefault(round, 0) / (double) total;
                if (probability == 0.0) {
                    break;
                }
            }
            sum += probability;
            samples++;
        }
        return samples == 0 ? 1.0 : sum / samples;
    }

    /**
     * P(the scan yields exactly the true offset), assuming the true offset is in range.
     * Evaluated per observation window INSIDE the power: rare (book-containing) windows
     * survive a 1000-candidate scan while plain windows do not, and the average confidence
     * is the player's mixed reality of both.
     */
    public static double uniqueProbability(Distribution distribution, int window, int candidateCount) {
        if (window <= 0) {
            return 0.0; // no observation, no localization
        }
        if (candidateCount <= 1) {
            return 1.0;
        }
        double total = distribution.total();
        double sum = 0.0;
        int samples = 0;
        for (int start = 0; start + window <= distribution.rounds().size(); start++) {
            double q = 1.0;
            for (int i = 0; i < window; i++) {
                List<OfferFingerprint> round = distribution.rounds().get(start + i);
                q *= distribution.counts().getOrDefault(round, 0) / (double) total;
                if (q == 0.0) {
                    break;
                }
            }
            sum += Math.pow(1.0 - q, candidateCount - 1);
            samples++;
        }
        return samples == 0 ? 0.0 : sum / samples;
    }

    /** P(the scan yields exactly the true offset) for an analytic false-match rate. */
    public static double uniqueProbability(double falseMatch, int candidateCount) {
        if (candidateCount <= 1) {
            return 1.0;
        }
        return Math.pow(1.0 - falseMatch, candidateCount - 1);
    }

    /** Screen-order fingerprint of one round (what the player sees, left to right). */
    public static List<OfferFingerprint> orderedFingerprints(List<PredictedOffer> offers) {
        return offers.stream().map(OfferFingerprint::of).toList();
    }
}
