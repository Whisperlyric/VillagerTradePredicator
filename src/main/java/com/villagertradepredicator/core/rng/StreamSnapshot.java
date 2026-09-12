package com.villagertradepredicator.core.rng;

/**
 * A persisted position inside a named random sequence: the generator's 128-bit state plus
 * the offset the NEXT round will consume. Capturing this after a scan lets later
 * predictions resume exactly there instead of replaying from offset 0.
 */
public record StreamSnapshot(long lo, long hi, int nextOffset) {}
