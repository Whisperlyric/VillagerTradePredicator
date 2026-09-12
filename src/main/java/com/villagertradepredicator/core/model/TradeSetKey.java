package com.villagertradepredicator.core.model;

import net.minecraft.resources.Identifier;

/** Identifies one trade set: {@code <profession>} at villager level {@code 1..5}. */
public record TradeSetKey(Identifier profession, int level) {}
