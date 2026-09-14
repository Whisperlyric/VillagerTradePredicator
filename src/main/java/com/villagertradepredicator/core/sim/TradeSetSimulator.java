package com.villagertradepredicator.core.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.villagertradepredicator.core.model.CostDef;
import com.villagertradepredicator.core.model.EnchantRandomlyDef;
import com.villagertradepredicator.core.model.EnchantRef;
import com.villagertradepredicator.core.model.EnchantmentLevel;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeDef;
import com.villagertradepredicator.core.model.TradeSetDef;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Replays one round of vanilla offer generation for a trade set — the same RNG
 * consumption order as {@code addOffersFromItemListingsWithoutDuplicates} followed by
 * {@code VillagerTrade.getOffer} and its loot functions.
 *
 * <p>RNG draw order (any deviation shifts every later prediction):
 * <ol>
 *   <li>{@code amount.getInt} — evaluated once before the pool picks</li>
 *   <li>{@code nextInt(pool.remaining)} — pick a trade, remove it (no-duplicates sets)</li>
 *   <li>variant predicate fails → offer discarded, but the pick in (2) is still consumed</li>
 *   <li>{@code enchant_randomly}: {@code nextInt(options.size)} → enchantment</li>
 *   <li>enchantment level: {@code nextInt(max-min+1)} — <b>skipped entirely when min==max</b>
 *       (vanilla branches first; drawing here misaligns the whole stream)</li>
 *   <li>book price: {@code nextInt(5 + level*10)}; cost = 2 + draw + 3*level, written to
 *       the additional-trade-cost component</li>
 *   <li>{@code set_enchantments} / {@code filtered} — deterministic, no draws</li>
 *   <li>{@code double_trade_price} hit → additional cost doubled</li>
 *   <li>wants count provider draw, then additional-wants provider (constants draw nothing)</li>
 *   <li>max_uses → xp → reputation_discount provider draws (constants in all vanilla data)</li>
 * </ol>
 * Rounds where the pool is smaller than {@code amount} simply stop when the pool empties.</p>
 */
public final class TradeSetSimulator {
    private TradeSetSimulator() {}

    public static List<PredictedOffer> simulateRound(TradeSetDef set, RandomSource rng, SimContext ctx) {
        int amount = set.amount().getInt(rng);
        List<PredictedOffer> offers = new ArrayList<>(amount);
        List<TradeDef> pool = new ArrayList<>(set.trades());
        while (offers.size() < amount && !pool.isEmpty()) {
            TradeDef picked = set.allowDuplicates()
                    ? pool.get(rng.nextInt(pool.size()))
                    : pool.remove(rng.nextInt(pool.size()));
            generateOffer(picked, rng, ctx).ifPresent(offers::add);
        }
        return List.copyOf(offers);
    }

    private static Optional<PredictedOffer> generateOffer(TradeDef trade, RandomSource rng, SimContext ctx) {
        if (trade.predicate().isPresent() && !trade.predicate().get().test(ctx)) {
            return Optional.empty();
        }

        List<EnchantmentLevel> enchantments = new ArrayList<>(2);
        int extraCost = 0;

        if (trade.enchantRandomly().isPresent()) {
            EnchantRandomlyDef def = trade.enchantRandomly().get();
            EnchantRef picked = def.options().get(rng.nextInt(def.options().size()));
            int min = picked.minLevel();
            int max = picked.maxLevel();
            // Vanilla evaluates the min==max case BEFORE drawing — no RNG consumption at max level.
            int level = min >= max ? min : min + rng.nextInt(max - min + 1);
            enchantments.add(new EnchantmentLevel(picked.id(), level));
            if (def.includeAdditionalCostComponent()) {
                extraCost = 2 + rng.nextInt(5 + level * 10) + 3 * level;
            }
        }
        enchantments.addAll(trade.presetEnchantments());

        if (trade.requireEnchanted() && enchantments.isEmpty()) {
            return Optional.empty();
        }

        if (!trade.doublePriceEnchantments().isEmpty()) {
            boolean hit = enchantments.stream()
                    .anyMatch(e -> trade.doublePriceEnchantments().contains(e.enchantment()));
            if (hit) {
                extraCost *= 2;
            }
        }

        int costA = costCount(trade.wants(), extraCost, rng);
        if (costA < 1) {
            return Optional.empty();
        }
        Optional<PredictedOffer.SecondCost> costB = Optional.empty();
        if (trade.additionalWants().isPresent()) {
            int count = costCount(trade.additionalWants().get(), 0, rng);
            if (count < 1) {
                return Optional.empty();
            }
            costB = Optional.of(new PredictedOffer.SecondCost(
                    trade.additionalWants().get().item(), count));
        }

        int maxUses = Math.max(1, trade.maxUses().getInt(rng));
        int xp = trade.xp().getInt(rng);
        float reputationDiscount = trade.reputationDiscount().getFloat(rng);

        return Optional.of(new PredictedOffer(
                trade.id(), trade.gives().item(), trade.gives().count(), List.copyOf(enchantments),
                trade.wants().item(), costA, costB, maxUses, xp, reputationDiscount));
    }

    /** {@code TradeCost.toItemCost}: provider draw plus the additional-cost component, clamped to stack size. */
    private static int costCount(CostDef cost, int extra, RandomSource rng) {
        return Mth.clamp(cost.count().getInt(rng) + extra, 0, cost.maxStackSize());
    }
}
