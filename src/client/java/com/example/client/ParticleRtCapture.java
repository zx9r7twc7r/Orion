package com.example.client;

import com.example.vulkan.NativeVulkan;
import com.example.client.config.RTXSettingsConfig;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** RT-only capture for vanilla's already-extracted particle quads. */
public final class ParticleRtCapture {
    public static final int MAX_RT_PARTICLES = 256;
    public static final int RT_RAIN_MARKER = -2;
    public static final int RT_SUPPRESS_MARKER = -3;
    

    public static final int RT_SNOW_MARKER = -4;
    private static final int BASE_WEATHER_GRID_SIZE = 16;
    private static final float RT_RAIN_DENSITY_SCALE = 7.0F;
    private static final int WEATHER_GRID_SIZE =
            (int)Math.round(BASE_WEATHER_GRID_SIZE * Math.sqrt(RT_RAIN_DENSITY_SCALE));
    private static final int MAX_RT_RAIN_DROPS = WEATHER_GRID_SIZE * WEATHER_GRID_SIZE;
    private static final float RAIN_CELL_SPACING = 1.35F;
    private static final int MAX_RT_WEATHER_QUADS = MAX_RT_RAIN_DROPS * 2;
    private static final float RT_RAIN_REACHABLE_FRACTION = 0.077F;
    private static float weatherRainHalfWidth = 0.039F;
    private static final float RT_RAIN_TINT_RED = 228.0F / 255.0F;
    private static final float RT_RAIN_TINT_GREEN = 238.0F / 255.0F;
    private static final float RT_RAIN_TINT_BLUE = 245.0F / 255.0F;
    private static final float RT_RAIN_NEAR_FADE_INNER = 0.50F;
    private static final float RT_RAIN_NEAR_FADE_OUTER = 1.80F;

    private static final float RT_SNOW_TILE_V_BASE = 0.5F;
    private static final int RT_SNOW_FLAKES_PER_CELL = 2;
    

    private static final float RT_SNOW_FLAKE_MIN_SIZE = 0.06F;
    private static final float RT_SNOW_FLAKE_MAX_SIZE = 0.19F;
    

    private static final float RT_SNOW_FALL_MIN = 0.085F;
    private static final float RT_SNOW_FALL_MAX = 0.145F;
    

    private static final float RT_SNOW_SWAY_FREQUENCY = 0.22F;
    private static final float RT_SNOW_TINT_RED = 236.0F / 255.0F;
    private static final float RT_SNOW_TINT_GREEN = 242.0F / 255.0F;
    private static final float RT_SNOW_TINT_BLUE = 250.0F / 255.0F;
    private static final int BYTES_PER_VERTEX = 12 * Float.BYTES;
    private static final Identifier RT_RAIN_STREAK_TEXTURE =
            Identifier.fromNamespaceAndPath("orion", "textures/rt_rain_streaks.png");
    private static final Identifier RT_STAR_MAP_TEXTURE =
            Identifier.fromNamespaceAndPath("orion", "textures/rt_star_map.png");
    private static final String STAR_MAP_RESOURCE_PATH =
            "/assets/orion/textures/rt_star_map.png";
    private static final String RAIN_STREAK_RESOURCE_PATH =
            "/assets/orion/textures/rt_rain_streaks.png";
    private static final String END_SKY_ATLAS_RESOURCE_PATH =
            "/assets/orion/textures/rt_end_atlas.png";
    private static final Quad[] nearest = createQuadArray(MAX_RT_PARTICLES);
    private static final Quad[] weatherQuads = createQuadArray(MAX_RT_WEATHER_QUADS);
    private static int nearestCount;
    private static int weatherQuadCount;
    private static final BlockPos.MutableBlockPos weatherProbe = new BlockPos.MutableBlockPos();
    private static int particleBudget = MAX_RT_PARTICLES;
    private static int genericParticleBudget = MAX_RT_PARTICLES;
    private static final ByteBuffer particleVertices = ByteBuffer
            .allocateDirect((MAX_RT_PARTICLES + MAX_RT_WEATHER_QUADS) * 6 * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder());

    private static final ByteBuffer particleMotion = ByteBuffer
            .allocateDirect((MAX_RT_PARTICLES + MAX_RT_WEATHER_QUADS) * 6 * 3 * Float.BYTES)
            .order(ByteOrder.nativeOrder());
    private static final Quaternionf particleRotation = new Quaternionf();
    private static final Quaternionf weatherFlakeRotation = new Quaternionf();
    private static final Vector3f particleVertexA = new Vector3f();
    private static final Vector3f particleVertexB = new Vector3f();
    private static final Vector3f particleVertexC = new Vector3f();
    private static final Vector3f particleVertexD = new Vector3f();
    private static final long[] publishedViews = new long[3];
    private static final long[] publishedSamplers = new long[3];
    private static long publishedRainStreakView;
    private static long publishedRainStreakSampler;
    private static long publishedStarMapView;
    private static long publishedStarMapSampler;
    private static long publishedEndSkyAtlasView;
    private static long publishedEndSkyAtlasSampler;
    private static final AbstractTexture[] cachedAtlasTextures =
            new AbstractTexture[3];
    private static AbstractTexture cachedRainStreakTexture;
    private static AbstractTexture cachedStarMapTexture;
    private static OwnTexture starMapOwnTexture;
    private static boolean starMapLoadAttempted;
    private static OwnTexture endSkyAtlasOwnTexture;
    private static boolean endSkyAtlasLoadAttempted;
    private static OwnTexture rainStreakOwnTexture;
    private static boolean rainStreakLoadAttempted;
    private static final ThreadLocal<SingleQuadParticle.Layer> activeBuildLayer = new ThreadLocal<>();
    private static final ThreadLocal<Integer> activeExtractMarker = new ThreadLocal<>();
    private static final Map<Particle, Integer> particleMarkers =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ParticleType<?>, Integer> emissionTypeMarkers =
            new ConcurrentHashMap<>();

