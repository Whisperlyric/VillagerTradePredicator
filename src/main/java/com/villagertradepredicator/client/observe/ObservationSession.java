package com.villagertradepredicator.client.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.locate.OffsetLocator;
import com.villagertradepredicator.core.locate.OffsetLocator.MatchMode;
import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;

/**
 * Accumulates consecutive observation groups for one villager run (same villager UUID,
 * profession and level) and localizes the sequence offset from them. Any identity change
 * resets the session — observations must be consecutive rounds of the same list.
 */
public final class ObservationSession {
    private UUID villagerId;
    private Identifier profession;
    private int level;
    private final List<List<OfferFingerprint>> groups = new ArrayList<>();

    public void reset() {
        villagerId = null;
        profession = null;
        level = 0;
        groups.clear();
    }

    public int groups() {
        return groups.size();
    }

    /** Appends one group; starts a fresh session when the villager/profession/level changed. */
    public void append(ObservedOffers observed, Identifier profession) {
        boolean sameRun = villagerId != null && villagerId.equals(observed.villager())
                && profession.equals(this.profession)
                && level == observed.villagerLevel();
        if (!sameRun) {
            villagerId = observed.villager();
            this.profession = profession;
            level = observed.villagerLevel();
            groups.clear();
        }
        groups.add(observed.offers());
    }

    public List<Integer> infer(TradeSetDef set, SequenceConfig cfg, long seed, SimContext ctx, int scanRange) {
        return OffsetLocator.findOffsets(set, cfg, seed, ctx, MatchMode.ORDERED, groups, scanRange);
    }
}
