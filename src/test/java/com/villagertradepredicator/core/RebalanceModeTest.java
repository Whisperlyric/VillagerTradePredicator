package com.villagertradepredicator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.locate.RoundIterator;
import com.villagertradepredicator.core.model.EnchantmentLevel;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeDef;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trade-rebalance mode: the built-in {@code trade_rebalance} datapack layered over the
 * base data (replace-tags, per-biome book pools, {@code set_enchantments} armor, uniform
 * price providers). The vanilla generation algorithm is shared with the base mode and
 * already parity-verified there; these tests pin the data-layer fidelity — pool order,
 * variant gating, preset enchantments, and price-provider draws.
 */
class RebalanceModeTest {
    private static final long SEED = 7662585126525589278L;
    private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");
    private static final Identifier ARMORER = Identifier.withDefaultNamespace("armorer");
    private static final Identifier JUNGLE = Identifier.withDefaultNamespace("jungle");
    private static final Identifier DESERT = Identifier.withDefaultNamespace("desert");
    private static final Identifier SNOW = Identifier.withDefaultNamespace("snow");

    private static TradeDataRepository base;
    private static TradeDataRepository rebalance;
    private static TradeSetDef librarianL1;

    @BeforeAll
    static void setup() {
        TestSupport.bootstrap();
        base = TradeDataRepository.loadFromClasspath();
        rebalance = TradeDataRepository.loadFromClasspath("trade_rebalance");
        librarianL1 = rebalance.compatible(LIBRARIAN, 1).orElseThrow();
    }

    private static RoundIterator rounds(TradeSetDef set, SimContext ctx) {
        return new RoundIterator(set,
                TradeSequences.create(SEED, SequenceConfig.DEFAULT,
                        set.randomSequence()),
                ctx);
    }

    private static RoundIterator librarian(int level, SimContext ctx) {
        return new RoundIterator(rebalance.compatible(LIBRARIAN, level).orElseThrow(),
                TradeSequences.create(SEED, SequenceConfig.DEFAULT,
                        TradeSequences.sequenceId(LIBRARIAN, level)),
                ctx);
    }

    @Test
    void librarianL1PoolIsOverlayOrder() {
        List<Identifier> expected = new ArrayList<>(List.of(
                Identifier.withDefaultNamespace("librarian/1/paper_emerald"),
                Identifier.withDefaultNamespace("librarian/1/emerald_bookshelf")));
        for (String biome : new String[]{"desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga"}) {
            expected.add(Identifier.withDefaultNamespace("librarian/1/emerald_and_book_" + biome + "_enchanted_book"));
        }
        List<Identifier> actual = librarianL1.trades().stream().map(TradeDef::id).toList();
        assertEquals(expected, actual, "rebalance pool must follow the overlay tag order");
    }

    @Test
    void baseRepositoryIsUnaffected() {
        TradeSetDef baseLibrarian = base.compatible(LIBRARIAN, 1).orElseThrow();
        assertEquals(3, baseLibrarian.trades().size());
        assertEquals("base", base.mode());
        assertEquals("trade_rebalance", rebalance.mode());
    }

    @Test
    void jungleVariantOnlyOffersJungleBooks() {
        Set<Identifier> junglePool = Set.of(
                Identifier.withDefaultNamespace("feather_falling"),
                Identifier.withDefaultNamespace("projectile_protection"),
                Identifier.withDefaultNamespace("power"));
        int books = 0;
        RoundIterator it = librarian(1, SimContext.withVariant(JUNGLE));
        for (int round = 0; round < 200; round++) {
            for (PredictedOffer offer : it.next().offers()) {
                if (offer.enchantments().isEmpty()) {
                    assertTrue(offer.tradeId().getPath().endsWith("paper_emerald")
                            || offer.tradeId().getPath().endsWith("emerald_bookshelf"),
                            "unexpected plain trade " + offer.tradeId());
                    continue;
                }
                books++;
                assertEquals("librarian/1/emerald_and_book_jungle_enchanted_book",
                        offer.tradeId().getPath(), "a foreign biome book leaked through");
                for (EnchantmentLevel enchant : offer.enchantments()) {
                    assertTrue(junglePool.contains(enchant.enchantment()),
                            "enchant outside trades/jungle_common: " + enchant);
                }
            }
        }
        assertTrue(books > 10, "expected biome books to appear, got " + books);
    }

