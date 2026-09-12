package com.villagertradepredicator.core.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/**
 * Layered read access to the game's trade data: the base jar data plus optional
 * built-in datapack overlays (e.g. {@code trade_rebalance}), in pack order, applying
 * vanilla pack-stack semantics — entry JSONs resolve to the LAST layer defining the id,
 * and tags accumulate across layers with each layer's {@code "replace"} flag resetting
 * whatever came before it.
 */
public final class DataAccess {
    private final List<String> overlayRoots;

    public DataAccess() {
        this(List.of());
    }

    public DataAccess(List<String> builtInPacks) {
        this.overlayRoots = builtInPacks.stream()
                .map(pack -> "data/minecraft/datapacks/" + pack + "/data/")
                .toList();
    }

    /** Entry/trade-set JSON: the last layer defining the id wins. */
    public Optional<JsonObject> json(String relativePath) {
        Optional<JsonObject> found = ClasspathTradeData.readJson("data/" + relativePath);
        for (String root : overlayRoots) {
            Optional<JsonObject> overlay = ClasspathTradeData.readJson(root + relativePath);
            if (overlay.isPresent()) {
                found = overlay;
            }
        }
        return found;
    }

    /**
     * Tag values folded across the layers in pack order: a layer's {@code "replace": true}
     * discards everything accumulated before it, otherwise its values append.
     * Empty optional = the tag exists in no layer.
     */
    public Optional<List<JsonElement>> tagValues(String registry, Identifier tagId) {
        String relative = tagId.getNamespace() + "/tags/" + registry + "/" + tagId.getPath() + ".json";
        List<JsonElement> values = new ArrayList<>();
        boolean found = false;
        Optional<JsonObject> base = ClasspathTradeData.readJson("data/" + relative);
        if (base.isPresent()) {
            values.addAll(valuesOf(base.get(), tagId));
            found = true;
        }
        for (String root : overlayRoots) {
            Optional<JsonObject> overlay = ClasspathTradeData.readJson(root + relative);
            if (overlay.isPresent()) {
                JsonObject overlayTag = overlay.get();
                boolean replace = overlayTag.has("replace") && overlayTag.get("replace").getAsBoolean();
                if (replace || !found) {
                    values.clear();
                }
                values.addAll(valuesOf(overlayTag, tagId));
                found = true;
            }
        }
        return found ? Optional.of(values) : Optional.empty();
    }

    private static List<JsonElement> valuesOf(JsonObject tag, Identifier tagId) {
        JsonElement values = tag.get("values");
        if (values == null || !values.isJsonArray()) {
            throw new TradeDataParser.UnsupportedTradeException("tag " + tagId + " has no values array");
        }
        List<JsonElement> out = new ArrayList<>();
        values.getAsJsonArray().forEach(out::add);
        return out;
    }
}
