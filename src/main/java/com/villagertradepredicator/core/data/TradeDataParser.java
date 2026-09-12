package com.villagertradepredicator.core.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagertradepredicator.core.model.CostDef;
import com.villagertradepredicator.core.model.EnchantRandomlyDef;
import com.villagertradepredicator.core.model.EnchantRef;
import com.villagertradepredicator.core.model.GiveDef;
import com.villagertradepredicator.core.model.TradeDef;
import com.villagertradepredicator.core.model.TradePredicate;
import com.villagertradepredicator.core.model.TradeSetDef;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Parses the vanilla trade data files (field names verified against the 26.x jar):
 * {@code trade_set/<prof>/level_<n>.json}, {@code tags/villager_trade/...} and
 * {@code villager_trade/...}, plus enchantment tags and definitions for option pools
 * and price doubling.
 *
 * <p>Anything the simulator cannot replay exactly raises {@link UnsupportedTradeException};
 * callers convert that into a compatibility issue that disables the containing set.</p>
 */
public final class TradeDataParser {
    private TradeDataParser() {}

    /** Content the predictor refuses to simulate (whole set gets disabled). */
    public static final class UnsupportedTradeException extends RuntimeException {
        public UnsupportedTradeException(String reason) {
            super(reason);
        }
    }

    // ---------------------------------------------------------------- trade_set

    public static TradeSetDef parseTradeSet(Identifier setId, JsonObject json) {
        Identifier seq = Identifier.parse(requireString(json, "random_sequence"));
        int amount = requireInt(json, "amount");
        boolean allowDuplicates = optionalBoolean(json, "allow_duplicates", false);
        List<TradeDef> trades = resolveTradesReference(json.get("trades"));
        return new TradeSetDef(setId, seq, amount, allowDuplicates, List.copyOf(trades));
    }

    /**
     * {@code trades}: {@code "#minecraft:librarian/level_1"} (tag in the villager_trade
     * registry), a single trade id, or an array of those. Order defines the pool order
     * and therefore the {@code nextInt(bound)} mapping — it is preserved verbatim.
     */
    private static List<TradeDef> resolveTradesReference(JsonElement ref) {
        List<Identifier> ids = new ArrayList<>();
        collectReferencedIds(ref, "villager_trade", ids, new LinkedHashSet<>());
        List<TradeDef> trades = new ArrayList<>(ids.size());
        for (Identifier id : ids) {
            String path = "data/" + id.getNamespace() + "/villager_trade/" + id.getPath() + ".json";
            JsonObject json = ClasspathTradeData.readJson(path)
                    .orElseThrow(() -> new UnsupportedTradeException("missing trade definition " + id));
            trades.add(parseTrade(id, json));
        }
        return trades;
    }

    private static void collectReferencedIds(JsonElement ref, String tagRegistry,
            List<Identifier> out, Set<Identifier> visitingTags) {
        if (ref.isJsonArray()) {
            for (JsonElement e : ref.getAsJsonArray()) {
                collectReferencedIds(e, tagRegistry, out, visitingTags);
            }
            return;
        }
        String raw = ref.getAsString();
        if (raw.startsWith("#")) {
            Identifier tagId = Identifier.parse(raw.substring(1));
            if (!visitingTags.add(tagId)) {
                throw new UnsupportedTradeException("cyclic trade tag " + tagId);
            }
            for (JsonElement value : readTagValues(tagId, tagRegistry)) {
                collectReferencedIds(value, tagRegistry, out, visitingTags);
            }
            visitingTags.remove(tagId);
            return;
        }
        out.add(Identifier.parse(raw));
    }

    private static JsonArray readTagValues(Identifier tagId, String tagRegistry) {
        String path = "data/" + tagId.getNamespace() + "/tags/" + tagRegistry + "/" + tagId.getPath() + ".json";
        JsonObject tag = ClasspathTradeData.readJson(path)
                .orElseThrow(() -> new UnsupportedTradeException("missing tag " + tagRegistry + "/" + tagId));
        JsonElement values = tag.get("values");
        if (values == null || !values.isJsonArray()) {
            throw new UnsupportedTradeException("tag " + tagId + " has no values array");
        }
        return values.getAsJsonArray();
    }

    // ---------------------------------------------------------------- villager_trade

