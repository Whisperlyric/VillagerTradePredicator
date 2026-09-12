package com.villagertradepredicator.core.model;

import net.minecraft.resources.Identifier;

/** One concrete enchantment instance (id + level) on a predicted offer. */
public record EnchantmentLevel(Identifier enchantment, int level) {}
