package com.villagertradepredicator.core;

import java.util.ArrayList;
import java.util.List;

import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.locate.OffsetConfidence;
import com.villagertradepredicator.core.locate.OffsetConfidence.Distribution;
import com.villagertradepredicator.core.locate.OffsetLocator;
import com.villagertradepredicator.core.locate.OffsetLocator.InferenceResult;
import com.villagertradepredicator.core.locate.OffsetLocator.MatchMode;
import com.villagertradepredicator.core.locate.RoundIterator;
import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Answers "how many observation groups does offset localization need": per compatible
 * trade set we measure the fingerprint distribution and derive P(unique & correct
 * localization) for observation windows of k=1..10 consecutive rounds, then validate the
 * analytic model against Monte Carlo scans of the real locator.
 */
class OffsetConfidenceAnalysisTest {
    private static final long SEED = 7662585126525589278L;
    private static final int SIM_ROUNDS = 3000;
    private static final int SCAN_RANGE = 1000; // candidate offsets 0..1000
    private static final int MAX_GROUPS = 10;
    private static final Identifier PLAINS = Identifier.withDefaultNamespace("plains");

    private static TradeDataRepository repo;
    private static final List<String> setNames = new ArrayList<>();
    private static final List<Distribution> distributions = new ArrayList<>();
    private static final List<SimContext> contexts = new ArrayList<>();
    private static final List<TradeSetDef> defs = new ArrayList<>();

    @BeforeAll
    static void setup() {
        TestSupport.bootstrap();
        repo = TradeDataRepository.loadFromClasspath();
        for (LoadedSet loaded : compatibleSets()) {
            setNames.add(loaded.name());
            distributions.add(OffsetConfidence.distribution(loaded.set(),
                    TradeSequences.create(SEED, SequenceConfig.DEFAULT, loaded.set().randomSequence()),
                    SIM_ROUNDS));
            contexts.add(loaded.context());
            defs.add(loaded.set());
        }
    }

    private record LoadedSet(String name, TradeSetDef set, SimContext context) {}

    private static List<LoadedSet> compatibleSets() {
        List<LoadedSet> out = new ArrayList<>();
        for (var loaded : repo.all()) {
            if (!loaded.isCompatible()) {
                continue;
            }
            TradeSetDef def = loaded.def().orElseThrow();
            boolean variantGated = def.trades().stream().anyMatch(t -> t.predicate().isPresent());
            String name = loaded.key().profession().getPath() + "/L" + loaded.key().level();
            out.add(new LoadedSet(name, def, variantGated ? SimContext.withVariant(PLAINS) : SimContext.withoutVariant()));
        }
        return out;
    }

    @Test
    void confidenceTableGroupsOneToTen() {
        int sets = setNames.size();
        double[] avgUnique1000 = new double[MAX_GROUPS];
        double[] avgUnique200 = new double[MAX_GROUPS];
        double[] minUnique1000 = new double[MAX_GROUPS];
        List<String> unlocalizable = new ArrayList<>();
        List<String> localizable = new ArrayList<>();

        for (int s = 0; s < sets; s++) {
            Distribution dist = distributions.get(s);
            if (dist.distinctRounds() <= 2) {
                unlocalizable.add(setNames.get(s) + "(distinct=" + dist.distinctRounds() + ")");
            } else {
                localizable.add(setNames.get(s) + "(distinct=" + dist.distinctRounds() + ")");
            }
            for (int k = 1; k <= MAX_GROUPS; k++) {
                double p1000 = OffsetConfidence.uniqueProbability(dist, k, SCAN_RANGE + 1);
                double p200 = OffsetConfidence.uniqueProbability(dist, k, 200);
                avgUnique1000[k - 1] += p1000 / sets;
                avgUnique200[k - 1] += p200 / sets;
                if (k == 1 || p1000 < minUnique1000[k - 1]) {
                    minUnique1000[k - 1] = p1000;
                }
            }
        }

        System.out.println("== offset localization confidence (avg over " + sets
                + " compatible sets, ordered matching) ==");
        System.out.println("groups | P(unique)@scan1000 | P(unique)@scan200 | worst-set@1000");
        for (int k = 0; k < MAX_GROUPS; k++) {
            System.out.printf("%6d | %.4f              | %.4f            | %.4f%n",
                    k + 1, avgUnique1000[k], avgUnique200[k], minUnique1000[k]);
        }
        System.out.println("unlocalizable (rounds indistinguishable, rely on manual counters): " + unlocalizable);
        System.out.println("localizable sets: " + localizable.size() + " -> " + localizable);

        // The model must be monotone in k, and ten groups must be near-certain on any set
        // whose rounds are distinguishable at all.
        for (int k = 1; k < MAX_GROUPS; k++) {
            assertTrue(avgUnique1000[k] >= avgUnique1000[k - 1] - 1e-9, "monotonicity @" + k);
        }
        double k10WorstLocalizable = 1.0;
        for (int s = 0; s < sets; s++) {
            if (distributions.get(s).distinctRounds() <= 2) {
                continue;
            }
            double p = OffsetConfidence.uniqueProbability(distributions.get(s), 10, SCAN_RANGE + 1);
            k10WorstLocalizable = Math.min(k10WorstLocalizable, p);
        }
        System.out.printf("k=10 worst localizable set: %.4f%n", k10WorstLocalizable);
        // The weakest localizable sets (small pools, uneven frequencies) bottom out below
        // 1.0; one more group keeps shrinking candidates, and InferenceResult.unique() is
        // the UI's "done" signal.
        assertTrue(k10WorstLocalizable >= 0.85, "k=10 must be near-certain for localizable sets");
    }

