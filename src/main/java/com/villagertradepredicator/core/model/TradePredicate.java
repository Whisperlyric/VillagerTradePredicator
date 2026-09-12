package com.villagertradepredicator.core.model;

import java.util.Set;

import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;

/**
 * Trade conditions we can evaluate statically on the client. Anything outside this
 * interface is compatibility-rejected for the whole trade set, because a predicate
 * outcome changes the RNG draw sequence (a failed predicate burns the selection draw
 * but produces no offer).
 */
public interface TradePredicate {
    boolean test(SimContext context);

    /**
     * {@code minecraft:entity_properties} checking {@code minecraft:villager/variant}
     * (biome variants of the villager). Values may be a single id or a list.
     */
    record VillagerVariant(Set<Identifier> allowedVariants) implements TradePredicate {
        @Override
        public boolean test(SimContext context) {
            return context.variantId().map(allowedVariants::contains).orElse(false);
        }
    }
}
