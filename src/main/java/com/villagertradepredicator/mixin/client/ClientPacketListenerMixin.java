package com.villagertradepredicator.mixin.client;

import com.villagertradepredicator.client.VtpCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legitimate seed capture: when the player runs the (operator) {@code /seed} command
 * themselves, the response is a normal system chat message — observe it and parse the
 * seed. Never cancels or alters anything; the command still reaches the server and the
 * message still displays.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "sendCommand(Ljava/lang/String;)V", at = @At("HEAD"))
    private void villagertradepredicator$onSendCommand(String command, CallbackInfo ci) {
        if (command.equals("seed")) {
            VtpCommands.onSeedCommandSent();
        }
    }

    @Inject(method = "handleSystemChat(Lnet/minecraft/network/protocol/game/ClientboundSystemChatPacket;)V",
            at = @At("HEAD"))
    private void villagertradepredicator$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        VtpCommands.onSystemMessage(packet.content());
    }
}
