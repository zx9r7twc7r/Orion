package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ClientChunkCache.class)
public class ClientChunkCacheMixin {

    @Inject(
            method = "drop",
            at = @At("HEAD")
    )
    private void onDropChunk(
            ChunkPos pos,
            CallbackInfo ci
    ) {
        if (pos == null) {
            return;
        }


        try {
            NativeVulkan.removeLightChunk(
                    pos.x(),
                    pos.z()
            );
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}