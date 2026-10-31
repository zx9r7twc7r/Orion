package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;
import com.example.client.RRFovState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import com.example.client.EntityRtCapture;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class VulkanCameraMixin {

    @Shadow
    private Minecraft minecraft;

    @Shadow
    private GameRenderState gameRenderState;

    @Shadow
    private Camera mainCamera;

    private final float[] viewInvArray = new float[16];
    private final float[] projInvArray = new float[16];

    private final Matrix4f tempViewInv = new Matrix4f();
    private final Matrix4f tempProjInv = new Matrix4f();
    private final Matrix4f tempView = new Matrix4f();
    private final Matrix4f rotInv = new Matrix4f();
    private final float[] handRotationArray = new float[16];

    private int frameCount = 0;

    private static final int WATER_ENTITY_MAX_BOATS = 8;
    private static final double WATER_ENTITY_RANGE_SQR = 24.0 * 24.0;
    private final java.util.Map<Integer, Boolean> waterEntityWasInWater =
            new java.util.HashMap<>();
    private Level waterEntityLastLevel = null;
    private final float[] waterBoatData = new float[WATER_ENTITY_MAX_BOATS * 5];

    private static final boolean RTX_DEBUG =
            Boolean.getBoolean("rtx.debug");

    private Boolean lastLoadFinished = null;
    private Boolean lastAdvanceGameTime = null;
    private Boolean lastLevelPresent = null;
    private Boolean lastReady = null;
    private BlockPos cachedWaterSurfaceProbeBlock = null;
    private float cachedWaterSurfaceY = 0.0f;

    /**
     * Returns the actual top surface of the connected water column containing
     * the real player.  Camera position is intentionally never consulted: in
     * third person the simulation must remain attached to the entity.
     */
    private float findPlayerWaterSurfaceY(Vec3 playerPosition) {
        if (this.minecraft == null || this.minecraft.level == null ||
                this.minecraft.player == null ||
                !this.minecraft.player.isInWater()) {
            cachedWaterSurfaceProbeBlock = null;
            return (float) playerPosition.y;
        }

        BlockPos playerBlock = BlockPos.containing(playerPosition);
        if (playerBlock.equals(cachedWaterSurfaceProbeBlock)) {
            return cachedWaterSurfaceY;
        }

        BlockPos cursor = playerBlock;
        FluidState state = this.minecraft.level.getFluidState(cursor);

        if (!state.is(FluidTags.WATER)) {
            cursor = cursor.below();
            state = this.minecraft.level.getFluidState(cursor);
        }

        if (!state.is(FluidTags.WATER)) {
            cachedWaterSurfaceProbeBlock = playerBlock;
            cachedWaterSurfaceY = (float) playerPosition.y;
            return (float) playerPosition.y;
        }

        BlockPos topWaterBlock = cursor;
        FluidState topWaterState = state;

        for (int step = 0; step < 128; ++step) {
            BlockPos above = topWaterBlock.above();
            FluidState aboveState = this.minecraft.level.getFluidState(above);
            if (!aboveState.is(FluidTags.WATER)) {
                break;
            }
            topWaterBlock = above;
            topWaterState = aboveState;
        }

        cachedWaterSurfaceProbeBlock = playerBlock;
        cachedWaterSurfaceY = topWaterBlock.getY() +
                topWaterState.getHeight(this.minecraft.level, topWaterBlock);
        return cachedWaterSurfaceY;
    }

    /**
     * The wave field follows the player, while optical medium state follows
     * the actual render camera.  They differ in third-person views.
     */
    private boolean isRenderCameraInWater(Vec3 cameraPosition) {
        if (this.minecraft == null || this.minecraft.level == null) {
            return false;
        }

        BlockPos cameraBlock = BlockPos.containing(cameraPosition);
        FluidState state = this.minecraft.level.getFluidState(cameraBlock);
        if (!state.is(FluidTags.WATER)) {
            return false;
        }

        float fluidSurfaceY = cameraBlock.getY() +
                state.getHeight(this.minecraft.level, cameraBlock);
        return cameraPosition.y < fluidSurfaceY;
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void onExtractState(
            DeltaTracker deltaTracker,
            boolean advanceGameTime,
            CallbackInfo ci
    ) {
        if (this.minecraft == null) {
            if (RTX_DEBUG) {
                System.out.println(
                        "[WORLD STATE] minecraft=null"
                );
            }
            return;
        }


        boolean loadFinished =
                this.minecraft.isGameLoadFinished();

        boolean levelPresent =
                this.minecraft.level != null;

        boolean ready =
                loadFinished &&
                        advanceGameTime &&
                        levelPresent;


        boolean changed =
                lastLoadFinished == null ||
                        lastAdvanceGameTime == null ||
                        lastLevelPresent == null ||
                        lastReady == null ||
                        lastLoadFinished != loadFinished ||
                        lastAdvanceGameTime != advanceGameTime ||
                        lastLevelPresent != levelPresent ||
                        lastReady != ready;


        if (changed && RTX_DEBUG) {
            System.out.println(
                    "[WORLD STATE]"
                            + " loadFinished=" + loadFinished
                            + " advanceGameTime=" + advanceGameTime
                            + " levelPresent=" + levelPresent
                            + " ready=" + ready
            );
        }


        lastLoadFinished = loadFinished;
        lastAdvanceGameTime = advanceGameTime;
        lastLevelPresent = levelPresent;
        lastReady = ready;


        if (!ready) {
            RRFovState.invalidateCalculatedFov();
        }

        NativeVulkan.setInWorld(ready);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevel(
            CallbackInfo ci
    ) {
        if (this.gameRenderState == null ||
                this.gameRenderState.levelRenderState == null ||
                this.gameRenderState.levelRenderState.cameraRenderState == null ||
                this.mainCamera == null) {
            return;
        }

        CameraRenderState cameraState =
                this.gameRenderState.levelRenderState.cameraRenderState;

        final com.example.client.bench.CameraPathDriver.Pose replayPose =
                com.example.client.bench.CameraPathDriver.frame(
                        cameraState.pos,
                        this.mainCamera.xRot(),
                        this.mainCamera.yRot());

        Vec3 pos = replayPose == null
                ? cameraState.pos
                : new Vec3(replayPose.x(), replayPose.y(), replayPose.z());

        if ((frameCount % 60) == 0 && RTX_DEBUG) {
            System.out.println(
                    "[PRIMARY ORIGIN]"
                            + " x=" + pos.x
                            + " y=" + pos.y
                            + " z=" + pos.z
            );
        }

        Matrix4fc viewRotationMatrix =
                replayPose == null
                        ? cameraState.viewRotationMatrix
                        : replayPose.viewRotationMatrix();

        Matrix4fc projectionMatrix =
                cameraState.projectionMatrix;



        float cameraNear =
                Camera.PROJECTION_Z_NEAR;


        float cameraFar =
                cameraState.depthFar;


        float settingFovDegrees =
                (float) this.minecraft.options
                        .fov()
                        .get();

        boolean usingCapturedFov =
                RRFovState.hasValidCalculatedFov();

        float effectiveFovDegrees;

        if (usingCapturedFov) {
            effectiveFovDegrees =
                    RRFovState.getCalculatedFovDegrees();
        } else {
            effectiveFovDegrees =
                    settingFovDegrees;
        }

        float cameraFov =
                (float) Math.toRadians(
                        effectiveFovDegrees
                );

        if ((frameCount % 60) == 0 && RTX_DEBUG) {
            System.out.println(
                    "[RR-3.4 FOV FINAL]"
                            + " source="
                            + (usingCapturedFov
                            ? "calculated"
                            : "setting")
                            + " degrees="
                            + effectiveFovDegrees
                            + " radians="
                            + cameraFov
            );
        }


        rotInv.set(viewRotationMatrix).invert();

        tempViewInv
                .translation(
                        (float) pos.x,
                        (float) pos.y,
                        (float) pos.z
                )
                .mul(rotInv);

        tempViewInv.get(viewInvArray);

        rotInv.get(handRotationArray);
        EntityRtCapture.setHandRotation(handRotationArray);

        EntityRtCapture.setCameraOrigin((float) pos.x, (float) pos.y, (float) pos.z);

        tempProjInv
                .set(projectionMatrix)
                .invert();

        tempProjInv.get(projInvArray);


        if ((frameCount % 60) == 0 && RTX_DEBUG) {

            org.joml.Vector4f target =
                    new org.joml.Vector4f(
                            0.0f,
                            0.0f,
                            1.0f,
                            1.0f
                    );

            tempProjInv.transform(target);

            org.joml.Vector3f rayDir;
            if (Math.abs(target.w) > 1.0e-6f) {
                float invW = 1.0f / target.w;
                rayDir = new org.joml.Vector3f(
                        target.x * invW,
                        target.y * invW,
                        target.z * invW
                );
            } else {
                rayDir = new org.joml.Vector3f(
                        target.x,
                        target.y,
                        target.z
                );
            }

            if (rayDir.lengthSquared() > 1.0e-12f) {
                rayDir.normalize();
            } else {
                rayDir.set(0.0f, 0.0f, -1.0f);
            }

            org.joml.Vector4f worldDir =
                    new org.joml.Vector4f(
                            rayDir.x,
                            rayDir.y,
                            rayDir.z,
                            0.0f
                    );

            tempViewInv.transform(worldDir);

            System.out.println(
                    "[PRIMARY DIR]"
                            + " x=" + worldDir.x
                            + " y=" + worldDir.y
                            + " z=" + worldDir.z
                            + " length="
                            + Math.sqrt(
                            worldDir.x * worldDir.x +
                                    worldDir.y * worldDir.y +
                                    worldDir.z * worldDir.z
                    )
            );
        }

        float overworldTimePhase =
                this.minecraft.level == null
                        ? 0.0f
                        : (float) Math.floorMod(
                        this.minecraft.level.getOverworldClockTime(),
                        24000L
                ) / 24000.0f;

        if (RTX_DEBUG && (frameCount % 64) == 0) {
            System.err.println(
                    "[RTX-TIME] phase=" + overworldTimePhase
            );
        }

        float rainLevel = 0.0f;
        float thunderLevel = 0.0f;
        float dimensionCode = 0.0f;
        if (this.minecraft.level != null) {
            Level level = this.minecraft.level;
            rainLevel = level.getRainLevel(1.0f);
            thunderLevel = level.getThunderLevel(1.0f);

            if (level.dimension().equals(Level.OVERWORLD)) {
                dimensionCode = 1.0f;
            } else if (level.dimension().equals(Level.NETHER)) {
                dimensionCode = 2.0f;
            } else if (level.dimension().equals(Level.END)) {
                dimensionCode = 3.0f;
            }
        }

        Vec3 playerPosition = this.minecraft.player == null
                ? Vec3.ZERO : this.minecraft.player.position();
        Vec3 playerVelocity = this.minecraft.player == null
                ? Vec3.ZERO : this.minecraft.player.getDeltaMovement();
        float playerInWater = this.minecraft.player != null &&
                this.minecraft.player.isInWater() ? 1.0f : 0.0f;
        float waterSurfaceY = findPlayerWaterSurfaceY(playerPosition);
        float cameraInWater = isRenderCameraInWater(pos) ? 1.0f : 0.0f;

        NativeVulkan.updateCamera(
                viewInvArray,
                projInvArray,
                frameCount,
                cameraNear,
                cameraFar,
                cameraFov,
                overworldTimePhase,
                rainLevel,
                thunderLevel,
                dimensionCode,
                (float) playerPosition.x,
                (float) playerPosition.y,
                (float) playerPosition.z,
                (float) playerVelocity.x,
                (float) playerVelocity.y,
                (float) playerVelocity.z,
                playerInWater,
                waterSurfaceY,
                cameraInWater
        );

        tickWaterInteractionEntities();

        frameCount++;


    }

    /**
     * 2026-10-17 实体交互波。每帧在 updateCamera 之后扫一遍可见实体：
     * <ul>
     *   <li>掉落物：入水沿（id 状态机）→ {@code queueWaterImpulse} 一次性脉冲；</li>
     *   <li>船（AbstractBoat 全家：船/木筏/运输船）：在水中且距玩家 ≤24 格 →
     *       {@code updateWaterBoats} 持续移动波源，入水/离水沿另发脉冲。</li>
     * </ul>
     * 24 格 = 水模拟盒（48×48，玩家居中）的半宽——盒外的源本来就落不进波场。
     */
    private void tickWaterInteractionEntities() {
        final Level level = this.minecraft == null ? null : this.minecraft.level;
        if (level == null) {
            return;
        }
        if (level != waterEntityLastLevel) {
            waterEntityLastLevel = level;
            waterEntityWasInWater.clear();
        }

        final net.minecraft.world.phys.Vec3 playerPosition =
                this.minecraft.player == null
                        ? net.minecraft.world.phys.Vec3.ZERO
                        : this.minecraft.player.position();

        final net.minecraft.client.multiplayer.ClientLevel clientLevel =
                (net.minecraft.client.multiplayer.ClientLevel) level;
        int boatCount = 0;
        for (net.minecraft.world.entity.Entity entity : clientLevel.entitiesForRendering()) {
            final boolean isBoat =
                    entity instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat;
            final boolean isItem =
                    entity instanceof net.minecraft.world.entity.item.ItemEntity;
            if (!isBoat && !isItem) {
                continue;
            }

            final boolean inWater = entity.isInWater();
            final Integer entityId = entity.getId();
            final Boolean previous = waterEntityWasInWater.get(entityId);
            if (previous != null && inWater != previous.booleanValue()) {
                if (inWater) {
                    final double speed = entity.getDeltaMovement().length();
                    final float strength = isBoat
                            ? (float) (0.50 + 0.06 * Math.min(speed, 4.0))
                            : (float) (0.24 + 0.10 * Math.min(speed, 4.0));
                    NativeVulkan.queueWaterImpulse(
                            (float) entity.getX(),
                            (float) entity.getZ(),
                            strength
                    );
                } else if (isBoat) {
                    NativeVulkan.queueWaterImpulse(
                            (float) entity.getX(),
                            (float) entity.getZ(),
                            -0.30f
                    );
                }
            }
            waterEntityWasInWater.put(entityId, inWater);

            if (isBoat && inWater
                    && boatCount < WATER_ENTITY_MAX_BOATS
                    && entity.distanceToSqr(playerPosition) <= WATER_ENTITY_RANGE_SQR) {
                final int base = boatCount * 5;
                waterBoatData[base] = (float) entity.getX();
                waterBoatData[base + 1] = (float) entity.getZ();
                waterBoatData[base + 2] = (float) entity.getDeltaMovement().x;
                waterBoatData[base + 3] = (float) entity.getDeltaMovement().z;
                waterBoatData[base + 4] = 1.5f;
                boatCount++;
            }
        }

        if (waterEntityWasInWater.size() > 1024) {
            waterEntityWasInWater.clear();
        }
        NativeVulkan.updateWaterBoats(waterBoatData, boatCount);
    }
}
