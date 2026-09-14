package com.villagertradepredicator.core.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Persisted sequence positions ("how far has each list stepped"), grouped exactly as the
 * game identifies them: {@code servers/<serverId>/worlds/<worldKey>/sequences/<seqId>}.
 *
 * <p>File placement follows the session type (the client layer decides): singleplayer
 * keeps it inside the save directory (one file per world — multiple saves never share a
 * bucket), multiplayer keeps it under the game config directory.</p>
 *
 * <p>{@code serverId} is a stable local id ("s1", "s2", ...) that several addresses can
 * alias (one server reachable through different IPs is the same server — see
 * {@link #mapIp}); {@code worldKey} is the world seed when the client receives one
 * (Velocity-style setups), otherwise the constant {@link #DEFAULT_WORLD} — a world
 * without a known seed stores normally under that bucket.</p>
 *
 * <p>The seed itself is also remembered per server so the UI can detect a mismatch
 * (rolled-back world or a proxy pointing at a different sub-server) and ask the player.</p>
 */
public final class OffsetStore {
    public static final String DEFAULT_WORLD = "default";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final JsonObject root;

    private OffsetStore(Path file, JsonObject root) {
        this.file = file;
        this.root = root;
    }

    public static OffsetStore load(Path file) {
        JsonObject root = null;
        if (Files.isRegularFile(file)) {
            try {
                root = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (Exception e) {
                root = null; // corrupt file: start fresh rather than crash the client
            }
        }
        if (root == null || !root.has("version")) {
            root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("nextServerId", 1);
            root.add("ipAliases", new JsonObject());
            root.add("servers", new JsonObject());
        }
        return new OffsetStore(file, root);
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save " + file, e);
        }
    }

    // ---------------------------------------------------------------- servers

    /** The server id behind {@code ip}, creating (and aliasing) a fresh id on first sight. */
    public String serverForIp(String ip) {
        JsonObject aliases = aliases();
        if (aliases.has(ip)) {
            return aliases.get(ip).getAsString();
        }
        String id = newServerId();
        aliases.addProperty(ip, id);
        servers(); // touch the servers object
        return id;
    }

    /** Binds an additional address to an existing server id (same backend, different IP). */
    public void mapIp(String ip, String serverId) {
        aliases().addProperty(ip, serverId);
    }

    /** Remember the world seed for a server; replaces a previously stored value. */
    public void setSeed(String serverId, long seed) {
        server(serverId);
        // remembered for mismatch detection; the worlds key uses the same value
        JsonObject server = server(serverId);
        server.addProperty("knownSeed", seed);
    }

    public Optional<Long> seed(String serverId) {
        JsonObject server = servers().has(serverId) ? servers().getAsJsonObject(serverId) : null;
        if (server == null || !server.has("knownSeed")) {
            return Optional.empty();
        }
        return Optional.of(server.get("knownSeed").getAsLong());
    }

    // ---------------------------------------------------------------- sequences

    /** The stored step of one list, or empty when that sequence was never observed. */
    public Optional<Long> offset(String serverId, String worldKey, String seqId) {
        JsonObject world = worldBucket(serverId, worldKey, false);
        if (world == null || !world.has(seqId)) {
            return Optional.empty();
        }
        return Optional.of(world.getAsJsonObject(seqId).get("offset").getAsLong());
    }

    /** Stores the step plus the generator state, so scans can resume exactly here. */
    public void putOffset(String serverId, String worldKey, String seqId,
            int nextOffset, long stateLo, long stateHi) {
        JsonObject world = worldBucket(serverId, worldKey, true);
        JsonObject seq = new JsonObject();
        // nextOffset = 已消耗轮数 = 下一轮将消费的轮号（状态 lo/hi 对应"已消费完这些轮"之后）
        seq.addProperty("offset", nextOffset);
        seq.addProperty("lo", String.valueOf(stateLo));
        seq.addProperty("hi", String.valueOf(stateHi));
        world.add(seqId, seq);
    }

    // ---------------------------------------------------------------- json plumbing

    private JsonObject aliases() {
        if (!root.has("ipAliases")) {
            root.add("ipAliases", new JsonObject());
        }
        return root.getAsJsonObject("ipAliases");
    }

    private JsonObject servers() {
        if (!root.has("servers")) {
            root.add("servers", new JsonObject());
        }
        return root.getAsJsonObject("servers");
    }

    private JsonObject server(String serverId) {
        JsonObject servers = servers();
        if (!servers.has(serverId)) {
            servers.add(serverId, new JsonObject());
        }
        return servers.getAsJsonObject(serverId);
    }

    private JsonObject worldBucket(String serverId, String worldKey, boolean create) {
        JsonObject server = server(serverId);
        if (!server.has("worlds")) {
            if (!create) {
                return null;
            }
            server.add("worlds", new JsonObject());
        }
        JsonObject worlds = server.getAsJsonObject("worlds");
        if (!worlds.has(worldKey)) {
            if (!create) {
                return null;
            }
            worlds.add(worldKey, new JsonObject());
        }
        return worlds.getAsJsonObject(worldKey);
    }

    private String newServerId() {
        int next = root.has("nextServerId") ? root.get("nextServerId").getAsInt() : 1;
        root.addProperty("nextServerId", next + 1);
        return "s" + next;
    }
}
