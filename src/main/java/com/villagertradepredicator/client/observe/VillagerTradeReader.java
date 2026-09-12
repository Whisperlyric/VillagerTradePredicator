package com.villagertradepredicator.client.observe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.villagertradepredicator.core.model.EnchantmentLevel;
import com.villagertradepredicator.core.model.OfferFingerprint;
import com.villagertradepredicator.core.model.PredictedOffer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Reads one villager's trade table imperceptibly (LibrarianTradeFinder's proven route,
 * cleaned up): sends a normal right-click interaction, intercepts the resulting
 * {@code ClientboundMerchantOffersPacket} at the netty layer, cancels the
 * {@code ClientboundOpenScreenPacket} so the trade UI never renders, and closes the
 * server-side container to leave no state behind. Reading consumes no sequence rounds —
 * only profession changes and level-ups advance the offset.
 *
 * <p>Threading: the netty callback arrives off-thread; packet contents are hopped to the
 * main thread before any ItemStack parsing. While a read is pending, the merchant
 * open-screen suppression is active for at most {@value TIMEOUT_TICKS} ticks; manual
 * trading during that window is therefore briefly blocked by design.</p>
 */
public final class VillagerTradeReader {
    private static final int TIMEOUT_TICKS = 100;

    private static final VillagerTradeReader INSTANCE = new VillagerTradeReader();

    public static VillagerTradeReader get() {
        return INSTANCE;
    }

    private record PendingRead(UUID villager, Consumer<ObservedOffers> callback, long deadline) {}

    private long clientTick;
    private PendingRead pending;

    private VillagerTradeReader() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public boolean isIdle() {
        return pending == null;
    }

    /**
     * Reads the villager's current offers without showing any UI. The callback runs on
     * the main thread; returns false when another read is still in flight.
     */
    public boolean requestRead(Villager villager, Consumer<ObservedOffers> callback) {
        Minecraft client = Minecraft.getInstance();
        if (pending != null || client.player == null || client.gameMode == null) {
            return false;
        }
        UUID villagerId = villager.getUUID();
        pending = new PendingRead(villagerId, callback, clientTick + TIMEOUT_TICKS);
        client.gameMode.interact(client.player, villager,
                new EntityHitResult(villager), InteractionHand.MAIN_HAND);
        return true;
    }

    private void tick(Minecraft client) {
        this.clientTick++;
        if (pending != null && clientTick >= pending.deadline()) {
            pending = null; // server never answered (lag, out of range, anti-cheat)
        }
    }

    // ---------------------------------------------------------------- netty callbacks

    public boolean isSuppressingOpenScreen() {
        return pending != null;
    }

    /** The merchant open-screen packet was cancelled at HEAD; close the server-side container. */
    public void onOpenScreenSuppressed(int containerId) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener != null) {
            listener.send(new ServerboundContainerClosePacket(containerId));
        }
    }

    /**
     * Merchant offers arrived while a read was pending — attributed to the pending
     * villager (the only interaction we issued). Unsolicited packets outside a pending
     * read flow to vanilla untouched.
     */
    public void onMerchantOffers(MerchantOffers offers, int villagerLevel, int villagerXp) {
        PendingRead read = pending;
        if (read == null) {
            return;
        }
        pending = null;
        List<OfferFingerprint> fingerprints = new ArrayList<>();
        for (MerchantOffer offer : offers) {
            fingerprints.add(fingerprint(offer));
        }
        read.callback().accept(new ObservedOffers(read.villager(), villagerLevel, villagerXp,
                List.copyOf(fingerprints)));
    }

    // ---------------------------------------------------------------- conversion

    private static OfferFingerprint fingerprint(MerchantOffer offer) {
        ItemStack costA = offer.getBaseCostA();
        ItemStack costB = offer.getCostB();
        ItemStack result = offer.getResult();
        Optional<PredictedOffer.SecondCost> secondCost = costB.isEmpty()
                ? Optional.empty()
                : Optional.of(new PredictedOffer.SecondCost(item(costB), costB.getCount()));
        return new OfferFingerprint(item(result), costA.getCount(), secondCost,
                enchantmentsOf(result));
    }

    /** Covers both book stored enchantments and normal equipment enchantments. */
    private static List<EnchantmentLevel> enchantmentsOf(ItemStack stack) {
        List<EnchantmentLevel> out = new ArrayList<>();
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            Identifier id = entry.getKey().unwrapKey()
                    .map(key -> key.identifier())
                    .orElse(null);
            if (id != null) {
                out.add(new EnchantmentLevel(id, entry.getIntValue()));
            }
        }
        out.sort(Comparator.comparing(e -> e.enchantment().toString()));
        return List.copyOf(out);
    }

    private static Identifier item(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }
}