    private static TradeDef parseTrade(Identifier id, JsonObject json) {
        CostDef wants = parseCost(json, "wants");
        Optional<CostDef> additionalWants = json.has("additional_wants")
                ? Optional.of(parseCost(json, "additional_wants"))
                : Optional.empty();
        GiveDef gives = parseGive(json);

        int maxUses = (int) Math.round(optionalDouble(json, "max_uses", 4.0));
        float reputationDiscount = (float) optionalDouble(json, "reputation_discount", 0.0);
        int xp = (int) Math.round(optionalDouble(json, "xp", 1.0));

        Optional<TradePredicate> predicate = parsePredicate(json);

        Optional<EnchantRandomlyDef> enchantRandomly = Optional.empty();
        boolean requireStoredEnchantment = false;
        if (json.has("given_item_modifiers")) {
            for (JsonElement e : json.getAsJsonArray("given_item_modifiers")) {
                JsonObject modifier = e.getAsJsonObject();
                String function = requireString(modifier, "function");
                switch (function) {
                    case "minecraft:enchant_randomly" -> enchantRandomly = Optional.of(parseEnchantRandomly(modifier));
                    case "minecraft:filtered" -> requireStoredEnchantment = parseFiltered(modifier);
                    default -> throw new UnsupportedTradeException("item modifier " + function);
                }
            }
        }

        Set<Identifier> doublePrice = json.has("double_trade_price_enchantments")
                ? Set.copyOf(resolveEnchantmentIds(json.get("double_trade_price_enchantments")))
                : Set.of();

        return new TradeDef(id, wants, additionalWants, gives, maxUses, reputationDiscount, xp,
                predicate, enchantRandomly, requireStoredEnchantment, doublePrice);
    }

    private static CostDef parseCost(JsonObject trade, String field) {
        JsonObject cost = trade.getAsJsonObject(field);
        if (cost.has("components")) {
            throw new UnsupportedTradeException(field + " uses component matching");
        }
        JsonElement count = cost.get("count");
        if (count != null && !count.getAsJsonPrimitive().isNumber()) {
            throw new UnsupportedTradeException(field + " count is randomized");
        }
        Identifier item = Identifier.parse(requireString(cost, "id"));
        return new CostDef(item, count == null ? 1 : count.getAsInt(), maxStackSize(item));
    }

    private static GiveDef parseGive(JsonObject trade) {
        JsonObject give = trade.getAsJsonObject("gives");
        if (give.has("components")) {
            throw new UnsupportedTradeException("gives uses preset components");
        }
        JsonElement count = give.get("count");
        if (count != null && !count.getAsJsonPrimitive().isNumber()) {
            throw new UnsupportedTradeException("gives count is randomized");
        }
        Identifier item = Identifier.parse(requireString(give, "id"));
        validateItem(item);
        return new GiveDef(item, count == null ? 1 : count.getAsInt());
    }

    private static EnchantRandomlyDef parseEnchantRandomly(JsonObject modifier) {
        if (modifier.has("only_compatible") && modifier.get("only_compatible").getAsBoolean()) {
            throw new UnsupportedTradeException("enchant_randomly with only_compatible");
        }
        JsonElement options = modifier.get("options");
        if (options == null) {
            throw new UnsupportedTradeException("enchant_randomly without explicit options");
        }
        List<EnchantRef> pool = resolveEnchantments(options);
        if (pool.isEmpty()) {
            throw new UnsupportedTradeException("enchant_randomly options pool is empty");
        }
        boolean includeCost = optionalBoolean(modifier, "include_additional_cost_component", false);
        return new EnchantRandomlyDef(List.copyOf(pool), includeCost);
    }

    /**
     * The known trailing {@code filtered}+{@code discard} safety step: keep the offer only
     * if the result has a stored enchantment. Consumes no RNG. Any other filtered shape is
     * rejected.
     */
    private static boolean parseFiltered(JsonObject modifier) {
        JsonElement onFail = modifier.get("on_fail");
        if (onFail == null || !"minecraft:discard".equals(onFail.getAsJsonObject().get("function").getAsString())) {
            throw new UnsupportedTradeException("filtered without discard on_fail");
        }
        JsonObject filter = modifier.getAsJsonObject("item_filter");
        JsonObject predicates = filter.getAsJsonObject("predicates");
        if (predicates == null || !predicates.has("minecraft:stored_enchantments")) {
            throw new UnsupportedTradeException("filtered on unsupported predicate "
                    + (predicates == null ? "?" : predicates.keySet().toString()));
        }
        return true;
    }

    private static Optional<TradePredicate> parsePredicate(JsonObject trade) {
        if (!trade.has("merchant_predicate")) {
            return Optional.empty();
        }
        JsonObject predicate = trade.getAsJsonObject("merchant_predicate");
        if (!"minecraft:entity_properties".equals(requireString(predicate, "condition"))
                || !"this".equals(requireString(predicate, "entity"))) {
            throw new UnsupportedTradeException("merchant_predicate of unsupported type");
        }
        if (!predicate.has("predicate")) {
            return Optional.empty();
        }
        JsonObject predicateBody = predicate.getAsJsonObject("predicate");
        // 26.1 ships "predicates", 26.2 renames the map to "minecraft:predicates"
        JsonObject predicates = predicateBody.has("predicates")
                ? predicateBody.getAsJsonObject("predicates")
                : predicateBody.getAsJsonObject("minecraft:predicates");
        if (predicates == null || predicates.entrySet().isEmpty()) {
            return Optional.empty();
        }
        for (String key : predicates.keySet()) {
            if (!key.equals("minecraft:villager/variant")) {
                throw new UnsupportedTradeException("merchant_predicate on " + key);
            }
        }
        JsonElement variant = predicates.get("minecraft:villager/variant");
        Set<Identifier> allowed = new LinkedHashSet<>();
        if (variant.isJsonArray()) {
            for (JsonElement e : variant.getAsJsonArray()) {
                allowed.add(Identifier.parse(e.getAsString()));
            }
        } else {
            allowed.add(Identifier.parse(variant.getAsString()));
        }
        return Optional.of(new TradePredicate.VillagerVariant(Set.copyOf(allowed)));
    }

