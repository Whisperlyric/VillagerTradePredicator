package com.villagertradepredicator.core.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.Identifier;

/**
 * A simulatable villager trade (26.x data-driven {@code VillagerTrade}), reduced to the
 * shapes the predictor supports. Trades using anything outside these fields fail the
 * compatibility gate and disable their whole trade set — a rejected entry would shift
 * the RNG draw sequence, so partial support is never correct.
 *
 * @param presetEnchantments fixed enchantments written by {@code set_enchantments}
 *        (deterministic, consumes no RNG)
 * @param requireEnchanted the known {@code filtered}+{@code discard} trailing step
 *        (offer is dropped when the result carries no enchantment)
 */
public record TradeDef(
        Identifier id,
        CostDef wants,
        Optional<CostDef> additionalWants,
        GiveDef gives,
        NumberProvider maxUses,
        NumberProvider reputationDiscount,
        NumberProvider xp,
        Optional<TradePredicate> predicate,
        Optional<EnchantRandomlyDef> enchantRandomly,
        List<EnchantmentLevel> presetEnchantments,
        boolean requireEnchanted,
        Set<Identifier> doublePriceEnchantments) {}
