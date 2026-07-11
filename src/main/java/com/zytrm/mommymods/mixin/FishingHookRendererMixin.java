package com.zytrm.mommymods.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zytrm.mommymods.config.ModConfig;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FishingHookRenderer.class)
public abstract class FishingHookRendererMixin {
    @Redirect(
        method = "submit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V",
            ordinal = 1
        )
    )
    private void mommymods$renderFishingLine(
        SubmitNodeCollector collector,
        PoseStack poseStack,
        RenderType renderType,
        SubmitNodeCollector.CustomGeometryRenderer renderer
    ) {
        if (!ModConfig.INSTANCE.getValues().getHideFishingLine()) {
            collector.submitCustomGeometry(poseStack, renderType, renderer);
        }
    }
}
