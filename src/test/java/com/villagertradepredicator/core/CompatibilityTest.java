package com.villagertradepredicator.core;

import java.util.List;

import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.data.LoadedTradeSet;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compatibility gate must disable exactly the trade sets whose generation we cannot
 * replay (unsupported item modifiers, randomized numbers, unknown predicates) — and keep
 * everything else predictable.
 */
class CompatibilityTest {
    private static TradeDataRepository repo;

    @BeforeAll
    static void setup() {
        TestSupport.bootstrap();
        repo = TradeDataRepository.loadFromClasspath();
    }

    private static LoadedTradeSet set(String profession, int level) {
        return repo.get(Identifier.withDefaultNamespace(profession), level).orElseThrow();
    }

    @Test
    void librarianAllLevelsCompatible() {
        for (int level = 1; level <= 5; level++) {
            assertTrue(set("librarian", level).isCompatible(),
                    "librarian L" + level + ": " + set("librarian", level).issues());
        }
    }

    @Test
    void cartographerMapsIncompatibleDueToExplorationMapModifier() {
        LoadedTradeSet l2 = set("cartographer", 2);
        assertFalse(l2.isCompatible());
        assertTrue(l2.issues().stream().anyMatch(i -> i.contains("exploration_map")),
                () -> "issues: " + l2.issues());
    }

    @Test
    void fishermanBoatTradesStayCompatible() {
        // L5: variant-gated boat trades (merchant_predicate on villager/variant) — simulatable
        assertTrue(set("fisherman", 5).isCompatible(),
                "fisherman L5: " + set("fisherman", 5).issues());
    }

    @Test
    void fishermanEnchantedRodIsFlagged() {
        LoadedTradeSet l3 = set("fisherman", 3);
        assertFalse(l3.isCompatible());
        assertTrue(l3.issues().stream().anyMatch(i -> i.contains("enchant_with_levels")),
                () -> "issues: " + l3.issues());
    }

    @Test
    void enchantedEquipmentTradesAreFlagged() {
        // Armorer sells enchantment-bearing equipment at higher levels — some armorer set
        // must be flagged, whichever unsupported modifier shape the data uses.
        assertTrue(repo.all().stream().anyMatch(s -> !s.isCompatible()
                        && s.key().profession().getPath().equals("armorer")),
                "expected incompatible armorer sets");
    }

    @Test
    void incompatibleSetsCarryReadableReasons() {
        List<String> issues = set("cartographer", 2).issues();
        assertFalse(issues.isEmpty());
        issues.forEach(i -> assertFalse(i.isBlank()));
    }
}
