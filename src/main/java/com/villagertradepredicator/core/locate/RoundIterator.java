package com.villagertradepredicator.core.locate;

import java.util.Iterator;
import java.util.List;

import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.StreamSnapshot;
import com.villagertradepredicator.core.sim.SimContext;
import com.villagertradepredicator.core.sim.TradeSetSimulator;
import com.villagertradepredicator.core.rng.XoroshiroStream;

/**
 * Walks a trade set's random sequence forward one generation round at a time. A single
 * RNG stream is reused (never re-derived per offset), so scanning thousands of rounds
 * costs O(N) draws. Offset 0 = the first villager of that profession/level in a fresh
 * world; each lectern-cycle reroll consumes one round.
 *
 * <p>{@link #snapshot()} + {@link #resume} persist a mid-stream position: subsequent
 * prediction sessions start from round N directly instead of replaying from the
 * beginning (the stream itself cannot seek — this is exactly how the game consumes it).
 * In singleplayer the snapshot values can equally come from the live vanilla generator
 * via an accessor, giving an exact "this villager's sequence is here" anchor.</p>
 */
public final class RoundIterator implements Iterator<RoundIterator.Round> {
    private final TradeSetDef set;
    private final SimContext context;
    private final XoroshiroStream rng;
    private int nextOffset;

    public RoundIterator(TradeSetDef set, XoroshiroStream rng, SimContext context) {
        this(set, rng, context, 0);
    }

    private RoundIterator(TradeSetDef set, XoroshiroStream rng, SimContext context, int nextOffset) {
        this.set = set;
        this.rng = rng;
        this.context = context;
        this.nextOffset = nextOffset;
    }

    public static RoundIterator resume(TradeSetDef set, SimContext context, StreamSnapshot snapshot) {
        return new RoundIterator(set, new XoroshiroStream(snapshot.lo(), snapshot.hi()), context, snapshot.nextOffset());
    }

    public StreamSnapshot snapshot() {
        return new StreamSnapshot(rng.lo(), rng.hi(), nextOffset);
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
