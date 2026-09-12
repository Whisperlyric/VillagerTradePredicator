package com.villagertradepredicator.core.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.villagertradepredicator.core.model.CostDef;
import com.villagertradepredicator.core.model.EnchantRandomlyDef;
import com.villagertradepredicator.core.model.EnchantRef;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeDef;
import com.villagertradepredicator.core.model.TradeSetDef;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Replays one round of vanilla offer generation for a trade set — the same RNG
 * consumption order as {@code addOffersFromItemListingsWithoutDuplicates} followed by
 * {@code VillagerTrade.getOffer} and the {@code enchant_randomly} loot function.
 *
 * <p>RNG draw order (any deviation shifts every later prediction):
 * <ol>
 *   <li>{@code nextInt(pool.remaining)} — pick a trade, remove it (no-duplicates sets)</li>
 *   <li>variant predicate fails → offer discarded, but the pick in (1) is still consumed</li>
 *   <li>{@code enchant_randomly}: {@code nextInt(options.size)} → enchantment</li>
 *   <li>enchantment level: {@code nextInt(max-min+1)} — <b>skipped entirely when min==max</b>
 *       (vanilla branches first; drawing here misaligns the whole stream)</li>
 *   <li>book price: {@code nextInt(5 + level*10)}; cost = 2 + draw + 3*level,
 *       doubled for {@code #double_trade_price} enchantments, clamped to the cost item's
 *       max stack size</li>
 * </ol>
 * Rounds where the pool is smaller than {@code amount} simply stop when the pool empties.</p>
 */
public final class TradeSetSimulator {
    private TradeSetSimulator() {}

    public static List<PredictedOffer> simulateRound(TradeSetDef set, RandomSource rng, SimContext ctx) {
        List<PredictedOffer> offers = new ArrayList<>(set.amount());
        List<TradeDef> pool = new ArrayList<>(set.trades());
        while (offers.size() < set.amount() && !pool.isEmpty()) {
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

        Optional<PredictedOffer.StoredEnchant> enchant = Optional.empty();
        int extraCost = 0;

        if (trade.enchantRandomly().isPresent()) {
            EnchantRandomlyDef def = trade.enchantRandomly().get();
            EnchantRef picked = def.options().get(rng.nextInt(def.options().size()));
            int min = picked.minLevel();
            int max = picked.maxLevel();
            // Vanilla evaluates the min==max case BEFORE drawing — no RNG consumption at max level.
            int level = min >= max ? min : min + rng.nextInt(max - min + 1);
            enchant = Optional.of(new PredictedOffer.StoredEnchant(picked.id(), level));
            if (def.includeAdditionalCostComponent()) {
                extraCost = 2 + rng.nextInt(5 + level * 10) + 3 * level;
            }
        }

        if (trade.requireStoredEnchantment() && enchant.isEmpty()) {
            return Optional.empty();
        }

        boolean doubled = enchant.isPresent()
                && trade.doublePriceEnchantments().contains(enchant.get().enchantment());
        if (doubled) {
            extraCost *= 2;
        }

        int costA = costCount(trade.wants(), extraCost);
        if (costA < 1) {
            return Optional.empty();
        }
        Optional<PredictedOffer.SecondCost> costB = Optional.empty();
        if (trade.additionalWants().isPresent()) {
            int count = costCount(trade.additionalWants().get(), 0);
            if (count < 1) {
                return Optional.empty();
            }
            costB = Optional.of(new PredictedOffer.SecondCost(
                    trade.additionalWants().get().item(), count));
        }

        return Optional.of(new PredictedOffer(
                trade.id(), trade.gives().item(), trade.gives().count(), enchant,
                costA, costB, Math.max(1, trade.maxUses()), trade.xp(), trade.reputationDiscount()));
    }

    /** {@code TradeCost.toItemCost}: constant count plus the additional-cost component, clamped to stack size. */
    private static int costCount(CostDef cost, int extra) {
        return Mth.clamp(cost.count() + extra, 0, cost.maxStackSize());
    }
}
