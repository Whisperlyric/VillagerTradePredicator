package com.villagertradepredicator.core.data;

import java.util.List;
import java.util.Optional;

import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.model.TradeSetKey;

/**
 * A parsed trade set plus its compatibility verdict. A set with any unsupported content
 * is kept (so the UI can explain why) but never simulated: skipping a single unsupported
 * trade would change the pool size and shift every subsequent RNG draw.
 */
public record LoadedTradeSet(TradeSetKey key, Optional<TradeSetDef> def, List<String> issues) {

    public static LoadedTradeSet ok(TradeSetKey key, TradeSetDef def) {
        return new LoadedTradeSet(key, Optional.of(def), List.of());
    }

    public static LoadedTradeSet incompatible(TradeSetKey key, Optional<TradeSetDef> def, List<String> issues) {
        return new LoadedTradeSet(key, def, List.copyOf(issues));
    }

    public boolean isCompatible() {
        return issues.isEmpty() && def.isPresent();
    }
}
