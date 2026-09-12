package com.villagertradepredicator.core.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.model.TradeSetKey;
import net.minecraft.resources.Identifier;

/**
 * Loads every trade set the running game ships, straight from classpath data — no
 * hardcoded professions or trades, so datapack-defined professions land here too.
 * Each set carries its compatibility verdict; incompatible sets are retained with the
 * reasons but never simulated.
 *
 * <p>{@link #loadFromClasspath(String)} additionally layers a built-in datapack
 * (e.g. {@code trade_rebalance}) over the base data with vanilla pack-stack semantics;
 * the client layer selects the variant matching the world's enabled feature flags.</p>
 */
public final class TradeDataRepository {
    private static final String BASE_ROOT = "data/";
    private static final String OVERLAY_TEMPLATE = "data/minecraft/datapacks/%s/data/";
    private static final Pattern TRADE_SET_PATH =
            Pattern.compile("([^/]+)/trade_set/([^/]+)/level_(\\d+)\\.json");

    private final Map<TradeSetKey, LoadedTradeSet> sets = new LinkedHashMap<>();
    private final String mode;

    private TradeDataRepository(String mode) {
        this.mode = mode;
    }

    public static TradeDataRepository loadFromClasspath() {
        return loadFromClasspath(null);
    }

    /** @param builtInPack built-in datapack name to layer on top, or {@code null} for base data */
    public static TradeDataRepository loadFromClasspath(String builtInPack) {
        String overlayRoot = builtInPack == null
                ? null
                : String.format(Locale.ROOT, OVERLAY_TEMPLATE, builtInPack);
        TradeDataRepository repo = new TradeDataRepository(builtInPack == null ? "base" : builtInPack);
        DataAccess access = new DataAccess(builtInPack == null ? List.of() : List.of(builtInPack));
        TradeDataParser parser = new TradeDataParser(access);

        Map<TradeSetKey, String> paths = new LinkedHashMap<>();
        collectTradeSetPaths(BASE_ROOT, paths);
        if (overlayRoot != null) {
            collectTradeSetPaths(overlayRoot, paths); // overlay overrides base on the same key
        }
        paths.forEach((key, path) -> repo.sets.put(key, loadSet(key, path, parser)));
        return repo;
    }

    private static void collectTradeSetPaths(String root, Map<TradeSetKey, String> paths) {
        for (String path : ClasspathTradeData.listJsonFiles(root)) {
            String relative = path.substring(root.length());
            Matcher m = TRADE_SET_PATH.matcher(relative);
            if (!m.matches()) {
                continue;
            }
            Identifier profession = Identifier.fromNamespaceAndPath(m.group(1), m.group(2));
            int level = Integer.parseInt(m.group(3));
            paths.put(new TradeSetKey(profession, level), path);
        }
    }

    private static LoadedTradeSet loadSet(TradeSetKey key, String path, TradeDataParser parser) {
        Identifier setId = Identifier.fromNamespaceAndPath(key.profession().getNamespace(),
                "trade_set/" + key.profession().getPath() + "/level_" + key.level());
        return ClasspathTradeData.readJson(path).map(json -> {
            try {
                TradeSetDef def = parser.parseTradeSet(setId, json);
                return LoadedTradeSet.ok(key, def);
            } catch (TradeDataParser.UnsupportedTradeException e) {
                return LoadedTradeSet.incompatible(key, Optional.empty(), List.of(e.getMessage()));
            } catch (RuntimeException e) {
                // Malformed/unexpected data: record instead of aborting the whole load
                return LoadedTradeSet.incompatible(key, Optional.empty(),
                        List.of("parse error in " + path + ": " + e));
            }
        }).orElseGet(() -> LoadedTradeSet.incompatible(key, Optional.empty(),
                List.of("unreadable " + path)));
    }

    public Optional<LoadedTradeSet> get(Identifier profession, int level) {
        return Optional.ofNullable(sets.get(new TradeSetKey(profession, level)));
    }

    /** The compatible definition, ready for simulation. */
    public Optional<TradeSetDef> compatible(Identifier profession, int level) {
        return get(profession, level).filter(LoadedTradeSet::isCompatible).flatMap(LoadedTradeSet::def);
    }

    public Collection<LoadedTradeSet> all() {
        return List.copyOf(sets.values());
    }

    public List<Identifier> professions() {
        List<Identifier> out = new ArrayList<>();
        sets.keySet().forEach(k -> {
            if (!out.contains(k.profession())) {
                out.add(k.profession());
            }
        });
        return out;
    }

    /** Which data mode this repository was loaded in ({@code "base"} or a pack name). */
    public String mode() {
        return mode;
    }

    @Override
    public String toString() {
        long compatible = sets.values().stream().filter(LoadedTradeSet::isCompatible).count();
        return String.format(Locale.ROOT, "TradeDataRepository[%s, %d sets, %d compatible]",
                mode, sets.size(), compatible);
    }
}
