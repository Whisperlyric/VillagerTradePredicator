package com.villagertradepredicator.client.observe;

import java.util.List;
import java.util.UUID;

import com.villagertradepredicator.core.model.OfferFingerprint;

/**
 * One imperceptible read of a villager's trade table: the villager identity, its level
 * and experience (xp > 0 means the profession is locked and can never re-roll), and the
 * offers in on-screen order — exactly one observation group for offset inference.
 */
public record ObservedOffers(UUID villager, int villagerLevel, int villagerXp,
        List<OfferFingerprint> offers) {

    /** Traded villagers keep their profession; re-rolling (and prediction) needs xp = 0. */
    public boolean tradeLocked() {
        return villagerXp > 0;
    }
}
