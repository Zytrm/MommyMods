package com.zytrm.mommymods.mixin;

import com.zytrm.mommymods.MommyMods;
import com.zytrm.mommymods.feature.LouderCatch;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = 1500)
public abstract class ClientPacketListenerMixin {
    private static final String AFTER_THREAD_CHECK =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(" +
        "Lnet/minecraft/network/protocol/Packet;" +
        "Lnet/minecraft/network/PacketListener;" +
        "Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void mommymods$openMenuCommand(String command, CallbackInfo ci) {
        String normalized = command.trim().replaceAll("\\s+", " ").toLowerCase();
        if (normalized.equals("mm") || normalized.equals("mommymods") || normalized.equals("mommy mods")) {
            MommyMods.requestMenuOpen();
            ci.cancel();
        }
    }

    @Inject(
        method = "handleSoundEvent",
        at = @At(value = "INVOKE", target = AFTER_THREAD_CHECK, shift = At.Shift.AFTER),
        cancellable = true
    )
    private void mommymods$replaceCatchPacket(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (LouderCatch.onSoundPacket(
            packet.getSound().value().location(),
            packet.getVolume(),
            packet.getX(),
            packet.getY(),
            packet.getZ()
        )) {
            ci.cancel();
        }
    }

    @Inject(
        method = "handleSoundEntityEvent",
        at = @At(value = "INVOKE", target = AFTER_THREAD_CHECK, shift = At.Shift.AFTER),
        cancellable = true
    )
    private void mommymods$replaceEntityCatchPacket(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        if (LouderCatch.onSoundEntityPacket(packet.getSound().value().location(), packet.getVolume(), packet.getId())) {
            ci.cancel();
        }
    }

    @Inject(
        method = "handleParticleEvent",
        at = @At(value = "INVOKE", target = AFTER_THREAD_CHECK, shift = At.Shift.AFTER)
    )
    private void mommymods$inspectFishingParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        LouderCatch.onParticlePacket(packet);
    }
}
