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
    public enum AppendStatus {
        /** First group of a new run (villager/profession/level changed). */
        STARTED,
        /** Appended as a new consecutive round. */
        APPENDED,
        /**
         * Appended, but identical to the previous group. Two possible causes: the player
         * read twice without a re-roll (should be undone), or the re-roll genuinely
         * produced the same content (common on low-entropy pools — real evidence). The
         * player decides via undo.
         */
        DUPLICATE_APPENDED
    }

    public AppendStatus append(ObservedOffers observed, Identifier profession) {
        boolean sameRun = villagerId != null && villagerId.equals(observed.villager())
                && profession.equals(this.profession)
                && level == observed.villagerLevel();
        if (!sameRun) {
            villagerId = observed.villager();
            this.profession = profession;
            level = observed.villagerLevel();
            groups.clear();
            groups.add(observed.offers());
            return AppendStatus.STARTED;
        }
        boolean duplicate = !groups.isEmpty() && groups.get(groups.size() - 1).equals(observed.offers());
        groups.add(observed.offers());
        return duplicate ? AppendStatus.DUPLICATE_APPENDED : AppendStatus.APPENDED;
    }

    /** Removes the most recent group (undo an accidental read); true when one was removed. */
    public boolean removeLast() {
        if (groups.isEmpty()) {
            return false;
        }
        groups.remove(groups.size() - 1);
        if (groups.isEmpty()) {
            villagerId = null;
            profession = null;
            level = 0;
        }
        return true;
    }

    public List<Integer> infer(TradeSetDef set, SequenceConfig cfg, long seed, SimContext ctx, int scanRange) {
        return OffsetLocator.findOffsets(set, cfg, seed, ctx, MatchMode.ORDERED, groups, scanRange);
    }
}
