package com.villagertradepredicator.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.villagertradepredicator.client.gui.SeedConfirmScreen;
import com.villagertradepredicator.client.observe.ObservationSession;
import com.villagertradepredicator.client.observe.ObservedOffers;
import com.villagertradepredicator.client.observe.VillagerTradeReader;
import com.villagertradepredicator.core.data.LoadedTradeSet;
import com.villagertradepredicator.core.data.TradeDataRepository;
import com.villagertradepredicator.core.locate.RoundIterator;
import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.TradeSetDef;
import com.villagertradepredicator.core.persist.OffsetStore;
import com.villagertradepredicator.core.rng.SequenceConfig;
import com.villagertradepredicator.core.rng.TradeSequences;
import com.villagertradepredicator.core.sim.SimContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Minimal in-game test loop for the inference pipeline: read the villager under the
 * crosshair imperceptibly, print the observation group, auto-run offset localization on
 * the accumulated consecutive groups, and persist the result per server-ip/world seed.
 * Deliberately minimal — will be replaced by the real UI later.
 */
public final class VtpCommands {
    private static final int SCAN_RANGE = 1000;
    private static final Identifier PLAINS = Identifier.withDefaultNamespace("plains");

    private static OffsetStore store;
    private static TradeDataRepository repo;
    private static String serverId = "unknown";
    private static Long manualSeed;
    private static final ObservationSession session = new ObservationSession();

    private VtpCommands() {}

    public static void init() {
        store = OffsetStore.load(FabricLoader.getInstance().getConfigDir()
                .resolve("villagertradepredicator").resolve("offsets.json"));
        repo = TradeDataRepository.loadFromClasspath();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData data = client.getCurrentServer();
            serverId = data != null ? store.serverForIp(data.ip) : "singleplayer";
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> store.save());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("vtp")
                        .then(literal("read").executes(ctx -> readLookedAt() ? 1 : 0))
                        .then(literal("reset").executes(ctx -> {
                            session.reset();
                            say("§7已清空当前观测序列");
                            return 1;
                        }))
                        .then(literal("seed")
                                .executes(ctx -> {
                                    SeedConfirmScreen.open(null,
                                            manualSeed == null ? "" : String.valueOf(manualSeed),
                                            VtpCommands::setManualSeed);
                                    return 1;
                                })
                                .then(argument("seed", longArg(Long.MIN_VALUE, Long.MAX_VALUE))
                                        .executes(ctx -> {
                                            setManualSeed(ctx.getArgument("seed", Long.class));
                                            return 1;
                                        })))));
    }

    private static void setManualSeed(long seed) {
        manualSeed = seed;
        say("§a世界种子已设置：" + seed + "（" + worldKey() + "）");
    }

    private static String worldKey() {
        return manualSeed != null ? String.valueOf(manualSeed) : OffsetStore.DEFAULT_WORLD;
    }

    /** Seed resolution per design: manual entry only in multiplayer; null = unlocatable. */
    private static Long resolveSeed(Minecraft client) {
        if (manualSeed != null) {
            return manualSeed;
        }
        return null;
    }

    private static boolean readLookedAt() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.crosshairPickEntity instanceof Villager villager)) {
            say("§c请将准星对准村民");
            return false;
        }
        var data = villager.getVillagerData();
        Identifier profession = data.profession().unwrapKey()
                .map(ResourceKey::identifier)
                .orElse(null);
        if (profession == null) {
            say("§c该村民职业未知");
            return false;
        }
        boolean queued = VillagerTradeReader.get().requestRead(villager, observed ->
                onObserved(villager.getUUID(), profession, data.level(), observed));
        if (!queued) {
            say("§c上一次读取仍在进行中…");
        }
        return queued;
    }

    private static void onObserved(UUID villagerId, Identifier profession, int level, ObservedOffers observed) {
        if (observed.tradeLocked()) {
            say("§e⚠ 该村民已有交易经验（xp=" + observed.villagerXp() + "），职业已锁定：无法重掷，观测仅作记录");
        }
        session.append(observed, profession);
        say("§b第 " + session.groups() + " 组观测 §7(" + profession.getPath() + " L"
                + observed.villagerLevel() + "):");
        for (OfferFingerprint fp : observed.offers()) {
            String second = fp.costB().map(b -> " + " + b.count() + " " + b.item().getPath()).orElse("");
            String enchants = fp.enchantments().stream()
                    .map(e -> " [" + e.enchantment().getPath() + " " + e.level() + "]")
                    .collect(java.util.stream.Collectors.joining());
            say("  • " + fp.resultItem().getPath() + second + " ← " + fp.costA() + " 绿宝" + enchants);
        }
        inferAndReport(profession, level);
    }

    private static void inferAndReport(Identifier profession, int level) {
        Optional<LoadedTradeSet> loaded = repo.get(profession, level);
        if (loaded.isEmpty()) {
            say("§c该职业/等级不在游戏数据中");
            return;
        }
        if (!loaded.get().isCompatible()) {
            say("§c该职业/等级不可预测：" + String.join("；", loaded.get().issues()));
            return;
        }
        TradeSetDef set = loaded.get().def().orElseThrow();
        Long seed = resolveSeed(Minecraft.getInstance());
        if (seed == null) {
            say("§7已累计 " + session.groups() + " 组观测；未提供世界种子，无法定位——/vtp seed <种子>");
            return;
        }
        SimContext ctx = set.trades().stream().anyMatch(t -> t.predicate().isPresent())
                ? SimContext.withVariant(PLAINS)
                : SimContext.withoutVariant();
        List<Integer> candidates = session.infer(set, SequenceConfig.DEFAULT, seed, ctx, SCAN_RANGE);
        String seqId = TradeSequences.sequenceId(profession, level).toString();
        if (candidates.isEmpty()) {
            say("§c0 候选：观测与模拟不符（种子错误 / 中途有其他村民消耗了序列 / 观测不连续）——/vtp reset 重来");
            return;
        }
        if (candidates.size() == 1) {
            int offset = candidates.get(0);
            say("§a✔ 唯一定位：offset = " + offset + "（已写入缓存 " + serverId + " / " + worldKey() + "）");
            RoundIterator it = new RoundIterator(set,
                    TradeSequences.create(seed, SequenceConfig.DEFAULT, set.randomSequence()), ctx);
            for (int i = 0; i <= offset; i++) {
                it.next();
            }
            var snap = it.snapshot();
            store.putOffset(serverId, worldKey(), seqId, offset, snap.lo(), snap.hi());
            store.save();
        } else {
            say("§e候选 " + candidates.size() + " 个（前几项 " + head(candidates)
                    + "）——继续对同一村民读取下一组以收敛");
        }
    }

    private static String head(List<Integer> values) {
        return values.subList(0, Math.min(6, values.size())).toString();
    }

    private static void say(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§7[VTP]§r " + message));
        }
    }
}
