package com.example.mc308593fix.mixin;

import com.example.mc308593fix.Mc308593Mod;
import com.example.mc308593fix.Mc308593Workaround;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public class SpriteContentsAnimatedTextureMixin {
    @Inject(method = "createAnimationState", at = @At("HEAD"), cancellable = true)
    private void mc308593$createAnimationState(GpuBufferSlice uboSlice, int spriteUboSize, CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
        if (Boolean.getBoolean("mc308593fix.disable")) {
            return;
        }

        try {
            cir.setReturnValue(Mc308593Workaround.createAnimationState((Object) this, uboSlice, spriteUboSize));
        } catch (Throwable t) {
            Mc308593Mod.LOGGER.warn("MC-308593 workaround failed, falling back to vanilla behavior", t);
        }
    }
}
