package com.villagertradepredicator.core.model;

import net.minecraft.resources.Identifier;

/**
 * A purchase side of a trade. {@code count} may be randomized (constant / uniform / sum
 * providers are simulatable; other provider types are compatibility-rejected).
 * {@code maxStackSize} is captured at parse time — it clamps the price after the
 * additional-cost component is applied.
 */
public record CostDef(Identifier item, NumberProvider count, int maxStackSize) {}
