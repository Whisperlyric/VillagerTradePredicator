package com.villagertradepredicator.core.model;

import net.minecraft.resources.Identifier;

/**
 * A purchase side of a trade; constant count only (randomized counts are
 * compatibility-rejected). {@code maxStackSize} is captured at parse time —
 * it clamps the emerald price after the additional-cost component is applied.
 */
public record CostDef(Identifier item, int count, int maxStackSize) {}
