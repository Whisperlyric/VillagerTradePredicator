package com.villagertradepredicator.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.villagertradepredicator.client.gui.SeedInputScreen;
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
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.Villager;

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
        repo = TradeDataRepository.loadFromClasspath();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // 存储位置随会话类型切换：单机跟随存档目录（每个世界独立，不互相污染），
            // 多人固定在 config（按 服务器(ip别名)→世界种子 归属）
            IntegratedServer integrated = client.getSingleplayerServer();
            if (integrated != null) {
                store = OffsetStore.load(integrated.getWorldPath(LevelResource.ROOT)
                        .resolve("vtp").resolve("offsets.json"));
                serverId = "singleplayer";
            } else {
                store = OffsetStore.load(FabricLoader.getInstance().getConfigDir()
                        .resolve("villagertradepredicator").resolve("offsets.json"));
                ServerData data = client.getCurrentServer();
                serverId = data != null ? store.serverForIp(data.ip) : "unknown";
            }
            // 种子按服务器（存档）持久化：重进自动恢复
            manualSeed = store.seed(serverId).orElse(null);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (store != null) {
                store.save();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("vtp")
                        .then(literal("read").executes(ctx -> readLookedAt() ? 1 : 0))
                        .then(literal("reset").executes(ctx -> {
                            session.reset();
                            say("§7已清空当前观测序列");
                            return 1;
                        }))
                        .then(literal("undo").executes(ctx -> {
                            boolean removed = session.removeLast();
                            say(removed
                                    ? "§7已撤销最近一组观测（剩余 " + session.groups() + " 组；缓存不受影响）"
                                    : "§c没有可撤销的观测");
                            return removed ? 1 : 0;
                        }))
                        .then(literal("seed")
                                .executes(ctx -> {
                                    SeedInputScreen.open(null,
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
        if (!"unknown".equals(serverId)) {
            store.setSeed(serverId, seed);
            store.save();
        }
        say("§a世界种子已设置：" + seed + "（" + worldKey() + "）");
    }

    private static String worldKey() {
        return manualSeed != null ? String.valueOf(manualSeed) : OffsetStore.DEFAULT_WORLD;
    }

    /** 种子解析：手动输入优先（含 /seed 截获），其次本服务器持久化的值。 */
    private static Long resolveSeed(Minecraft client) {
        if (manualSeed != null) {
            return manualSeed;
        }
        return store.seed(serverId).orElse(null);
    }

    // ---------------------------------------------------------------- /seed 截获

    private static long seedCaptureExpiresAt;

    /** 玩家自己运行了 op 命令 /seed —— 其回复为普通系统消息，合法可读，武装解析窗口。 */
    public static void onSeedCommandSent() {
        seedCaptureExpiresAt = System.currentTimeMillis() + 10_000;
    }

    public static void onSystemMessage(Component message) {
        if (System.currentTimeMillis() > seedCaptureExpiresAt) {
            return;
        }
        // Runs on the netty IO thread — hop to the render thread for parsing + state writes
        Minecraft.getInstance().execute(() -> {
            if (System.currentTimeMillis() > seedCaptureExpiresAt) {
                return;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(-?\\d+)]")
                    .matcher(message.getString());
            if (matcher.find()) {
                seedCaptureExpiresAt = 0;
                setManualSeed(Long.parseLong(matcher.group(1)));
                say("§7（已从 /seed 输出捕获）");
            }
        });
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
        var status = session.append(observed, profession);
        if (observed.tradeLocked()) {
            say("§e[!] 该村民已有交易经验（xp=" + observed.villagerXp() + "），职业已锁定：无法重掷，观测仅作记录");
        }
        if (status == ObservationSession.AppendStatus.DUPLICATE_APPENDED) {
            say("§e[!] 本次读取与上一组内容相同：若是未重掷的重复读取，执行 /vtp undo 撤销；"
                    + "若重掷后确实相同（低概率池常见），无需处理。");
        }
        say("§b" + (status == ObservationSession.AppendStatus.STARTED ? "开始新观测" : "第 "
                + session.groups() + " 组观测") + " §7(" + profession.getPath() + " L"
                + observed.villagerLevel() + "):");
        for (OfferFingerprint fp : observed.offers()) {
            String second = fp.costB().map(b -> " + " + b.count() + " " + b.item().getPath()).orElse("");
            String enchants = fp.enchantments().stream()
                    .map(e -> " [" + e.enchantment().getPath() + " " + e.level() + "]")
                    .collect(java.util.stream.Collectors.joining());
            say("  - " + fp.resultItem().getPath() + second + "  <- " + fp.costA() + " "
                    + fp.costAItem().getPath() + enchants);
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
            // 需要手动填写：自动弹出实验性设置风格的录入窗口（设计行为）
            say("§7已累计 " + session.groups() + " 组观测；定位需要世界种子——请手动输入：");
            SeedInputScreen.open(null, manualSeed == null ? "" : String.valueOf(manualSeed),
                    VtpCommands::setManualSeed);
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
            say("§a[OK] 唯一定位：offset = " + offset + "（已写入缓存 " + serverId + " / " + worldKey() + "）");
            RoundIterator it = new RoundIterator(set,
                    TradeSequences.create(seed, SequenceConfig.DEFAULT, set.randomSequence()), ctx);
            for (int i = 0; i <= offset; i++) {
                it.next();
            }
            var snap = it.snapshot();
            // store the NEXT round index (= rounds consumed): the saved stream state sits
            // after the matched round, so resuming must label the first next() as offset+1
            store.putOffset(serverId, worldKey(), seqId, snap.nextOffset(), snap.lo(), snap.hi());
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
        // Chat/font rendering must happen on the render thread — callers include netty
        // callbacks (system-chat capture), so always hop. Minecraft.execute is reentrant
        // on the render thread, so this is safe from both contexts.
        Minecraft.getInstance().execute(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("§7[VTP]§r " + message));
            }
        });
    }
}
