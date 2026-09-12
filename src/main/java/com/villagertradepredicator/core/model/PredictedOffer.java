package com.villagertradepredicator.core.model;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * One predicted offer of a round, as pure data (26.x enchantments live in a
 * server-synced registry, so the core carries identifiers only; the client layer
 * turns these into real stacks for display).
 *
 * {@code enchantments} covers both book stored enchantments (size 1) and preset
 * equipment enchantments from {@code set_enchantments}.
 */
public record PredictedOffer(
        Identifier tradeId,
        Identifier resultItem,
        int resultCount,
        List<EnchantmentLevel> enchantments,
        int costA,
        Optional<SecondCost> costB,
        int maxUses,
        int xp,
        float reputationDiscount) {

    /** The second purchase side (e.g. the book paired with emeralds). */
    public record SecondCost(Identifier item, int count) {}

    /** The single enchantment of an enchanted-book offer, if any. */
    public Optional<EnchantmentLevel> storedEnchantment() {
        return enchantments.size() == 1 ? Optional.of(enchantments.get(0)) : Optional.empty();
    }
}
