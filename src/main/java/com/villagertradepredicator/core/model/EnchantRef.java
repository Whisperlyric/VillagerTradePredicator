package com.villagertradepredicator.core.model;

import net.minecraft.resources.Identifier;

/**
 * Registry-free identity of an enchantment for prediction purposes. In 26.x enchantments
 * are a datapack (server-synced) registry, so the core resolves only what the RNG math
 * needs — id and level bounds — from the jar JSONs; the client layer maps these to real
 * registry holders for display.
 */
public record EnchantRef(Identifier id, int minLevel, int maxLevel) {}
