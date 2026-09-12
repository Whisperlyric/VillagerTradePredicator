package com.villagertradepredicator.core.model;

import java.util.List;

import net.minecraft.resources.Identifier;

/**
 * One {@code minecraft:trade_set/<profession>/level_<n>} set: how many offers a villager
 * of that profession/level rolls from which pool, and the name of the random sequence
 * the roll consumes. {@code amount} is evaluated once per generation round, before the
 * pool picks begin.
 */
public record TradeSetDef(
        Identifier id,
        Identifier randomSequence,
        NumberProvider amount,
        boolean allowDuplicates,
        List<TradeDef> trades) {}
