package com.villagertradepredicator.core.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagertradepredicator.core.model.CostDef;
import com.villagertradepredicator.core.model.EnchantRandomlyDef;
import com.villagertradepredicator.core.model.EnchantRef;
import com.villagertradepredicator.core.model.EnchantmentLevel;
import com.villagertradepredicator.core.model.GiveDef;
import com.villagertradepredicator.core.model.NumberProvider;
import com.villagertradepredicator.core.model.TradeDef;
import com.villagertradepredicator.core.model.TradePredicate;
import com.villagertradepredicator.core.model.TradeSetDef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Parses the vanilla trade data files (field names verified against the 26.x jar):
 * {@code trade_set/<prof>/level_<n>.json}, {@code tags/villager_trade/...} and
 * {@code villager_trade/...}, plus enchantment tags and definitions for option pools
 * and price doubling. All reads go through a {@link DataAccess}, so built-in datapack
 * overlays (trade_rebalance) layer over the base data with vanilla semantics.
 *
 * <p>Anything the simulator cannot replay exactly raises {@link UnsupportedTradeException};
 * callers convert that into a compatibility issue that disables the containing set.</p>
 */
public final class TradeDataParser {
    private final DataAccess access;

    public TradeDataParser(DataAccess access) {
        this.access = access;
    }

    /** Content the predictor refuses to simulate (whole set gets disabled). */
    public static final class UnsupportedTradeException extends RuntimeException {
        public UnsupportedTradeException(String reason) {
            super(reason);
        }
    }

    // ---------------------------------------------------------------- trade_set

    public TradeSetDef parseTradeSet(Identifier setId, JsonObject json) {
        Identifier seq = Identifier.parse(requireString(json, "random_sequence"));
        NumberProvider amount = parseNumber(json.get("amount"), "amount");
        boolean allowDuplicates = optionalBoolean(json, "allow_duplicates", false);
        List<TradeDef> trades = resolveTradesReference(json.get("trades"));
        return new TradeSetDef(setId, seq, amount, allowDuplicates, List.copyOf(trades));
    }

    /**
     * {@code trades}: {@code "#minecraft:librarian/level_1"} (tag in the villager_trade
     * registry), a single trade id, or an array of those. Order defines the pool order
     * and therefore the {@code nextInt(bound)} mapping — it is preserved verbatim.
     */
    private List<TradeDef> resolveTradesReference(JsonElement ref) {
        List<Identifier> ids = new ArrayList<>();
        collectReferencedIds(ref, "villager_trade", ids, new LinkedHashSet<>());
        List<TradeDef> trades = new ArrayList<>(ids.size());
        for (Identifier id : ids) {
            JsonObject json = access.json(id.getNamespace() + "/villager_trade/" + id.getPath() + ".json")
                    .orElseThrow(() -> new UnsupportedTradeException("missing trade definition " + id));
            trades.add(parseTrade(id, json));
        }
        return trades;
    }

