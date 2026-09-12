package com.villagertradepredicator.core.model;

import net.minecraft.resources.Identifier;

/** The item a trade sells (before item modifiers are applied). */
public record GiveDef(Identifier item, int count) {}
