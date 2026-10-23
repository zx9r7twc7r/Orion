package com.example.client.mixin;

import com.example.client.RRFovState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraFovMixin {

    @Unique
    private int rrFovProbeCounter = 0;

    @Unique
    private static final boolean RTX_DEBUG =
            Boolean.getBoolean("rtx.debug");

    @Inject(
            method = "calculateFov(F)F",
            at = @At("RETURN")
    )
    private void rrCaptureCalculatedFov(
            float partialTicks,
            CallbackInfoReturnable<Float> cir
    ) {
        float calculatedFov =
                cir.getReturnValue();

        boolean valid =
                Float.isFinite(calculatedFov)
                        && calculatedFov > 0.0f;

        if (valid) {
            RRFovState.setCalculatedFovDegrees(
                    calculatedFov
            );
        }

        if (RTX_DEBUG
                && (rrFovProbeCounter % 60) == 0) {
            System.out.println(
                    "[RR-3.4 FOV CAPTURE]"
                            + " raw=" + calculatedFov
                            + " valid=" + valid
                            + " stored="
                            + RRFovState.getCalculatedFovDegrees()
            );
        }

        rrFovProbeCounter++;
    }
}