    private void collectReferencedIds(JsonElement ref, String tagRegistry,
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
            for (JsonElement value : access.tagValues(tagRegistry, tagId)
                    .orElseThrow(() -> new UnsupportedTradeException("missing tag " + tagRegistry + "/" + tagId))) {
                collectReferencedIds(value, tagRegistry, out, visitingTags);
            }
            visitingTags.remove(tagId);
            return;
        }
        out.add(Identifier.parse(raw));
    }

    // ---------------------------------------------------------------- villager_trade

    private TradeDef parseTrade(Identifier id, JsonObject json) {
        CostDef wants = parseCost(json, "wants");
        Optional<CostDef> additionalWants = json.has("additional_wants")
                ? Optional.of(parseCost(json, "additional_wants"))
                : Optional.empty();
        GiveDef gives = parseGive(json);

        NumberProvider maxUses = parseNumberOr(json.get("max_uses"), 4f, "max_uses");
        NumberProvider reputationDiscount = parseNumberOr(json.get("reputation_discount"), 0f, "reputation_discount");
        NumberProvider xp = parseNumberOr(json.get("xp"), 1f, "xp");

        Optional<TradePredicate> predicate = parsePredicate(json);

        Optional<EnchantRandomlyDef> enchantRandomly = Optional.empty();
        List<EnchantmentLevel> presetEnchantments = List.of();
        boolean requireEnchanted = false;
        if (json.has("given_item_modifiers")) {
            for (JsonElement e : json.getAsJsonArray("given_item_modifiers")) {
                JsonObject modifier = e.getAsJsonObject();
                String function = requireString(modifier, "function");
                switch (function) {
                    case "minecraft:enchant_randomly" -> enchantRandomly = Optional.of(parseEnchantRandomly(modifier));
                    case "minecraft:set_enchantments" -> presetEnchantments = parseSetEnchantments(modifier);
                    case "minecraft:filtered" -> requireEnchanted = parseFiltered(modifier);
                    default -> throw new UnsupportedTradeException("item modifier " + function);
                }
            }
        }

        Set<Identifier> doublePrice = json.has("double_trade_price_enchantments")
                ? Set.copyOf(resolveEnchantmentIds(json.get("double_trade_price_enchantments")))
                : Set.of();

        return new TradeDef(id, wants, additionalWants, gives, maxUses, reputationDiscount, xp,
                predicate, enchantRandomly, presetEnchantments, requireEnchanted, doublePrice);
    }

    private CostDef parseCost(JsonObject trade, String field) {
        JsonObject cost = trade.getAsJsonObject(field);
        if (cost.has("components")) {
            throw new UnsupportedTradeException(field + " uses component matching");
        }
        Identifier item = Identifier.parse(requireString(cost, "id"));
        NumberProvider count = cost.has("count")
                ? parseNumber(cost.get("count"), field + " count")
                : NumberProvider.constant(1f);
        return new CostDef(item, count, maxStackSize(item));
    }

    private GiveDef parseGive(JsonObject trade) {
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

    private EnchantRandomlyDef parseEnchantRandomly(JsonObject modifier) {
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

    /** {@code set_enchantments} with constant levels only — deterministic, consumes no RNG. */
    private List<EnchantmentLevel> parseSetEnchantments(JsonObject modifier) {
        JsonElement enchantments = modifier.get("enchantments");
        if (enchantments == null || !enchantments.isJsonObject()) {
            throw new UnsupportedTradeException("set_enchantments without enchantments object");
        }
        Map<Identifier, Integer> presets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : enchantments.getAsJsonObject().entrySet()) {
            JsonElement level = e.getValue();
            if (!level.getAsJsonPrimitive().isNumber()) {
                throw new UnsupportedTradeException("set_enchantments level is randomized");
            }
            float raw = level.getAsFloat();
            if (raw != Math.round(raw) || raw < 1f) {
                throw new UnsupportedTradeException("set_enchantments level " + raw + " is not a positive integer");
            }
            presets.put(Identifier.parse(e.getKey()), Math.round(raw));
        }
        List<EnchantmentLevel> out = new ArrayList<>();
        presets.forEach((id, level) -> out.add(new EnchantmentLevel(id, level)));
        return List.copyOf(out);
    }

    /**
     * The known trailing {@code filtered}+{@code discard} safety step: keep the offer only
     * if the result carries an enchantment (stored for books, normal for equipment).
     * Consumes no RNG. Any other filtered shape is rejected.
     */
    private boolean parseFiltered(JsonObject modifier) {
        JsonElement onFail = modifier.get("on_fail");
        if (onFail == null || !"minecraft:discard".equals(onFail.getAsJsonObject().get("function").getAsString())) {
            throw new UnsupportedTradeException("filtered without discard on_fail");
        }
        JsonObject filter = modifier.getAsJsonObject("item_filter");
        JsonObject predicates = filter.getAsJsonObject("predicates");
        if (predicates == null || !(predicates.has("minecraft:stored_enchantments") || predicates.has("minecraft:enchantments"))) {
            throw new UnsupportedTradeException("filtered on unsupported predicate "
                    + (predicates == null ? "?" : predicates.keySet().toString()));
        }
        return true;
    }

    private Optional<TradePredicate> parsePredicate(JsonObject trade) {
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

    // ---------------------------------------------------------------- number providers

    /**
     * Supported provider shapes: plain number / {@code constant} / {@code uniform} /
     * {@code sum} (recursive). Uniform consumes one {@code nextFloat} draw exactly like
     * the game's {@code UniformGenerator}; anything else is rejected.
     */
    private NumberProvider parseNumber(JsonElement e, String what) {
        if (e == null) {
            throw new UnsupportedTradeException("missing number " + what);
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return NumberProvider.constant(e.getAsFloat());
        }
        JsonObject provider = e.getAsJsonObject();
        String type = provider.get("type").getAsString();
        switch (type) {
            case "minecraft:constant":
                return NumberProvider.constant(provider.get("value").getAsFloat());
            case "minecraft:uniform":
                return NumberProvider.uniform(
                        parseNumber(provider.get("min"), what + " min"),
                        parseNumber(provider.get("max"), what + " max"));
            case "minecraft:sum": {
                List<NumberProvider> summands = new ArrayList<>();
                for (JsonElement summand : provider.getAsJsonArray("summands")) {
                    summands.add(parseNumber(summand, what + " summand"));
                }
                return NumberProvider.sum(List.copyOf(summands));
            }
            default:
                throw new UnsupportedTradeException(what + " uses number provider " + type);
        }
    }

    private NumberProvider parseNumberOr(JsonElement e, float fallback, String what) {
        return e == null ? NumberProvider.constant(fallback) : parseNumber(e, what);
    }

    // ---------------------------------------------------------------- enchantments

    /** {@code "#minecraft:tradeable"} (tag, recursive) | {@code "minecraft:mending"} | array of those. */
    public List<EnchantRef> resolveEnchantments(JsonElement ref) {
        List<Identifier> ids = new ArrayList<>();
        collectEnchantmentIds(ref, ids, new LinkedHashSet<>());
        List<EnchantRef> out = new ArrayList<>(ids.size());
        for (Identifier id : ids) {
            out.add(loadEnchantment(id));
        }
        return List.copyOf(out);
    }

    /** Same resolution as {@link #resolveEnchantments} without loading level bounds. */
    public List<Identifier> resolveEnchantmentIds(JsonElement ref) {
        List<Identifier> ids = new ArrayList<>();
        collectEnchantmentIds(ref, ids, new LinkedHashSet<>());
        return List.copyOf(ids);
    }

    private void collectEnchantmentIds(JsonElement ref, List<Identifier> out, Set<Identifier> visitingTags) {
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
            for (JsonElement value : access.tagValues("enchantment", tagId)
                    .orElseThrow(() -> new UnsupportedTradeException("missing enchantment tag " + tagId))) {
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
    public EnchantRef loadEnchantment(Identifier id) {
        JsonObject json = access.json(id.getNamespace() + "/enchantment/" + id.getPath() + ".json")
                .orElseThrow(() -> new UnsupportedTradeException("unknown enchantment " + id));
        int maxLevel = json.has("max_level") ? json.get("max_level").getAsInt() : 1;
        int minLevel = json.has("min_level") ? json.get("min_level").getAsInt() : 1;
        return new EnchantRef(id, minLevel, maxLevel);
    }

    // ---------------------------------------------------------------- helpers

    private void validateItem(Identifier id) {
        // DefaultedRegistry.getValue falls back to the default entry for unknown ids,
        // so round-trip through getKey to reject unknown items reliably.
        Item value = BuiltInRegistries.ITEM.getValue(id);
        if (!BuiltInRegistries.ITEM.getKey(value).equals(id)) {
            throw new UnsupportedTradeException("unknown item " + id);
        }
    }

    private int maxStackSize(Identifier id) {
        validateItem(id);
        // Item components bind together with world tags, which a plain registry bootstrap
        // does not load. 64 is the stack size of every vanilla trade cost item and the
        // clamp the validated reference implementation uses; in-game the bound value is
        // read, so modded cost items still clamp exactly.
        var holder = BuiltInRegistries.ITEM.get(id).orElseThrow();
        return holder.areComponentsBound() ? holder.value().getDefaultMaxStackSize() : 64;
    }

    private String requireString(JsonObject json, String field) {
        JsonElement e = json.get(field);
        if (e == null || !e.isJsonPrimitive()) {
            throw new UnsupportedTradeException("missing field " + field);
        }
        return e.getAsString();
    }

    private boolean optionalBoolean(JsonObject json, String field, boolean fallback) {
        JsonElement e = json.get(field);
        return e == null ? fallback : e.getAsBoolean();
    }
}
