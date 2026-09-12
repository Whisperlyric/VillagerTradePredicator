package com.villagertradepredicator.core.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * Canonical, comparable identity of one offer, used to match a predicted round against
 * offers observed in-game. Deliberately ignores demand/uses/discount, which the server
 * applies on top and the predictor does not model — observed prices therefore equal the
 * predicted base price only while demand is neutral.
 */
public record OfferFingerprint(Identifier resultItem, int costA,
        Optional<PredictedOffer.SecondCost> costB,
        Optional<PredictedOffer.StoredEnchant> enchant) {

    public static OfferFingerprint of(PredictedOffer offer) {
        return new OfferFingerprint(offer.resultItem(), offer.costA(), offer.costB(), offer.storedEnchantment());
    }

    /** Stable multi-offer identity of one round, order-insensitive. */
    public static List<OfferFingerprint> ofRound(List<PredictedOffer> offers) {
        return offers.stream().map(OfferFingerprint::of)
                .sorted(Comparator.comparing(Object::toString))
                .toList();
    }

    public boolean sameOffer(OfferFingerprint other) {
        return Objects.equals(this, other);
    }
}
