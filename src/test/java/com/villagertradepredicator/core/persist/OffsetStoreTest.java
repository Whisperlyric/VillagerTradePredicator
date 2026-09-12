package com.villagertradepredicator.core.persist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip and identity semantics of the persisted sequence positions. */
class OffsetStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingFileStartsEmptyAndCreatesServerOnFirstIp() {
        OffsetStore store = OffsetStore.load(dir.resolve("offsets.json"));
        assertEquals(Optional.empty(), store.offset("s1", "default",
                "minecraft:trade_set/librarian/level_1"));
        String id = store.serverForIp("play.example.com:25565");
        assertEquals("s1", id);
        assertEquals("s1", store.serverForIp("play.example.com:25565"), "same ip must be stable");
    }

    @Test
    void ipAliasesCollapseToOneServer() {
        OffsetStore store = OffsetStore.load(dir.resolve("offsets.json"));
        String first = store.serverForIp("a.example.com");
        store.mapIp("b.example.com", first);
        assertEquals(first, store.serverForIp("b.example.com"));
        assertEquals(first, store.serverForIp("a.example.com"));
    }

    @Test
    void offsetsGroupUnderServerAndWorldAndSurviveReload() throws Exception {
        Path file = dir.resolve("offsets.json");
        OffsetStore store = OffsetStore.load(file);
        String id = store.serverForIp("play.example.com");
        store.setSeed(id, 7662585126525589278L);
        // known-seed world bucket
        store.putOffset(id, "7662585126525589278",
                "minecraft:trade_set/librarian/level_1", 137, -6132508605523384832L, 5251083674425136153L);
        // unknown-seed fallback bucket
        store.putOffset(id, OffsetStore.DEFAULT_WORLD,
                "minecraft:trade_set/librarian/level_2", 3, 1L, 2L);
        store.save();

        OffsetStore reloaded = OffsetStore.load(file);
        assertEquals(Optional.of(137L), reloaded.offset(id, "7662585126525589278",
                "minecraft:trade_set/librarian/level_1"));
        assertEquals(Optional.of(3L), reloaded.offset(id, OffsetStore.DEFAULT_WORLD,
                "minecraft:trade_set/librarian/level_2"));
        assertEquals(Optional.of(7662585126525589278L), reloaded.seed(id));
        assertEquals(Optional.empty(), reloaded.offset(id, "7662585126525589278",
                "minecraft:trade_set/librarian/level_3"));
    }

    @Test
    void sameSequenceUnderDifferentSeedsStaysSeparate() {
        OffsetStore store = OffsetStore.load(dir.resolve("offsets.json"));
        String id = store.serverForIp("play.example.com");
        store.putOffset(id, "100", "minecraft:trade_set/librarian/level_1", 5, 10L, 20L);
        store.putOffset(id, "200", "minecraft:trade_set/librarian/level_1", 9, 30L, 40L);
        assertEquals(Optional.of(5L), store.offset(id, "100", "minecraft:trade_set/librarian/level_1"));
        assertEquals(Optional.of(9L), store.offset(id, "200", "minecraft:trade_set/librarian/level_1"));
    }

    @Test
    void corruptFileStartsFresh() throws Exception {
        Path file = dir.resolve("offsets.json");
        Files.writeString(file, "{ not json !!!");
        OffsetStore store = OffsetStore.load(file);
        assertTrue(store.serverForIp("x").startsWith("s"));
    }
}
