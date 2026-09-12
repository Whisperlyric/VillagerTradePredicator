package com.villagertradepredicator.mixin.client;

import com.villagertradepredicator.client.observe.VillagerTradeReader;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Netty-layer interception while an imperceptible read is in flight: capture the
 * merchant offers packet, and swallow the trade-screen packet (closing the server-side
 * container) so the UI never renders. When the reader is idle, everything passes
 * through untouched — manual trading is unaffected.
 */
@Mixin(Connection.class)
public class ConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void villagertradepredicator$onChannelRead0(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        VillagerTradeReader reader = VillagerTradeReader.get();
        if (reader.isIdle()) {
            return;
        }
        if (packet instanceof ClientboundOpenScreenPacket openScreen
                && openScreen.getType() == MenuType.MERCHANT) {
            int containerId = openScreen.getContainerId();
            ci.cancel();
            Minecraft.getInstance().execute(() ->
                    VillagerTradeReader.get().onOpenScreenSuppressed(containerId));
        } else if (packet instanceof ClientboundMerchantOffersPacket offersPacket) {
            // Not cancelled: vanilla's handler no-ops without a matching open container
            // (proven by LibrarianTradeFinder in production). Content is read on the main thread.
            MerchantOffersPayload payload = new MerchantOffersPayload(
                    offersPacket.getOffers(),
                    offersPacket.getVillagerLevel(),
                    offersPacket.getVillagerXp());
            Minecraft.getInstance().execute(() ->
                    VillagerTradeReader.get().onMerchantOffers(payload.offers(), payload.level(), payload.xp()));
        }
    }

    /** Immutable packet snapshot so parsing happens on the main thread. */
    private record MerchantOffersPayload(MerchantOffers offers, int level, int xp) {}
}
