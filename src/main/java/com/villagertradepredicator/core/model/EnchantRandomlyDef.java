package com.villagertradepredicator.core.model;

import java.util.List;

/**
 * Supported subset of the {@code minecraft:enchant_randomly} item modifier:
 * an explicit options pool (tag or list, in tag-file order — the pool order defines the
 * {@code nextInt(size)} mapping), optionally writing the emerald price into the
 * {@code minecraft:additional_trade_cost} component.
 */
public record EnchantRandomlyDef(List<EnchantRef> options, boolean includeAdditionalCostComponent) {}
