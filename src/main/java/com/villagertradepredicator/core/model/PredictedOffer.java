package com.villagertradepredicator.core.model;

import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * One predicted offer of a round, as pure data (26.x enchantments live in a
 * server-synced registry, so the core carries identifiers only; the client layer
 * turns these into real stacks for display).
 */
public record PredictedOffer(
        Identifier tradeId,
        Identifier resultItem,
        int resultCount,
        Optional<StoredEnchant> storedEnchantment,
        int costA,
        Optional<SecondCost> costB,
        int maxUses,
        int xp,
        float reputationDiscount) {

    /** The single enchantment on an enchanted-book offer. */
    public record StoredEnchant(Identifier enchantment, int level) {}

    /** The second purchase side (e.g. the book paired with emeralds). */
    public record SecondCost(Identifier item, int count) {}
}
