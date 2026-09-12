package com.villagertradepredicator.core.locate;

import java.util.Iterator;
import java.util.List;

import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.sim.SimContext;
import com.villagertradepredicator.core.sim.TradeSetSimulator;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Walks a trade set's random sequence forward one generation round at a time from its
 * initial state. A single RNG stream is reused (never re-derived per offset), so scanning
 * thousands of rounds costs O(N) draws. Offset 0 = the first villager of that
 * profession/level in a fresh world; each lectern-cycle reroll consumes one round.
 */
public final class RoundIterator implements Iterator<RoundIterator.Round> {
    private final TradeSetDef set;
    private final SimContext context;
    private final XoroshiroRandomSource rng;
    private int nextOffset;

    public RoundIterator(TradeSetDef set, XoroshiroRandomSource rng, SimContext context) {
        this.set = set;
        this.rng = rng;
        this.context = context;
    }

    @Override
    public boolean hasNext() {
        return true;
    }

    @Override
    public Round next() {
        List<PredictedOffer> offers = TradeSetSimulator.simulateRound(set, rng, context);
        return new Round(nextOffset++, offers);
    }

    public record Round(int offset, List<PredictedOffer> offers) {

        public List<OfferFingerprint> fingerprints() {
            return OfferFingerprint.ofRound(offers);
        }

        public boolean isEmpty() {
            return offers.isEmpty();
        }
    }
}
