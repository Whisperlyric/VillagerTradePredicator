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
 */
public final class TradeDataRepository {
    private static final Pattern TRADE_SET_PATH =
            Pattern.compile("data/([^/]+)/trade_set/([^/]+)/level_(\\d+)\\.json");

    private final Map<TradeSetKey, LoadedTradeSet> sets = new LinkedHashMap<>();

    private TradeDataRepository() {}

    public static TradeDataRepository loadFromClasspath() {
        TradeDataRepository repo = new TradeDataRepository();
        for (String path : ClasspathTradeData.listJsonFiles("data")) {
            Matcher m = TRADE_SET_PATH.matcher(path);
            if (!m.matches()) {
                continue;
            }
            Identifier profession = Identifier.fromNamespaceAndPath(m.group(1), m.group(2));
            int level = Integer.parseInt(m.group(3));
            TradeSetKey key = new TradeSetKey(profession, level);
            repo.sets.put(key, loadSet(key, path));
        }
        return repo;
    }

    private static LoadedTradeSet loadSet(TradeSetKey key, String path) {
        Identifier setId = Identifier.fromNamespaceAndPath(key.profession().getNamespace(),
                "trade_set/" + key.profession().getPath() + "/level_" + key.level());
        return ClasspathTradeData.readJson(path).map(json -> {
            try {
                TradeSetDef def = TradeDataParser.parseTradeSet(setId, json);
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

    @Override
    public String toString() {
        long compatible = sets.values().stream().filter(LoadedTradeSet::isCompatible).count();
        return String.format(Locale.ROOT, "TradeDataRepository[%d sets, %d compatible]", sets.size(), compatible);
    }
}