    private ParticleRtCapture() {}

    private static final long WEATHER_RAIN_REBUILD_INTERVAL_NANOS = 50_000_000L;
    private static long weatherRainLastBuildNanos;
    private static boolean weatherRainWasActive;
    /**
     * 天气层（雨 + 雪）是否需要本轮重建。未下雨时必须返回 true，让 buildRtWeather
     * 走空（它自身在无雨时直接 return，weatherQuadCount 已在调用点清零 ⇒ 粒子层
     * 随之清空）。
     */
    private static boolean shouldRebuildWeather() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean raining = minecraft.level != null
                && minecraft.player != null
                && minecraft.level.isRaining()
                && minecraft.level.getRainLevel(1.0F) > 0.01F;
        if (!raining) {
            weatherRainWasActive = false;
            weatherRainLastBuildNanos = 0L;
            return true;
        }
        long now = System.nanoTime();
        boolean firstFrameOfThisRain = !weatherRainWasActive;
        weatherRainWasActive = true;
        if (firstFrameOfThisRain
                || now - weatherRainLastBuildNanos >= WEATHER_RAIN_REBUILD_INTERVAL_NANOS) {
            weatherRainLastBuildNanos = now;
            return true;
        }
        return false;
    }

    public static void beginFrame() {
        updateParticleMotionFrame();
        nearestCount = 0;
        particleBudget = RTXSettingsConfig
                .getPerformanceSettings()
                .maxRtParticles();
        if(shouldRebuildWeather())
        {
            weatherQuadCount = 0;
            buildRtWeather();
        }
        genericParticleBudget = particleBudget;
        publishAtlas(0, TextureAtlas.LOCATION_PARTICLES);
        publishAtlas(1, TextureAtlas.LOCATION_BLOCKS);
        publishAtlas(2, TextureAtlas.LOCATION_ITEMS);
        publishRainStreakTexture();
        publishStarMapTexture();
        publishEndSkyAtlasTexture();
        updateAuroraStrength();
        NativeVulkan.beginParticleFrame();
    }

    /**
     * TextureAtlas reload replaces the three Vulkan image views. Their raw
     * handle values are only valid within that atlas lifetime.
     */
    public static void invalidateTextureCache() {
        Arrays.fill(publishedViews, 0L);
        Arrays.fill(publishedSamplers, 0L);
        publishedRainStreakView = 0L;
        publishedRainStreakSampler = 0L;
        publishedStarMapView = 0L;
        publishedStarMapSampler = 0L;
        publishedEndSkyAtlasView = 0L;
        publishedEndSkyAtlasSampler = 0L;
        Arrays.fill(cachedAtlasTextures, null);
        cachedRainStreakTexture = null;
        cachedStarMapTexture = null;
    }

    /** Called around QuadParticleRenderState.buildLayer on the render thread. */
    public static void beginBuildLayer(SingleQuadParticle.Layer layer) {
        activeBuildLayer.set(layer);
    }

    /** Clears the render-thread layer selection after vanilla has built that layer. */
    public static void endBuildLayer() {
        activeBuildLayer.remove();
    }

    /** Returns the layer currently being consumed by QuadParticleRenderState.Storage. */
    public static SingleQuadParticle.Layer currentBuildLayer() {
        SingleQuadParticle.Layer layer = activeBuildLayer.get();
        if (layer == null) {
            throw new IllegalStateException("Particle RT capture ran outside QuadParticleRenderState.buildLayer");
        }
        return layer;
    }

    /** Remembers the two RT-special particle categories without allocating per-frame records. */
    public static void rememberEmissionType(Particle particle, ParticleOptions options) {
        final ParticleType<?> type = options.getType();
        Integer marker = emissionTypeMarkers.get(type);
        if (marker == null) {
            String path = BuiltInRegistries.PARTICLE_TYPE.getKey(type).getPath();
            marker = switch (path) {
                case "rain" -> RT_RAIN_MARKER;
                case "bubble", "bubble_pop", "bubble_column_up",
                     "bubble_column_updown", "suspended", "suspended_depth" ->
                    RT_SUPPRESS_MARKER;
                case "flame", "small_flame", "soul_fire_flame", "dragon_breath",
                     "lava", "dripping_lava", "falling_lava", "landing_lava",
                     "firework", "electric_spark", "experience", "note" -> 1;
                default -> 0;
            };
            emissionTypeMarkers.put(type, marker);
        }
        if (marker != 0) {
            particleMarkers.put(particle, marker);
        }
    }

    private static final ThreadLocal<float[]> activeExtractMotion =
            new ThreadLocal<>();

    private static final java.util.WeakHashMap<Particle, float[]> particlePrevRel =
            new java.util.WeakHashMap<>();

    private static int particleMotionFrameId;

    /** 每帧一次：推进帧序号（"本帧是否已算过"的判据）。 */
    private static void updateParticleMotionFrame() {
        ++particleMotionFrameId;
    }

    /** Begins the short extraction-to-storage handoff for one vanilla quad. */
    public static void beginParticleExtract(
            Particle particle, float x, float y, float z) {
        activeExtractMarker.set(particleMarkers.getOrDefault(particle, 0));

        final float[] motion = activeExtractMotion.get();
        final float[] target = motion != null ? motion : new float[3];

        final float[] previous = particlePrevRel.get(particle);

        if (previous == null) {
            target[0] = 0.0F;
            target[1] = 0.0F;
            target[2] = 0.0F;
            particlePrevRel.put(
                    particle, new float[] { x, y, z, particleMotionFrameId });
        } else if ((int)previous[3] != particleMotionFrameId) {
            target[0] = previous[0] - x;
            target[1] = previous[1] - y;
            target[2] = previous[2] - z;

            previous[0] = x;
            previous[1] = y;
            previous[2] = z;
            previous[3] = particleMotionFrameId;
        } else {
        }

        if (motion == null) {
            activeExtractMotion.set(target);
        }
    }

    public static void endParticleExtract() {
        activeExtractMarker.remove();
        activeExtractMotion.remove();
    }

    /** 0 is ordinary; 1 is emissive; -2 is the RT-only weather-rain marker. */
    public static int currentExtractEmissionMarker() {
        Integer marker = activeExtractMarker.get();
        return marker != null ? marker : 0;
    }

    public static boolean isRtRainMarker(int marker) {
        return marker == RT_RAIN_MARKER;
    }

    public static void capture(
            SingleQuadParticle.Layer layer,
            float x, float y, float z, float scale, float u0, float u1, float v0, float v1,
            float xRot, float yRot, float zRot, float wRot,
            int color, int emissionMarker) {
        int atlasSlot = atlasSlot(layer.textureAtlasLocation());
        if (atlasSlot == 0 || color >>> 24 == 0) return;
        if (emissionMarker == RT_SUPPRESS_MARKER) return;
        double distanceSq = (double)x * x + (double)y * y + (double)z * z;
        if (nearestCount < genericParticleBudget) {
            Quad quad = nearest[nearestCount];
            quad.set(distanceSq, atlasSlot, x, y, z, scale, u0, u1, v0, v1,
                    xRot, yRot, zRot, wRot, color, emissionMarker);
            applyExtractMotion(quad);
            siftUp(nearestCount++);
            return;
        }
        if (genericParticleBudget == 0 || distanceSq >= nearest[0].distanceSq) return;

        nearest[0].set(distanceSq, atlasSlot, x, y, z, scale, u0, u1, v0, v1,
                xRot, yRot, zRot, wRot, color, emissionMarker);
        applyExtractMotion(nearest[0]);
        siftDown(0);
    }

    /** Keeps the dormant legacy mixin source compilable; it is no longer registered. */
    public static void capture(
            SingleQuadParticle.Layer layer,
            Quaternionf rotation,
            float x, float y, float z, float scale, float u0, float u1, float v0, float v1,
            int color, int ignoredLightCoords) {
        capture(layer, x, y, z, scale, u0, u1, v0, v1,
                rotation.x, rotation.y, rotation.z, rotation.w, color, 0);
    }

    public static void endFrame() {
        if (weatherQuadCount + nearestCount > 0) {
            particleVertices.clear();
            particleMotion.clear();
            for (int index = 0; index < weatherQuadCount; ++index) {
                emitQuad(particleVertices, weatherQuads[index]);
            }
            for (int index = 0; index < nearestCount; ++index) {
                emitQuad(particleVertices, nearest[index]);
            }
            particleVertices.flip();
            particleMotion.flip();
            NativeVulkan.uploadParticleBatch(
                    MemoryUtil.memAddress(particleVertices),
                    particleVertices.remaining() / BYTES_PER_VERTEX,
                    BYTES_PER_VERTEX,
                    MemoryUtil.memAddress(particleMotion)
            );
        }
        NativeVulkan.endParticleFrame();
    }

    private static void emitQuad(ByteBuffer output, Quad q) {
        particleRotation.set(q.xRot, q.yRot, q.zRot, q.wRot);
        float width = isRtRainMarker(q.emissionMarker)
                ? weatherRainHalfWidth / Math.max(q.scale, 1.0e-4F)
                : 1.0F;
        Vector3f a = vertex(q, particleRotation, width, -1.0F, particleVertexA);
        Vector3f b = vertex(q, particleRotation, width, 1.0F, particleVertexB);
        Vector3f c = vertex(q, particleRotation, -width, 1.0F, particleVertexC);
        Vector3f d = vertex(q, particleRotation, -width, -1.0F, particleVertexD);
        if (isRtRainMarker(q.emissionMarker)) {
            applyWeatherWind(a, -1.0F, q);
            applyWeatherWind(b, 1.0F, q);
            applyWeatherWind(c, 1.0F, q);
            applyWeatherWind(d, -1.0F, q);
        }
        emit(output, a, q.u1, q.v1, q); emit(output, b, q.u1, q.v0, q); emit(output, c, q.u0, q.v0, q);
        emit(output, a, q.u1, q.v1, q); emit(output, c, q.u0, q.v0, q); emit(output, d, q.u0, q.v1, q);
    }

    /**
     * World half-width of a rain card, derived from the user-facing streak
     * width in millimetres.
     *
     * The atlas only lets a fraction of the card's columns reach the shader at
     * all -- the rest sit below the `0.025` cutoff in rt_test.rahit -- so the
     * card has to be about 13x wider than the streak it draws.  That fraction
     * is measured, not guessed: see tools/rain_atlas/README.md and measure.py,
     * which report 7.7% at the default vertex alpha and 8.1% at the widest.
     * The conversion itself lives on the settings record so the two sides
     * cannot drift apart.
     */
    private static float rainHalfWidthBlocks(RTXSettingsConfig.RainSettings look) {
        return look.halfWidthBlocks(RT_RAIN_REACHABLE_FRACTION);
    }

    /**
     * Dedicated weather geometry.  This bypasses ParticleEngine completely:
     * no ParticleTypes.RAIN allocation, no vanilla render submit and no
     * dependence on Minecraft's particle extraction order.  Two crossed
     * vertical quads give each falling drop a visible silhouette from every
     * horizontal view direction while staying within the existing RT-particle
     * vertex budget.
     *
     * <p>2026-10-07: the lattice now carries the snow half too.  Each column
     * is routed by its {@code getPrecipitationAt} answer -- RAIN columns emit
     * the crossed streak pairs described above, SNOW columns emit
     * {@link #RT_SNOW_FLAKES_PER_CELL} camera-facing snowflake billboards per
     * cell ({@code appendWeatherSnow}) that sample the atlas's bottom half.
     */
    private static void buildRtWeather() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !minecraft.level.isRaining()) {
            return;
        }

        float rain = minecraft.level.getRainLevel(1.0F);
        if (rain <= 0.01F) {
            return;
        }

        int requestedDrops =
                (int)((64.0F + rain * 192.0F) * RT_RAIN_DENSITY_SCALE *
                        RTXSettingsConfig.getRainSettings().densityScale());
        int dropCount = Math.min(requestedDrops, MAX_RT_RAIN_DROPS);
        float seconds = System.nanoTime() * 1.0e-9F;
        RTXSettingsConfig.RainSettings rainLook = RTXSettingsConfig.getRainSettings();
        final float rainScale = rainLook.streakLength() * 0.5F;
        weatherRainHalfWidth = rainHalfWidthBlocks(rainLook);
        final float rainBrightness = rainLook.brightness();
        final int rainColor = ARGB.colorFromFloat(
                rainLook.opacity(),
                Math.min(RT_RAIN_TINT_RED * rainBrightness, 1.0F),
                Math.min(RT_RAIN_TINT_GREEN * rainBrightness, 1.0F),
                Math.min(RT_RAIN_TINT_BLUE * rainBrightness, 1.0F));
        RTXSettingsConfig.SnowSettings snowLook = RTXSettingsConfig.getSnowSettings();
        int snowCellCount = Math.min(
                (int)((64.0F + rain * 192.0F)
                        * RT_RAIN_DENSITY_SCALE * snowLook.densityScale()),
                MAX_RT_RAIN_DROPS);
        final float snowBrightness = snowLook.brightness();
        final int snowColor = ARGB.colorFromFloat(
                snowLook.opacity(),
                Math.min(RT_SNOW_TINT_RED * snowBrightness, 1.0F),
                Math.min(RT_SNOW_TINT_GREEN * snowBrightness, 1.0F),
                Math.min(RT_SNOW_TINT_BLUE * snowBrightness, 1.0F));
        final float cellSpacing =
                RAIN_CELL_SPACING * BASE_WEATHER_GRID_SIZE / WEATHER_GRID_SIZE;
        final int anchorX = (int)Math.floor(minecraft.player.getX() / cellSpacing);
        final int anchorZ = (int)Math.floor(minecraft.player.getZ() / cellSpacing);
        final float eyeY = (float)minecraft.player.getEyeY();

        final Quaternionf cameraRotation = minecraft.gameRenderer.mainCamera().rotation();

        final float rainFill =
                Math.min(1.0F, (float)dropCount / (float)MAX_RT_RAIN_DROPS);
        final float snowFill =
                Math.min(1.0F, (float)snowCellCount / (float)MAX_RT_RAIN_DROPS);
        final float anyFill = Math.max(rainFill, snowFill);
        final float playerX = (float)minecraft.player.getX();
        final float playerZ = (float)minecraft.player.getZ();

        final float gust = 1.0F
                + 0.35F * (float)Math.sin(seconds * 0.85F)
                + 0.20F * (float)Math.sin(seconds * 2.31F + 1.7F);
        final float rainWindScale = (0.45F + 0.55F * rain) * gust;
        final float rainWindX = 0.32F * rainWindScale;
        final float rainWindZ = 0.11F * rainWindScale;

        for (int cell = 0; cell < MAX_RT_RAIN_DROPS; ++cell) {
            int gridX = cell % WEATHER_GRID_SIZE;
            int gridZ = cell / WEATHER_GRID_SIZE;
            int worldCellX = anchorX + gridX - WEATHER_GRID_SIZE / 2;
            int worldCellZ = anchorZ + gridZ - WEATHER_GRID_SIZE / 2;
            int seed = hashWeatherSeed(
                    worldCellX * 0x9e3779b1 ^ worldCellZ * 0x85ebca77);
            float cellUnit = unitFromSeed(hashWeatherSeed(seed ^ 0x165667b1));
            if (cellUnit >= anyFill) {
                continue;
            }
            float jitterX = unitFromSeed(seed) - 0.5F;
            float jitterZ = unitFromSeed(seed * 1664525 + 1013904223) - 0.5F;
            float worldX = (worldCellX + jitterX * 0.62F) * cellSpacing;
            float worldZ = (worldCellZ + jitterZ * 0.62F) * cellSpacing;
            float x = worldX - playerX;
            float z = worldZ - playerZ;
            float phase = unitFromSeed(seed * 1103515245 + 12345);
            int blockX = (int)Math.floor(worldX);
            int blockZ = (int)Math.floor(worldZ);
            int columnTop = minecraft.level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            Biome.Precipitation precipitation =
                    minecraft.level.getPrecipitationAt(weatherProbe.set(blockX, (int)eyeY, blockZ));
            if (precipitation == Biome.Precipitation.NONE) {
                continue;
            }
            if (precipitation == Biome.Precipitation.SNOW) {
                if (cellUnit >= snowFill) {
                    continue;
                }
                appendWeatherSnow(cameraRotation, snowColor, snowLook,
                        seconds, seed, phase, x, z, worldX, worldZ,
                        eyeY);
                continue;
            }
            if (cellUnit >= rainFill) {
                continue;
            }
            float y = -12.0F + fract(phase - seconds * 0.65F) * 28.0F;
            if (eyeY + y < columnTop) {
                continue;
            }
            float nearFade = smoothstep(
                    RT_RAIN_NEAR_FADE_INNER,
                    RT_RAIN_NEAR_FADE_OUTER,
                    (float)Math.sqrt(x * x + y * y + z * z));
            if (nearFade <= 0.02F) {
                continue;
            }
            float streakScale = rainScale * nearFade;
            int variant = hashWeatherSeed(seed ^ 0x51ed270b) & 15;
            float u0 = (variant & 3) * 0.25F;
            float v0 = (variant >>> 2) * 0.125F;
            float u1 = u0 + 0.25F;
            float v1 = v0 + 0.125F;
            appendWeatherQuad(x, y, z, streakScale, u0, u1, v0, v1,
                    0.0F, 0.0F, 0.0F, 1.0F,
                    rainColor, RT_RAIN_MARKER, rainWindX, rainWindZ);
            appendWeatherQuad(x, y, z, streakScale, u0, u1, v0, v1,
                    0.0F, 0.70710677F, 0.0F, 0.70710677F,
                    rainColor, RT_RAIN_MARKER, rainWindX, rainWindZ);
        }
    }

    /**
     * 2026-10-07 雪：一个 SNOW 天气格的 {@link #RT_SNOW_FLAKES_PER_CELL} 片雪。
     *
     * 每片独立相位/下落速度/尺寸/面内自旋/摇摆，全部从格 seed 派生，所以
     * 20 Hz 重建之间整个雪幕只按各自速度推进（与雨丝同一条"世界稳定"约定，
     * 不做屏幕空间精灵）。
     * 2026-10-07：这个 seed 现在是**世界格**哈希 —— 曾经是循环下标，那会让整幅
     * 雪幕以玩家为原点刚体平移（就是"雪跟着人走"），见 buildRtWeather 的说明。
     * 遮挡在摇摆后的实际落点重新探 MOTION_BLOCKING：
     * 格距 0.51 格而摆幅 0.30 格，片可能跨进邻格的屋檐下。近场收缩复用雨的
     * smoothstep 常数。
     *
     * 形状是单张相机 billboard（对比：雨是两张**不**随相机的正交竖条——雨丝
     * 匹配俯仰会不像雨；雪片是圆片，不匹配俯仰在俯视时会变成一条线）。JOML
     * 的 rotateZ 是后乘 ⇒ 自旋先于相机旋转、发生在卡片自身平面内。
     */
    private static void appendWeatherSnow(
            Quaternionf cameraRotation, int snowColor,
            RTXSettingsConfig.SnowSettings snowLook,
            float seconds, int seed, float phase,
            float x, float z, float worldX, float worldZ,
            float eyeY) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int flake = 0; flake < RT_SNOW_FLAKES_PER_CELL; ++flake) {
            int flakeSeed = hashWeatherSeed(seed ^ (0x9E3779B9 * (flake + 1)));
            float flakePhase = fract(phase + unitFromSeed(flakeSeed ^ 0x68bc21eb));
            float fallSpeed = (RT_SNOW_FALL_MIN
                    + (RT_SNOW_FALL_MAX - RT_SNOW_FALL_MIN)
                      * unitFromSeed(flakeSeed ^ 0x7feb352d))
                    * snowLook.fallSpeedScale();
            float y = -12.0F + fract(flakePhase - seconds * fallSpeed) * 28.0F;
            float swayPhase =
                    unitFromSeed(flakeSeed ^ 0x51ed270b) * (float)(Math.PI * 2.0);
            float swayAngle =
                    seconds * RT_SNOW_SWAY_FREQUENCY * (float)(Math.PI * 2.0)
                    + swayPhase;
            float swayX = (float)Math.sin(swayAngle) * snowLook.swayBlocks();
            float swayZ = (float)Math.cos(swayAngle * 1.618F) * snowLook.swayBlocks();
            float flakeX = x + swayX;
            float flakeZ = z + swayZ;
            int blockX = (int)Math.floor(worldX + swayX);
            int blockZ = (int)Math.floor(worldZ + swayZ);
            if (eyeY + y < minecraft.level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)) {
                continue;
            }
            float nearFade = smoothstep(
                    RT_RAIN_NEAR_FADE_INNER,
                    RT_RAIN_NEAR_FADE_OUTER,
                    (float)Math.sqrt(flakeX * flakeX + y * y + flakeZ * flakeZ));
            if (nearFade <= 0.02F) {
                continue;
            }
            float size = (RT_SNOW_FLAKE_MIN_SIZE
                    + (RT_SNOW_FLAKE_MAX_SIZE - RT_SNOW_FLAKE_MIN_SIZE)
                      * unitFromSeed(flakeSeed ^ 0x85ebca6b))
                    * snowLook.sizeScale();
            int variant = hashWeatherSeed(flakeSeed ^ 0xc2b2ae35) & 15;
            float u0 = (variant & 3) * 0.25F;
            float v0 = RT_SNOW_TILE_V_BASE + (variant >>> 2) * 0.125F;
            float u1 = u0 + 0.25F;
            float v1 = v0 + 0.125F;
            weatherFlakeRotation.set(cameraRotation).rotateZ(
                    unitFromSeed(flakeSeed ^ 0x27d4eb2f) * (float)(Math.PI * 2.0));
            appendWeatherQuad(flakeX, y, flakeZ, size * nearFade, u0, u1, v0, v1,
                    weatherFlakeRotation.x, weatherFlakeRotation.y,
                    weatherFlakeRotation.z, weatherFlakeRotation.w,
                    snowColor, RT_SNOW_MARKER);
        }
    }

    private static void appendWeatherQuad(
            float x, float y, float z, float scale,
            float u0, float u1, float v0, float v1,
            float xRot, float yRot, float zRot, float wRot, int color, int marker) {
        appendWeatherQuad(x, y, z, scale, u0, u1, v0, v1,
                xRot, yRot, zRot, wRot, color, marker, 0.0F, 0.0F);
    }

    private static void appendWeatherQuad(
            float x, float y, float z, float scale,
            float u0, float u1, float v0, float v1,
            float xRot, float yRot, float zRot, float wRot, int color, int marker,
            float windX, float windZ) {
        if (weatherQuadCount >= MAX_RT_WEATHER_QUADS) {
            return;
        }
        Quad quad = weatherQuads[weatherQuadCount++];
        quad.set(
                x * x + y * y + z * z,
                1,
                x, y, z, scale,
                u0, u1, v0, v1,
                xRot, yRot, zRot, wRot,
                color,
                marker
        );
        quad.windX = windX;
        quad.windZ = windZ;
    }

    private static int hashWeatherSeed(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static float unitFromSeed(int value) {
        return (value & 0x00FFFFFF) * (1.0F / 16777216.0F);
    }

    private static float fract(float value) {
        return value - (float)Math.floor(value);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static Vector3f vertex(
            Quad q, Quaternionf rotation, float nx, float ny, Vector3f output) {
        return output.set(nx, ny, 0.0F).rotate(rotation).mul(q.scale).add(q.x, q.y, q.z);
    }

    /** 风斜：把卡片顶端相对底端横移（世界空间），形成斜雨丝。 */
    private static void applyWeatherWind(Vector3f p, float ny, Quad q) {
        if (q.windX == 0.0F && q.windZ == 0.0F) {
            return;
        }
        final float lift = ny * q.scale;
        p.x += q.windX * lift;
        p.z += q.windZ * lift;
    }

    /** 把本次 extract 交接里的位移挂到这个 quad 上（没有就写 0，绝不留旧值）。 */
    private static void applyExtractMotion(Quad quad) {
        final float[] motion = activeExtractMotion.get();

        if (motion == null) {
            quad.dx = 0.0F;
            quad.dy = 0.0F;
            quad.dz = 0.0F;
            return;
        }

        quad.dx = motion[0];
        quad.dy = motion[1];
        quad.dz = motion[2];
    }

    private static void emit(ByteBuffer output, Vector3f p, float u, float v, Quad q) {
        output.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(u).putFloat(v);
        output.putFloat(ARGB.red(q.color) / 255.0F).putFloat(ARGB.green(q.color) / 255.0F)
                .putFloat(ARGB.blue(q.color) / 255.0F).putFloat(ARGB.alpha(q.color) / 255.0F);
        output.putFloat(0.0F).putFloat(q.emissionMarker).putFloat(-q.atlasSlot);

        particleMotion.putFloat(q.dx).putFloat(q.dy).putFloat(q.dz);
    }

    private static int atlasSlot(Identifier texture) {
        if (TextureAtlas.LOCATION_PARTICLES.equals(texture)) return 1;
        if (TextureAtlas.LOCATION_BLOCKS.equals(texture)) return 2;
        return TextureAtlas.LOCATION_ITEMS.equals(texture) ? 3 : 0;
    }

    private static void publishAtlas(int index, Identifier id) {
        AbstractTexture texture = cachedAtlasTextures[index];
        if (texture == null) {
            texture = Minecraft.getInstance().getTextureManager().getTexture(id);
            cachedAtlasTextures[index] = texture;
        }
        GpuTextureView view = texture.getTextureView();
        GpuSampler sampler = texture.getSampler();
        if (!(view instanceof VulkanGpuTextureView vkView) || !(sampler instanceof VulkanGpuSampler vkSampler)) return;
        long imageView = vkView.vkImageView();
        long vkSamplerHandle = vkSampler.vkSampler();
        if (publishedViews[index] != imageView || publishedSamplers[index] != vkSamplerHandle) {
            publishedViews[index] = imageView;
            publishedSamplers[index] = vkSamplerHandle;
            NativeVulkan.updateParticleTexture(index + 1, imageView, vkSamplerHandle);
        }
    }

    private static void publishRainStreakTexture() {
        if (rainStreakOwnTexture == null) {
            if (rainStreakLoadAttempted) {
                return;
            }
            rainStreakLoadAttempted = true;
            rainStreakOwnTexture = loadOwnTexture(
                    RAIN_STREAK_RESOURCE_PATH, "RAINSTREAK", "orion:rt_rain_streaks");
            if (rainStreakOwnTexture == null) {
                return;
            }
        }
        if (!(rainStreakOwnTexture.view() instanceof VulkanGpuTextureView vkView)
                || !(rainStreakOwnTexture.sampler() instanceof VulkanGpuSampler vkSampler)) {
            return;
        }
        long imageView = vkView.vkImageView();
        long vkSamplerHandle = vkSampler.vkSampler();
        if (publishedRainStreakView != imageView || publishedRainStreakSampler != vkSamplerHandle) {
            publishedRainStreakView = imageView;
            publishedRainStreakSampler = vkSamplerHandle;
            NativeVulkan.updateRainStreakTexture(imageView, vkSamplerHandle);
            System.out.println("[RAINSTREAK] published view=0x" + Long.toHexString(imageView));
        }
    }

    /**
     * Retained as the alternative path: taking the rain streak sprite out of
     * Minecraft's own TextureManager.  That only resolves when something
     * registers the `orion` namespace with the ResourceManager (Fabric API's
     * resource loader), so it is unused now -- see loadOwnTexture() for the
     * reason.  Left in place because it is the shortest route back if this mod
     * ever does pick up Fabric API.
     */
    @SuppressWarnings("unused")
    private static void publishRainStreakTextureFromMinecraftResource() {
        AbstractTexture texture = cachedRainStreakTexture;
        if (texture == null) {
            texture = Minecraft.getInstance()
                    .getTextureManager()
                    .getTexture(RT_RAIN_STREAK_TEXTURE);
            cachedRainStreakTexture = texture;
        }
        GpuTextureView view = texture.getTextureView();
        GpuSampler sampler = texture.getSampler();
        if (!(view instanceof VulkanGpuTextureView vkView) || !(sampler instanceof VulkanGpuSampler vkSampler)) {
            return;
        }
        long imageView = vkView.vkImageView();
        long vkSamplerHandle = vkSampler.vkSampler();
        if (publishedRainStreakView != imageView || publishedRainStreakSampler != vkSamplerHandle) {
            publishedRainStreakView = imageView;
            publishedRainStreakSampler = vkSamplerHandle;
            NativeVulkan.updateRainStreakTexture(imageView, vkSamplerHandle);
        }
    }

    /** A texture uploaded by this class rather than by Minecraft's TextureManager. */
    private record OwnTexture(GpuTextureView view, GpuSampler sampler) {}

    /**
     * Uploads one of this mod's own textures straight off its class loader.
     *
     * This mod deliberately does not depend on Fabric API, and mod assets only
     * become visible to Minecraft's ResourceManager through
     * fabric-resource-loader-v0.  Without it the `orion` namespace never shows
     * up in ResourceManager.getNamespaces(), so `orion:textures/...` cannot be
     * resolved at all: TextureManager silently substitutes its 16x16 magenta
     * missing texture without even logging an error.  For the star map
     * pow(texel, 4.4) keeps that magenta and the whole night sky turns into a
     * magenta curtain; for the rain streak sprite the streaks mix the same
     * magenta checker into their albedo.  Reading the PNG off the mod class path
     * and uploading it here gives identical results with and without Fabric API,
     * and removes the dependency on TextureManager's async load ordering.
     *
     * @return the uploaded view/sampler pair, or null if the asset could not be
     *         read; callers latch their own "attempted" flag so a failure is not
     *         retried every frame.
     */
    private static OwnTexture loadOwnTexture(
            String resourcePath, String logTag, String textureLabel) {
        try (InputStream stream = ParticleRtCapture.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                System.out.println("[" + logTag + "] missing from the mod class path: "
                        + resourcePath);
                return null;
            }
            NativeImage image = NativeImage.read(stream);
            try {
                GpuDevice device = RenderSystem.getDevice();
                GpuTexture texture = device.createTexture(
                        () -> textureLabel,
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM,
                        image.getWidth(),
                        image.getHeight(),
                        1,
                        1);
                device.createCommandEncoder().writeToTexture(texture, image);
                GpuTextureView view = device.createTextureView(texture);
                GpuSampler sampler = RenderSystem.getSamplerCache().getSampler(
                        AddressMode.CLAMP_TO_EDGE,
                        AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR,
                        FilterMode.LINEAR,
                        false);
                System.out.println("[" + logTag + "] uploaded " + image.getWidth() + "x"
                        + image.getHeight() + " from the mod class path");
                return new OwnTexture(view, sampler);
            } finally {
                image.close();
            }
        } catch (Throwable failure) {
            System.out.println("[" + logTag + "] upload failed: " + failure);
            return null;
        }
    }

    private static void publishStarMapTexture() {
        if (starMapOwnTexture == null) {
            if (starMapLoadAttempted) {
                return;
            }
            starMapLoadAttempted = true;
            starMapOwnTexture = loadOwnTexture(
                    STAR_MAP_RESOURCE_PATH, "STARMAP", "orion:rt_star_map");
            if (starMapOwnTexture == null) {
                return;
            }
        }
        if (!(starMapOwnTexture.view() instanceof VulkanGpuTextureView vkView)
                || !(starMapOwnTexture.sampler() instanceof VulkanGpuSampler vkSampler)) {
            return;
        }
        long imageView = vkView.vkImageView();
        long vkSamplerHandle = vkSampler.vkSampler();
        if (publishedStarMapView != imageView || publishedStarMapSampler != vkSamplerHandle) {
            publishedStarMapView = imageView;
            publishedStarMapSampler = vkSamplerHandle;
            NativeVulkan.updateStarMapTexture(imageView, vkSamplerHandle);
            System.out.println("[STARMAP] published view=0x" + Long.toHexString(imageView));
        }
    }

    /**
     * 2026-10-05 末地天空：与星图逐字同构的一条发布链（binding 60）。
     * 图集是自持纹理（独立于 TextureManager），所以只加载一次、句柄变化才推 native。
     */
    private static void publishEndSkyAtlasTexture() {
        if (endSkyAtlasOwnTexture == null) {
            if (endSkyAtlasLoadAttempted) {
                return;
            }
            endSkyAtlasLoadAttempted = true;
            endSkyAtlasOwnTexture = loadOwnTexture(
                    END_SKY_ATLAS_RESOURCE_PATH, "ENDSKY", "orion:rt_end_atlas");
            if (endSkyAtlasOwnTexture == null) {
                return;
            }
        }
        if (!(endSkyAtlasOwnTexture.view() instanceof VulkanGpuTextureView vkView)
                || !(endSkyAtlasOwnTexture.sampler() instanceof VulkanGpuSampler vkSampler)) {
            return;
        }
        long imageView = vkView.vkImageView();
        long vkSamplerHandle = vkSampler.vkSampler();
        if (publishedEndSkyAtlasView != imageView
                || publishedEndSkyAtlasSampler != vkSamplerHandle) {
            publishedEndSkyAtlasView = imageView;
            publishedEndSkyAtlasSampler = vkSamplerHandle;
            NativeVulkan.updateEndSkyAtlasTexture(imageView, vkSamplerHandle);
            System.out.println("[ENDSKY] published view=0x" + Long.toHexString(imageView));
        }
    }

    private static final long AURORA_PROBE_INTERVAL_TICKS = 10L;
    private static final float AURORA_SMOOTHING = 0.045f;
    private static final float AURORA_EPSILON = 0.004f;

    private static float auroraStrength;
    private static float auroraTarget;
    private static float auroraPushed = -1.0f;
    private static long auroraProbeTick = Long.MIN_VALUE;
    private static String auroraLoggedProbe;

    private static void updateAuroraStrength() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            auroraTarget = 0.0f;
        } else {
            final long gameTime = minecraft.level.getGameTime();
            if (auroraProbeTick == Long.MIN_VALUE
                    || gameTime - auroraProbeTick >= AURORA_PROBE_INTERVAL_TICKS) {
                auroraProbeTick = gameTime;
                auroraTarget = isAuroraBiome(minecraft) ? 1.0f : 0.0f;
            }
        }
        final float delta = auroraTarget - auroraStrength;
        auroraStrength = Math.abs(delta) < AURORA_EPSILON
                ? auroraTarget
                : auroraStrength + delta * AURORA_SMOOTHING;
        if (Math.abs(auroraStrength - auroraPushed) >= AURORA_EPSILON) {
            auroraPushed = auroraStrength;
            NativeVulkan.setAuroraStrength(auroraStrength);
        }
    }

    private static boolean isAuroraBiome(Minecraft minecraft) {
        final var holder = minecraft.level.getBiome(minecraft.player.blockPosition());
        final boolean taiga = holder.is(BiomeTags.IS_TAIGA);
        final float temperature = holder.value().getBaseTemperature();
        final boolean cold = temperature <= 0.15f;
        final String probe = "taiga=" + taiga + " temp=" + temperature
                + " match=" + (taiga || cold);
        if (!probe.equals(auroraLoggedProbe)) {
            auroraLoggedProbe = probe;
            System.out.println("[RTX-AURORA] biome decision: " + probe);
        }
        return taiga || cold;
    }

    /**
     * Retained as the alternative path: taking the star map out of Minecraft's
     * own TextureManager.  That only resolves when something registers the
     * `orion` namespace with the ResourceManager (Fabric API's resource
     * loader), so it is unused now -- see loadOwnTexture() for the reason.
     */
    @SuppressWarnings("unused")
    private static void publishStarMapTextureFromMinecraftResource() {
        AbstractTexture texture = cachedStarMapTexture;
        if (texture == null) {
            texture = Minecraft.getInstance()
                    .getTextureManager()
                    .getTexture(RT_STAR_MAP_TEXTURE);
            cachedStarMapTexture = texture;
        }
        if (texture == null) {
            return;
        }
        GpuTextureView view = texture.getTextureView();
        GpuSampler sampler = texture.getSampler();
        if (!(view instanceof VulkanGpuTextureView vkView) || !(sampler instanceof VulkanGpuSampler vkSampler)) {
            return;
        }
        long imageView = vkView.vkImageView();
        long vkSamplerHandle = vkSampler.vkSampler();
        if (publishedStarMapView != imageView || publishedStarMapSampler != vkSamplerHandle) {
            publishedStarMapView = imageView;
            publishedStarMapSampler = vkSamplerHandle;
            NativeVulkan.updateStarMapTexture(imageView, vkSamplerHandle);
        }
    }

    private static Quad[] createQuadArray(int capacity) {
        Quad[] heap = new Quad[capacity];
        for (int index = 0; index < heap.length; ++index) {
            heap[index] = new Quad();
        }
        return heap;
    }

    private static void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (nearest[parent].distanceSq >= nearest[index].distanceSq) return;
            swap(index, parent);
            index = parent;
        }
    }

    private static void siftDown(int index) {
        while (true) {
            int left = index * 2 + 1;
            if (left >= nearestCount) return;
            int right = left + 1;
            int largest = right < nearestCount && nearest[right].distanceSq > nearest[left].distanceSq
                    ? right : left;
            if (nearest[index].distanceSq >= nearest[largest].distanceSq) return;
            swap(index, largest);
            index = largest;
        }
    }

    private static void swap(int first, int second) {
        Quad temporary = nearest[first];
        nearest[first] = nearest[second];
        nearest[second] = temporary;
    }

    private static final class Quad {
        private double distanceSq;
        private int atlasSlot;
        private float x, y, z, scale, u0, u1, v0, v1;
        private float xRot, yRot, zRot, wRot;
        private int color;
        private int emissionMarker;
        private float windX, windZ;
        private float dx, dy, dz;

        private void set(double distanceSq, int atlasSlot,
                         float x, float y, float z, float scale, float u0, float u1, float v0, float v1,
                         float xRot, float yRot, float zRot, float wRot, int color, int emissionMarker) {
            this.distanceSq = distanceSq;
            this.atlasSlot = atlasSlot;
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
            this.xRot = xRot;
            this.yRot = yRot;
            this.zRot = zRot;
            this.wRot = wRot;
            this.color = color;
            this.emissionMarker = emissionMarker;
            this.windX = 0.0F;
            this.windZ = 0.0F;
        }
    }
}