    @Test
    void unknownVariantNeverOffersBiomeBooks() {
        RoundIterator it = librarian(1, SimContext.withoutVariant());
        for (int round = 0; round < 300; round++) {
            for (PredictedOffer offer : it.next().offers()) {
                assertTrue(offer.enchantments().isEmpty(),
                        "biome book offered without a variant: " + offer);
            }
        }
    }

    @Test
    void maxLevelEnchantmentSkipsLevelDraw() {
        // snow L5 book: enchant_randomly with the single-option pool [silk_touch]
        // (max level 1 -> no level draw) — silk touch level must be exactly 1 and the
        // mending book belongs to swamp. Also verifies prices follow the usual formula.
        RoundIterator snow = librarian(5, SimContext.withVariant(SNOW));
        for (int round = 0; round < 400; round++) {
            for (PredictedOffer offer : snow.next().offers()) {
                if (offer.enchantments().isEmpty()) {
                    continue;
                }
                assertEquals("minecraft:silk_touch",
                        offer.enchantments().get(0).enchantment().toString());
                assertEquals(1, offer.enchantments().get(0).level());
                int expectedMin = 2 + 3, expectedMax = 2 + 14 + 3;
                assertTrue(offer.costA() >= expectedMin && offer.costA() <= expectedMax,
                        "silk touch price " + offer.costA() + " outside [5,19]");
                return;
            }
        }
        throw new AssertionError("snow L5 book never appeared in 400 rounds");
    }

    @Test
    void desertArmorerOffersPresetEnchantments() {
        // armorer L4 rebalance: 26 entries, all biome-gated; desert boots carry
        // set_enchantments(thorns 1) at 8 emeralds, price drawn from a uniform provider.
        TradeSetDef set = rebalance.compatible(ARMORER, 4).orElseThrow();
        RoundIterator it = rounds(set, SimContext.withVariant(DESERT));
        for (int round = 0; round < 600; round++) {
            for (PredictedOffer offer : it.next().offers()) {
                assertTrue(offer.tradeId().getPath().contains("_desert"),
                        "foreign biome armor leaked through: " + offer.tradeId());
                if (!offer.tradeId().getPath().endsWith("emerald_enchanted_iron_boots_desert")) {
                    continue;
                }
                assertEquals(List.of(new EnchantmentLevel(
                        Identifier.withDefaultNamespace("thorns"), 1)), offer.enchantments());
                assertEquals(8, offer.costA());
                assertTrue(offer.costA() >= 8 && offer.costA() <= 8 + 64 - 8, "clamped price sane");
                return;
            }
        }
        throw new AssertionError("desert boots never appeared in 600 rounds");
    }

    @Test
    void uniformPriceProviderDrawsAndClamps() {
        // desert L5 book: fixed efficiency III, price = round(11 + uniform(0..35)) in [11,46]
        RoundIterator it = librarian(5, SimContext.withVariant(DESERT));
        for (int round = 0; round < 600; round++) {
            for (PredictedOffer offer : it.next().offers()) {
                if (!offer.tradeId().getPath().endsWith("emerald_and_book_desert_enchanted_book")) {
                    continue;
                }
                assertEquals(1, offer.enchantments().size());
                assertEquals("minecraft:efficiency",
                        offer.enchantments().get(0).enchantment().toString());
                assertEquals(3, offer.enchantments().get(0).level());
                assertTrue(offer.costA() >= 11 && offer.costA() <= 46,
                        "uniform price " + offer.costA() + " outside [11,46]");
                return;
            }
        }
        throw new AssertionError("desert L5 book never appeared in 600 rounds");
    }

    @Test
    void predictionsAreDeterministic() {
        RoundIterator a = librarian(1, SimContext.withVariant(JUNGLE));
        RoundIterator b = librarian(1, SimContext.withVariant(JUNGLE));
        for (int round = 0; round < 50; round++) {
            assertEquals(a.next().offers(), b.next().offers(), "round " + round);
        }
    }

    @Test
    void gateStillAppliesInRebalanceMode() {
        // fisherman L3 (enchant_with_levels) and cartographer L2 (exploration_map) come
        // from base data and stay disabled; all rebalance-replaced sets are simulatable.
        for (int level = 1; level <= 5; level++) {
            assertTrue(rebalance.compatible(LIBRARIAN, level).isPresent(), "librarian L" + level);
            assertTrue(rebalance.compatible(ARMORER, level).isPresent(), "armorer L" + level);
        }
        assertFalse(rebalance.get(Identifier.withDefaultNamespace("fisherman"), 3).orElseThrow().isCompatible());
        assertFalse(rebalance.get(Identifier.withDefaultNamespace("cartographer"), 2).orElseThrow().isCompatible());
    }
}
