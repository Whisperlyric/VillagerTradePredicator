package com.villagertradepredicator.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.villagertradepredicator.client.observe.ObservationSession;
import com.villagertradepredicator.client.observe.ObservationSession.AppendStatus;
import com.villagertradepredicator.client.observe.ObservedOffers;
import com.villagertradepredicator.core.model.EnchantmentLevel;
import com.villagertradepredicator.core.model.OfferFingerprint;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session semantics: run continuity, duplicate detection, and undo. */
class ObservationSessionTest {
    private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");

    private static ObservedOffers observed(String villager, int level, String resultItem, int cost) {
        OfferFingerprint fingerprint = new OfferFingerprint(
                Identifier.withDefaultNamespace(resultItem),
                Identifier.withDefaultNamespace("emerald"),
                cost,
                Optional.empty(),
                List.of(new EnchantmentLevel(Identifier.withDefaultNamespace("mending"), 1)));
        return new ObservedOffers(UUID.nameUUIDFromBytes(villager.getBytes()), level, 0, List.of(fingerprint));
    }

    @Test
    void runContinuityAndDuplicates() {
        ObservationSession session = new ObservationSession();
        assertEquals(AppendStatus.STARTED, session.append(observed("v1", 1, "a", 10), LIBRARIAN));
        assertEquals(1, session.groups());
        assertEquals(AppendStatus.APPENDED, session.append(observed("v1", 1, "b", 12), LIBRARIAN));
        assertEquals(2, session.groups());
        assertEquals(AppendStatus.DUPLICATE_APPENDED, session.append(observed("v1", 1, "b", 12), LIBRARIAN));
        assertEquals(3, session.groups(), "duplicate is appended and flagged, not dropped");
        // a different villager/level starts a fresh run
        assertEquals(AppendStatus.STARTED, session.append(observed("v1", 2, "a", 10), LIBRARIAN));
        assertEquals(1, session.groups());
    }

    @Test
    void undoRemovesLastAndResetsIdentityWhenEmpty() {
        ObservationSession session = new ObservationSession();
        assertFalse(session.removeLast());
        session.append(observed("v1", 1, "a", 10), LIBRARIAN);
        session.append(observed("v1", 1, "b", 12), LIBRARIAN);
        assertTrue(session.removeLast());
        assertEquals(1, session.groups());
        assertTrue(session.removeLast());
        assertFalse(session.removeLast());
        // identity was forgotten when the run drained — next read starts over
        assertEquals(AppendStatus.STARTED, session.append(observed("v1", 1, "a", 10), LIBRARIAN));
    }
}
