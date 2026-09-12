package com.villagertradepredicator.core.sim;

import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * The villager-side inputs a simulation needs. Only variant-sensitive trades
 * (e.g. cartographer maps, fisherman boats) read it; an empty variant evaluates
 * every variant-gated predicate to false, matching a villager of unknown variant
 * whose gated offers can never appear.
 */
public record SimContext(Optional<Identifier> variantId) {

    public static SimContext withoutVariant() {
        return new SimContext(Optional.empty());
    }

    public static SimContext withVariant(Identifier variantId) {
        return new SimContext(Optional.of(variantId));
    }
}