    // ---------------------------------------------------------------- enchantments

    /** {@code "#minecraft:tradeable"} (tag, recursive) | {@code "minecraft:mending"} | array of those. */
    public static List<EnchantRef> resolveEnchantments(JsonElement ref) {
        List<Identifier> ids = new ArrayList<>();
        collectEnchantmentIds(ref, ids, new LinkedHashSet<>());
        List<EnchantRef> out = new ArrayList<>(ids.size());
        for (Identifier id : ids) {
            out.add(loadEnchantment(id));
        }
        return List.copyOf(out);
    }

    /** Same resolution as {@link #resolveEnchantments} without loading level bounds. */
    public static List<Identifier> resolveEnchantmentIds(JsonElement ref) {
        List<Identifier> ids = new ArrayList<>();
        collectEnchantmentIds(ref, ids, new LinkedHashSet<>());
        return List.copyOf(ids);
    }

    private static void collectEnchantmentIds(JsonElement ref, List<Identifier> out, Set<Identifier> visitingTags) {
        if (ref.isJsonArray()) {
            for (JsonElement e : ref.getAsJsonArray()) {
                collectEnchantmentIds(e, out, visitingTags);
            }
            return;
        }
        String raw = ref.getAsString();
        if (raw.startsWith("#")) {
            Identifier tagId = Identifier.parse(raw.substring(1));
            if (!visitingTags.add(tagId)) {
                throw new UnsupportedTradeException("cyclic enchantment tag " + tagId);
            }
            for (JsonElement value : readTagValues(tagId, "enchantment")) {
                collectEnchantmentIds(value, out, visitingTags);
            }
            visitingTags.remove(tagId);
            return;
        }
        out.add(Identifier.parse(raw));
    }

    /**
     * Enchantment definitions are datapack JSONs in 26.x ({@code data/<ns>/enchantment/<path>.json});
     * only the RNG-relevant fields are read ({@code min_level}, default 1, and {@code max_level}).
     */
    public static EnchantRef loadEnchantment(Identifier id) {
        String path = "data/" + id.getNamespace() + "/enchantment/" + id.getPath() + ".json";
        JsonObject json = ClasspathTradeData.readJson(path)
                .orElseThrow(() -> new UnsupportedTradeException("unknown enchantment " + id));
        int maxLevel = json.has("max_level") ? json.get("max_level").getAsInt() : 1;
        int minLevel = json.has("min_level") ? json.get("min_level").getAsInt() : 1;
        return new EnchantRef(id, minLevel, maxLevel);
    }

    // ---------------------------------------------------------------- helpers

    private static void validateItem(Identifier id) {
        // DefaultedRegistry.getValue falls back to the default entry for unknown ids,
        // so round-trip through getKey to reject unknown items reliably.
        Item value = BuiltInRegistries.ITEM.getValue(id);
        if (!BuiltInRegistries.ITEM.getKey(value).equals(id)) {
            throw new UnsupportedTradeException("unknown item " + id);
        }
    }

    private static int maxStackSize(Identifier id) {
        validateItem(id);
        // Item components bind together with world tags, which a plain registry bootstrap
        // does not load. 64 is the stack size of every vanilla trade cost item and the
        // clamp the validated reference implementation uses; in-game the bound value is
        // read, so modded cost items still clamp exactly.
        Holder.Reference<Item> holder = BuiltInRegistries.ITEM.get(id).orElseThrow();
        return holder.areComponentsBound() ? holder.value().getDefaultMaxStackSize() : 64;
    }

    private static String requireString(JsonObject json, String field) {
        JsonElement e = json.get(field);
        if (e == null || !e.isJsonPrimitive()) {
            throw new UnsupportedTradeException("missing field " + field);
        }
        return e.getAsString();
    }

    private static int requireInt(JsonObject json, String field) {
        JsonElement e = json.get(field);
        if (e == null || !e.getAsJsonPrimitive().isNumber()) {
            throw new UnsupportedTradeException("field " + field + " is not a constant number");
        }
        return (int) Math.round(e.getAsDouble());
    }

    private static double optionalDouble(JsonObject json, String field, double fallback) {
        JsonElement e = json.get(field);
        if (e == null) {
            return fallback;
        }
        if (!e.getAsJsonPrimitive().isNumber()) {
            throw new UnsupportedTradeException("field " + field + " is randomized");
        }
        return e.getAsDouble();
    }

    private static boolean optionalBoolean(JsonObject json, String field, boolean fallback) {
        JsonElement e = json.get(field);
        return e == null ? fallback : e.getAsBoolean();
    }
}