    @Test
    void monteCarloValidatesAnalyticModel() {
        // Cross-check the analytic P(unique) against the real locator with planted truths.
        String[][] cases = {
                {"librarian", "1"}, // book-capable: rare fingerprints dominate
                {"farmer", "1"},    // plain pool: collisions come from a small outcome space
        };
        int[] groups = {1, 2, 3, 5};
        int trials = 24;
        System.out.println("== monte carlo (24 trials per cell, ordered matching, scan 0..1000) ==");
        for (String[] c : cases) {
            Identifier prof = Identifier.withDefaultNamespace(c[0]);
            int level = Integer.parseInt(c[1]);
            TradeSetDef set = repo.compatible(prof, level).orElseThrow();
            for (int k : groups) {
                Distribution dist = distributionOf(prof, level);
                double analytic = OffsetConfidence.uniqueProbability(dist, k, SCAN_RANGE + 1);
                int unique = 0;
                java.util.random.RandomGenerator rng = new java.util.Random(42);
                for (int trial = 0; trial < trials; trial++) {
                    int truth = 80 + rng.nextInt(641);
                    List<List<OfferFingerprint>> observed = observe(prof, level, truth, k);
                    InferenceResult result = OffsetLocator.infer(set, SequenceConfig.DEFAULT, SEED,
                            SimContext.withoutVariant(), MatchMode.ORDERED, observed, SCAN_RANGE);
                    if (result.unique() && result.contains(truth)) {
                        unique++;
                    }
                }
                double empirical = unique / (double) trials;
                System.out.printf("%s L%d k=%d: analytic %.3f empirical %.3f%n",
                        c[0], level, k, analytic, empirical);
                assertTrue(Math.abs(analytic - empirical) <= 0.3,
                        c[0] + " L" + level + " k=" + k + " analytic " + analytic + " vs " + empirical);
            }
        }
    }

    private Distribution distributionOf(Identifier prof, int level) {
        int idx = setNames.indexOf(prof.getPath() + "/L" + level);
        return distributions.get(idx);
    }

    private static List<List<OfferFingerprint>> observe(Identifier prof, int level, int start, int groups) {
        RoundIterator it = new RoundIterator(repo.compatible(prof, level).orElseThrow(),
                TradeSequences.create(SEED, SequenceConfig.DEFAULT, TradeSequences.sequenceId(prof, level)),
                SimContext.withoutVariant());
        for (int i = 0; i < start; i++) {
            it.next();
        }
        List<List<OfferFingerprint>> out = new ArrayList<>();
        for (int i = 0; i < groups; i++) {
            out.add(it.next().orderedFingerprints());
        }
        return out;
    }
}
