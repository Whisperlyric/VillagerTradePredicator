package com.villagertradepredicator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.locate.RoundIterator;
import com.villagertradepredicator.core.model.PredictedOffer;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value parity against the author's validated CLI predictor
 * (refs/Villager-Trade-Calculator, itself verified in-game). Any divergence here means
 * seed derivation, data order, or RNG draw order is wrong.
 */
class GoldenParityTest {
    private static final long SEED1 = 7662585126525589278L;
    private static final long SEED2 = 123456789L;
    private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");
    private static final Identifier FARMER = Identifier.withDefaultNamespace("farmer");

    private static TradeDataRepository repo;

    @BeforeAll
    static void setup() {
        TestSupport.bootstrap();
        repo = TradeDataRepository.loadFromClasspath();
    }

    private static TradeSetDef set(Identifier profession, int level) {
        return repo.compatible(profession, level).orElseThrow(
                () -> new AssertionError(profession + " level " + level + " not compatible"));
    }

    /** Compact identity of a round: {@code book:<ench>:<level>:<price>} or the trade id path. */
    private static List<String> describe(Identifier profession, int level, long seed, int offset) {
        RoundIterator it = new RoundIterator(set(profession, level),
                TradeSequences.create(seed, SequenceConfig.DEFAULT,
                        TradeSequences.sequenceId(profession, level)),
                SimContext.withoutVariant());
        for (int i = 0; i < offset; i++) {
            it.next();
        }
        List<String> out = new ArrayList<>();
        for (PredictedOffer offer : it.next().offers()) {
            out.add(offer.storedEnchantment()
                    .map(e -> "book:" + e.enchantment().getPath() + ":" + e.level() + ":" + offer.costA())
                    .orElseGet(offer.tradeId()::toString));
        }
        return out;
    }

    private static String book(String ench, int level, int price) {
        return "book:" + ench + ":" + level + ":" + price;
    }

    private static String trade(String path) {
        return "minecraft:" + path;
    }

    @Test
    void repositoryFindsAllVanillaProfessions() {
        assertTrue(repo.professions().size() >= 13, "expected >=13 professions, got " + repo.professions());
        assertTrue(repo.professions().contains(LIBRARIAN));
    }

    @Test
    void sequenceDerivationMatchesReferenceIntermediates() {
        // From the reference tool's debug output for minecraft:trade_set/librarian/level_1:
        // after XOR the raw state is lo=0x17234611BEB0AD3E hi=0xE99E02949D7D25D8, and the
        // stafford13-mixed state is lo=0xDDF7BE1789D3DABE hi=0x48F9718A062D2DDA.
        assertEquals(0xDDF7BE1789D3DABEL, RandomSupport.mixStafford13(0x17234611BEB0AD3EL));
        assertEquals(0x48F9718A062D2DDAL, RandomSupport.mixStafford13(0xE99E02949D7D25D8L));

        XoroshiroRandomSource derived = TradeSequences.create(SEED1, SequenceConfig.DEFAULT,
                TradeSequences.sequenceId(LIBRARIAN, 1));
        int[] expected = {1, 2, 1, 2, 2, 1, 2, 1};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], derived.nextInt(3), "draw " + i);
        }
    }

    @Test
    void librarianLevel1MatchesReferenceOffsets0to5() {
        List<List<String>> expected = List.of(
                List.of(book("multishot", 1, 10), trade("librarian/1/emerald_bookshelf")),
                List.of(trade("librarian/1/emerald_bookshelf"), book("density", 4, 17)),
                List.of(trade("librarian/1/emerald_bookshelf"), book("bane_of_arthropods", 3, 25)),
                List.of(book("silk_touch", 1, 11), trade("librarian/1/emerald_bookshelf")),
                List.of(trade("librarian/1/emerald_bookshelf"), book("power", 2, 22)),
                List.of(book("mending", 1, 32), trade("librarian/1/emerald_bookshelf")));
        for (int offset = 0; offset < expected.size(); offset++) {
            assertEquals(expected.get(offset), describe(LIBRARIAN, 1, SEED1, offset),
                    "librarian L1 offset " + offset);
        }
    }

    @Test
    void librarianLevels2to5MatchReference() {
        assertEquals(List.of(trade("librarian/2/emerald_lantern"), book("depth_strider", 3, 45)),
                describe(LIBRARIAN, 2, SEED1, 0));
        assertEquals(List.of(trade("librarian/3/emerald_glass"), book("sharpness", 2, 32)),
                describe(LIBRARIAN, 3, SEED1, 0));
        assertEquals(List.of(book("sharpness", 5, 62), trade("librarian/4/writable_book_emerald")),
                describe(LIBRARIAN, 4, SEED1, 0));
        // L5: amount=3 but the pool holds only 2 trades — generation stops when the pool empties
        assertEquals(List.of(trade("librarian/5/emerald_red_candle"), trade("librarian/5/emerald_yellow_candle")),
                describe(LIBRARIAN, 5, SEED1, 0));
    }

    @Test
    void farmerLevel1MatchesReference() {
        assertEquals(List.of(trade("farmer/1/beetroot_emerald"), trade("farmer/1/wheat_emerald")),
                describe(FARMER, 1, SEED1, 0));
    }

    @Test
    void secondSeedMatchesReference() {
        assertEquals(List.of(trade("librarian/1/paper_emerald"), trade("librarian/1/emerald_bookshelf")),
                describe(LIBRARIAN, 1, SEED2, 0));
        assertEquals(List.of(book("mending", 1, 10), trade("librarian/1/emerald_bookshelf")),
                describe(LIBRARIAN, 1, SEED2, 1));
        assertEquals(List.of(trade("librarian/1/paper_emerald"), book("lunge", 1, 8)),
                describe(LIBRARIAN, 1, SEED2, 2));
    }

    @Test
    void bookCarriesSecondCostAndReputationDiscount() {
        RoundIterator it = new RoundIterator(set(LIBRARIAN, 1),
                TradeSequences.create(SEED1, SequenceConfig.DEFAULT, TradeSequences.sequenceId(LIBRARIAN, 1)),
                SimContext.withoutVariant());
        PredictedOffer book = it.next().offers().stream()
                .filter(o -> o.storedEnchantment().isPresent()).findFirst().orElseThrow();
        assertEquals(Optional.of(new PredictedOffer.SecondCost(
                Identifier.withDefaultNamespace("book"), 1)), book.costB());
        assertEquals(12, book.maxUses());
        assertEquals(0.2f, book.reputationDiscount(), 1e-6f);
    }
}
