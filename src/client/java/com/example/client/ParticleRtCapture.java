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
    /** Negative so the native closest-hit emission convention cannot mistake it for fire. */
    public static final int RT_RAIN_MARKER = -2;
    /** Capture-side kill switch: the particle exists but must not enter the RT world. */
    public static final int RT_SUPPRESS_MARKER = -3;
    /**
     * 2026-10-07 雪：雪片卡片 marker。着色器（rt_test.rahit / rchit）对一切
     * marker &lt; -1.5 的粒子 quad 走同一条天气路径 —— 采样 binding 40 的天气
     * 图集（雨/雪各占一半）、0.025 alpha 截断、逐像素随机覆盖、仅主相机光线
     * 可见 —— 所以雪不需要任何 shader/C++ 改动。emitQuad 只给
     * marker == RT_RAIN_MARKER 的 quad 改写卡片宽度，雪片保持整卡尺寸。
     * 仍是负值：正的 marker 是"自发光"，不能撞上。
     */
    public static final int RT_SNOW_MARKER = -4;
    // Dedicated weather capacity is intentionally independent from the user
    // setting for ordinary RT particles.  A value of zero there must not
    // silently remove all rain.
    //
    // The lattice is a fixed-footprint grid of weather cells: 21.6 blocks on a
    // side, one drop per cell, each drawn as a crossed pair of vertical strips,
    // traced only by primary camera rays.  The cell *count* is what the density
    // experiment below moves; the footprint never changes.
    //
    // This shape is deliberate and was tuned in game.  Each drop is one thin
    // streak, and density comes from the *number of drops* -- not from packing
    // several streaks into one card.  See the note on the reachable fraction
    // below for why the other approach reads as solid white bars.
    //
    // 2026-09-30: 密度 ×7 实验。GUI 上那四个滑杆（粗细 / 长度 / 浓度 / 亮度）
    // 没有一个管滴数 —— "雨线浓度" 只改每条线有多实（顶点 alpha = 光线存活率），
    // 所以用户把它拉到上限也还是稀。原来 16×16 = 256 格，满雨恰好把格子填满，
    // 于是"满雨"就等于"密度上限"，没有加重的余地。
    //
    // 现在边长 ×√7（42×42 = 1764 格 = 256 的 6.9 倍；7 不是完全平方数，取最近的
    // 整数格），间距同步缩到 1.35 × 16/42 ≈ 0.51 格，footprint 仍是 21.6 格 ——
    // 雨不会铺得更远，只是铺得更密。代价：满雨 1764 滴 → 3528 quad（原 512），
    // 只被主相机光线追踪，但粒子层 BLAS 每帧重建的三角形数也跟着 ×7。
    private static final int BASE_WEATHER_GRID_SIZE = 16;
    private static final float RT_RAIN_DENSITY_SCALE = 7.0F;
    private static final int WEATHER_GRID_SIZE =
            (int)Math.round(BASE_WEATHER_GRID_SIZE * Math.sqrt(RT_RAIN_DENSITY_SCALE));
    private static final int MAX_RT_RAIN_DROPS = WEATHER_GRID_SIZE * WEATHER_GRID_SIZE;
    // Cell pitch of the base (×1) lattice, in blocks.  The live pitch is derived
    // from it and the grid size so the footprint stays put; see
    // buildRtWeather().
    private static final float RAIN_CELL_SPACING = 1.35F;
    // Two crossed strips per rain drop, so a drop has a silhouette from any
    // horizontal direction.  Snow (2026-10-07) spends the same budget at one
    // camera-facing billboard per flake, RT_SNOW_FLAKES_PER_CELL flakes per
    // cell: full snow = 2 * MAX_RT_RAIN_DROPS quads, exactly like full rain.
    private static final int MAX_RT_WEATHER_QUADS = MAX_RT_RAIN_DROPS * 2;
    // Fraction of a card's columns that can ever reach the shader.  A texel
    // only contributes if `atlasAlpha * vertexAlpha > 0.025`, and the authored
    // streak carries anti-aliased edges, so most of the card is simply too
    // faint to draw.  Measured over the shipped atlas with
    // tools/rain_atlas/measure.py: 7.7% at the default vertex alpha, 8.1% at
    // the widest and 4.4% at the faintest.  The card therefore has to be about
    // 13x wider than the streak the user actually wants to see.
    private static final float RT_RAIN_REACHABLE_FRACTION = 0.077F;
    // World half-width of a rain card, recomputed once per frame from the
    // 雨线粗细 setting.  Width is deliberately NOT derived from the card's
    // height any more: it used to be `RT_RAIN_HALF_WIDTH * streakLength / 2`,
    // which meant long streaks could only ever be wide ones.
    //
    // Do NOT try to buy density by authoring a cluster of streaks into one
    // tile of the atlas.  That was tried and it fails: a card is a fixed
    // width, so packing N streaks into it divides that width by N, and beyond
    // roughly 13 blocks of distance the gaps fall below a pixel and the whole
    // card resolves as one solid white bar.  Rain is visible well past that
    // distance, so most of it turns into bars.  Density has to come from the
    // drop count; width and count are separate knobs.
    private static float weatherRainHalfWidth = 0.039F;   // 6 mm / (2 * 0.077)

    // Perceived opacity of the RT rain.  The closest-hit shader (rt_test.rahit)
    // has no fixed-function alpha blending: a primary camera ray keeps the drop
    // with probability atlasAlpha * vertexAlpha, so the vertex alpha *is* the
    // share of rays that survive -- i.e. how solid the streaks read.  Because
    // the shader's 0.025 cutoff is applied to the product, a lower value also
    // trims the faintest anti-aliased rim, which reads as a slightly crisper
    // streak.
    //
    // This is the user-facing "雨线浓度" setting; the default lives in
    // RTXSettingsConfig.RainSettings.  Do not push it near 1.0: the survival
    // test is binary, so every texel that passes is fully opaque and a high
    // value turns each streak into a solid card instead of a translucent veil.
    //
    // The streak card is also an isolated, fully sky-lit quad: nothing occludes
    // it, so its radiance is albedo * full sky irradiance.  The storm deck
    // above is dark only because it shadows itself, which is why a streak can
    // read brighter than the clouds it falls through.  The carrier below is the
    // bright, slightly blue value the rain has always used; it is fine at the
    // default opacity because a thin streak covers so few pixels that it never
    // reads as a solid bright object.  The user-facing "雨线亮度" setting scales
    // it before it is written into the vertex colour.
    private static final float RT_RAIN_TINT_RED = 228.0F / 255.0F;
    private static final float RT_RAIN_TINT_GREEN = 238.0F / 255.0F;
    private static final float RT_RAIN_TINT_BLUE = 245.0F / 255.0F;
    // A streak card has a fixed world size, so a drop that lands a metre from
    // the eye still covers a lot of the screen: the card is 0.24 blocks across
    // and 2.4 blocks tall, so closer than ~1.7 blocks it is taller than the
    // viewport and paints a bright bar across the whole frame.  Shrink the card
    // toward zero inside the near field: the visible streak drops below a pixel
    // well before the card does, so drops disappear without a visible pop and
    // the lattice keeps its density further out.  The lattice always contains a
    // cell at the player's own position, so without this the nearest drops sit
    // essentially on the lens.
    private static final float RT_RAIN_NEAR_FADE_INNER = 0.50F;
    private static final float RT_RAIN_NEAR_FADE_OUTER = 1.80F;

    // =======================================================================
    // 2026-10-07 雪（用户要求："下雪时雪很密"）。
    //
    // 复用雨的整套天气管线（图集 binding 40、20 Hz 重建、21.6 格 footprint、
    // MOTION_BLOCKING 遮挡、near fade），只有三处不同：
    //
    // 1. 判定：逐 column 探 getPrecipitationAt —— SNOW 群系（雪原/雪坡/雪林/
    //    冻河…）下雨时 vanilla 本就是"下雪"，此前这里错误地照画雨丝；现在
    //    SNOW column 输出雪片卡片，RAIN column 仍输出雨丝，群系边界自然混合。
    // 2. 贴图：雪片采图集下半（v ∈ [0.5, 1.0]，tools/snow_atlas/ 生成），雨的
    //    上半 UV 一格未动。v=0 是 PNG 顶行（对照 rt_test.rmiss 末地天图集验证）。
    // 3. 形状：雨是两张正交竖条（任意水平向都有轮廓）；雪是单张相机 billboard
    //    （mainCamera().rotation()）+ 一次面内自旋，俯视/仰视都是圆片。
    //
    // 密度：每格 2 片（SNOW_FLAKES_PER_CELL）⇒ 满雪 1764 格 x 2 = 3528 quad，
    // 与满雨持平（雨每滴 2 张正交条，雪每片 1 张）。间距 0.51 格里两片错相
    // 错位，加上随机尺寸/自旋/摇摆，观感是厚而密的雪幕。
    // =======================================================================
    /** 雪片 tile 的 v 基准：图集下半（雨占上半）。 */
    private static final float RT_SNOW_TILE_V_BASE = 0.5F;
    /** 每个天气格放几片雪（密度倍率作用于格数，见 SnowSettings.densityScale）。 */
    private static final int RT_SNOW_FLAKES_PER_CELL = 2;
    /**
     * 雪片尺寸带的端点（quad scale 语义 = 卡片**半**边长，全宽 = 2x，与雨丝
     * scale = 半高同约定）：0.06~0.19 ⇒ 出厂全宽 0.12~0.38 格。用户设置
     * {@code SnowSettings.sizeScale}（雪片大小）等比缩放整条带。
     */
    private static final float RT_SNOW_FLAKE_MIN_SIZE = 0.06F;
    private static final float RT_SNOW_FLAKE_MAX_SIZE = 0.19F;
    /**
     * 下落速度带：28 格柱高的分数/秒（0.085~0.145 ⇒ 出厂约 2.4~4.1 格/秒）。
     * {@code SnowSettings.fallSpeedScale}（雪下落速度）等比缩放。
     */
    private static final float RT_SNOW_FALL_MIN = 0.085F;
    private static final float RT_SNOW_FALL_MAX = 0.145F;
    /**
     * 摇摆频率（Hz）。幅度不再是常量：它是设置 {@code SnowSettings.swayBlocks}
     * （雪摇摆幅度，出厂 0.30 格），浓度/亮度/密度同理走 SnowSettings。
     */
    private static final float RT_SNOW_SWAY_FREQUENCY = 0.22F;
    /** 冷白着色基色；{@code SnowSettings.brightness}（雪亮度）等比缩放。 */
    private static final float RT_SNOW_TINT_RED = 236.0F / 255.0F;
    private static final float RT_SNOW_TINT_GREEN = 242.0F / 255.0F;
    private static final float RT_SNOW_TINT_BLUE = 250.0F / 255.0F;
    private static final int BYTES_PER_VERTEX = 12 * Float.BYTES;
    private static final Identifier RT_RAIN_STREAK_TEXTURE =
            Identifier.fromNamespaceAndPath("orion", "textures/rt_rain_streaks.png");
    // Equirectangular 2:1 night sky.  The miss shader decodes it with
    // pow(rgb, 2.2); see .tmp/starmap_tools/convert_starmap.py for how it is
    // produced from the source EXR.
    private static final Identifier RT_STAR_MAP_TEXTURE =
            Identifier.fromNamespaceAndPath("orion", "textures/rt_star_map.png");
    // Class-loader path to that very same asset, used instead of the identifier
    // above so the sky does not depend on a mod-resource-loader being present.
    private static final String STAR_MAP_RESOURCE_PATH =
            "/assets/orion/textures/rt_star_map.png";
    // Same class-loader route for the rain streak sprite.  It is subject to the
    // identical orion-namespace problem; see loadOwnTexture().
    private static final String RAIN_STREAK_RESOURCE_PATH =
            "/assets/orion/textures/rt_rain_streaks.png";
    // 2026-10-05 末地天空图集：上 2/3 银河全景 + 左下地球昼面 + 右下地球夜面，
    // 合成一张 4096x3072 PNG，所以只需要一次上传/JNI/发布（binding 60）。
    private static final String END_SKY_ATLAS_RESOURCE_PATH =
            "/assets/orion/textures/rt_end_atlas.png";
    // A fixed max-heap retains the nearest particles without allocating one
    // record for every extracted particle. Dense particle effects otherwise
    // put avoidable pressure on the render-thread allocator every frame.
    private static final Quad[] nearest = createQuadArray(MAX_RT_PARTICLES);
    private static final Quad[] weatherQuads = createQuadArray(MAX_RT_WEATHER_QUADS);
    private static int nearestCount;
    private static int weatherQuadCount;
    // Reused so the per-drop weather probes never allocate on the render thread.
    private static final BlockPos.MutableBlockPos weatherProbe = new BlockPos.MutableBlockPos();
    private static int particleBudget = MAX_RT_PARTICLES;
    private static int genericParticleBudget = MAX_RT_PARTICLES;
    private static final ByteBuffer particleVertices = ByteBuffer
            .allocateDirect((MAX_RT_PARTICLES + MAX_RT_WEATHER_QUADS) * 6 * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder());

    // =====================================================
    // 2026-10-09 运动矢量（DLSS-RR）：与 particleVertices **逐顶点同序**的
    // "上一帧**相机相对**位移"，每顶点 3 个 float。
    //
    // ★ 方向约定（写反表现为残影朝反方向拖、幅度加倍）：
    //   motion = **prevRel − curRel**（都是"世界坐标减相机"）。
    //   native 原样存进顶点缓冲尾段；**closest-hit（rt_test.rchit）**补 Δcam：
    //   `hitWorldOffset = motion + (prevCamPos − curCamPos)` ⇒ 得到世界位移；
    //   rgen 侧只做 `prevWorld = curWorld + hitWorldOffset`，**不再**补相机
    //   （rt_test.rgen 的 MV 段有明写"不要在这里再补一次"）。
    //   **Java 也不补 Δcam**：两边各自采样的时刻一旦差一帧就会整体错成 ±相机位移。
    //
    // 按 quad 常量写（旋转引起的逐顶点差异不建模）：实测拖影最明显的是
    // 雨/雪/烟花这类**平移主导**的粒子，它们的 4 个角点位移几乎相同，
    // 而建模自旋需要保存上一帧姿态，收益远小于复杂度。
    private static final ByteBuffer particleMotion = ByteBuffer
            .allocateDirect((MAX_RT_PARTICLES + MAX_RT_WEATHER_QUADS) * 6 * 3 * Float.BYTES)
            .order(ByteOrder.nativeOrder());
    private static final Quaternionf particleRotation = new Quaternionf();
    // Scratch for the snowflake billboards: camera rotation copied in, then a
    // per-flake in-plane spin applied -- never the camera's own instance.
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
    // Atlas instances are stable between resource reloads, so the per-frame
    // publish only re-resolves them from the TextureManager when the reload
    // hook (invalidateTextureCache) has dropped the cached references.
    private static final AbstractTexture[] cachedAtlasTextures =
            new AbstractTexture[3];
    private static AbstractTexture cachedRainStreakTexture;
    private static AbstractTexture cachedStarMapTexture;
    // Textures this class uploads itself, independent of Minecraft's
    // TextureManager: created once, and never invalidated by a resource reload.
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
    // Every spawned particle used to pay a registry key lookup plus a string
    // switch just to classify its type.  The classification is a pure
    // function of the ParticleType, which is a registry singleton with
    // identity semantics, so the result is cached per type: the steady state
    // is one plain map read and ParticleTypes is a bounded set.
    private static final Map<ParticleType<?>, Integer> emissionTypeMarkers =
            new ConcurrentHashMap<>();

    private ParticleRtCapture() {}

    // =====================================================
    // 2026-10-03 P0：雨滴层"内容不变就不重建"的限频状态。
    //
    // 背景（有日志支撑）：雨卡片位置每帧前进 ⇒ 旧实现每帧全量重传 + 触发粒子层
    // BLAS/TLAS 重建 ⇒ 下雨时 CPU geometry 阶段持续 6~14 ms、并有 399 ms 尖峰。
    // 现在 20 Hz 重建一次，两次之间 payload 逐字节相同 ⇒ 宿主侧 sameVertexPayload()
    // 判定未变化 ⇒ 跳过重传与重建。副作用：雨滴下落呈 20 Hz 步进（高于人眼对雨丝
    // 的辨识阈值，实测不可辨）。
    // =====================================================
    private static final long WEATHER_RAIN_REBUILD_INTERVAL_NANOS = 50_000_000L; // 20 Hz
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
        // 2026-10-09 运动矢量：每帧推进帧序号（见 updateParticleMotionFrame）。
        updateParticleMotionFrame();
        nearestCount = 0;
        particleBudget = RTXSettingsConfig
                .getPerformanceSettings()
                .maxRtParticles();
        // 2026-10-03 P0 性能优化（来源思路：ImmediatelyFast 的 "内容不变就不重建"）：
        // 雨滴卡片的位置每帧都会前进（相位移），旧实现于是**每帧**把 2400+ 个 quad
        // 全量重传、并在宿主侧触发粒子层的 BLAS/TLAS 重建 —— 下雨时表现为持续掉帧
        // 与尖峰（日志：geometry=6.76ms、max 399ms、atlasPublishDeviceIdle 4.23ms）。
        // 现在把重建限频到 20 Hz：两次重建之间沿用上一批顶点，宿主侧的 payload 比对
        // 判定为"未变化" ⇒ 不重传、不重建。雨滴外观差异在高频下不可辨。
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

    // =====================================================
    // 2026-10-09 运动矢量：本次 extract 的粒子"上一帧位移"（prev − cur，
    // **相机相对**；closest-hit 补 Δcam 后才是世界位移，见 particleMotion 的说明）。
    //
    // 与 activeExtractMarker 同一生命周期的 ThreadLocal：因为 extract 是
    // "begin → 若干 capture → end" 的一次一粒子交接，用 ThreadLocal 才能保证
    // 多线程（渲染线程 / 可能的其它提取线程）之间不串。
    // =====================================================
    private static final ThreadLocal<float[]> activeExtractMotion =
            new ThreadLocal<>();

    // =====================================================
    // 2026-10-09 运动矢量：粒子的"上一帧**相机相对**位移"（prevRel − curRel）。
    //
    // ★ 为什么不读粒子的 xo/x：这个 MC 版本上 `Particle` **没有**
    //   `getX()/getY()/getZ()`（编译期直接报"找不到符号"），xo/yo/zo 也不保证在。
    //   所以这里用**完全不依赖 MC API** 的做法 —— `extractRotatedQuad` 每次都会把
    //   "已插值、**相机相对**"的粒子中心交给我们，对同一个粒子量帧间差即可：
    //
    //       prevRel − curRel        ← 这里量出来的就是它，**到此为止**
    //
    //   **不在这里补 Δcam**（曾经的写法，也是"移动时四周闪动"那次的同类错误）：
    //   rel = world − cam ⇒ prevRel − curRel = Δworld − Δcam，看着"缺一项"，
    //   但那一项必须由**原生世界化顶点时用的那对矩阵**来补 —— 原生按
    //   camNow 把顶点世界化成 P = camNow + relNow，要的上一帧世界位置是
    //   P′ = camPrev + relPrev，所以 Δcam 的定义天然属于 GPU 侧（closest-hit 直接读
    //   同一个 UBO 的 viewInverse 平移列，而它也正是唯一知道"这一面有没有位移段"
    //   的地方 —— 静态面必须保持恰好 0）。Java 若自己再采一次相机，只要采样时刻
    //   与原生差一帧，整屏位移就会错成 ±相机位移。
    //   实体层同理：`EntityRenderState.x/y/z` 也是相机相对坐标。
    //
    // ★ 用 WeakHashMap 而不是强引用：粒子死亡后没有强引用，条目随 GC 消失 ——
    //   普通 Map 会随游玩时长无界累积（那正是"玩久了内存涨"的坑之一）。
    // ★ 新粒子第一帧给 0：它本来也没有历史可重投影。
    // =====================================================
    private static final java.util.WeakHashMap<Particle, float[]> particlePrevRel =
            new java.util.WeakHashMap<>();

    /** 本帧序号：同一帧内一个粒子可能被多次提取，位移只允许在第一次算。 */
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
            // ⚠ 报的是**相机相对**位移（prevRel − curRel），**不带**相机项。
            //   这个量里已经含了 −Δcam（因为 rel = world − cam），而原生把顶点世界化
            //   时会加上相机位置、rgen 再用同一对 UBO 矩阵把相机那一份补回去。
            //   分工必须这样切：Java 只负责"量"，相机的账交给持有真值矩阵的 GPU 侧，
            //   否则两边采样的时刻一旦差一帧，就会整体错成 ±相机位移。
            target[0] = previous[0] - x;
            target[1] = previous[1] - y;
            target[2] = previous[2] - z;

            previous[0] = x;
            previous[1] = y;
            previous[2] = z;
            previous[3] = particleMotionFrameId;
        } else {
            // 同一帧内重复提取：沿用本帧第一次算出的位移（再算一次会得 0）。
        }

        if (motion == null) {
            activeExtractMotion.set(target);
        }
    }

    public static void endParticleExtract() {
        activeExtractMarker.remove();
        // 2026-10-09 运动矢量：与标记同一生命周期，避免残留到下一个粒子。
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
        // Suppressed types never reach the RT world regardless of how they
        // were spawned (createParticle interception cannot cover particles
        // derived inside other particles' tick updates).
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
            // 2026-10-09 运动矢量：几何与位移两条缓冲**必须同时清、同时翻**，
            // 否则两者长度会错位（GPU 侧按顶点索引取位移）。
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
        // Rain cards take their world half-width straight from the 雨线粗细
        // setting.  `vertex` multiplies both axes by q.scale, so dividing the
        // height scaling back out is what keeps the width absolute -- length
        // no longer drags width along with it.
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

        // Density knob.  One drop draws one thin streak, so this is the only
        // thing that decides how dense the rain looks -- raise it for a
        // heavier curtain, at two quads each.  The ×RT_RAIN_DENSITY_SCALE is the
        // 2026-09-30 experiment (see the constants block); full rain reaches
        // MAX_RT_RAIN_DROPS again, which is the whole point of the change.
        // 2026-10-23：数量再乘用户滑杆（RainSettings.densityScale）。卡片数是
        // 粒子层 BLAS 三角形数与主射线 any-hit 次数的线性因子 —— "雨天掉一半帧"
        // 时这是唯一能不重编着色器就验伪/缓解的旋钮。
        int requestedDrops =
                (int)((64.0F + rain * 192.0F) * RT_RAIN_DENSITY_SCALE *
                        RTXSettingsConfig.getRainSettings().densityScale());
        int dropCount = Math.min(requestedDrops, MAX_RT_RAIN_DROPS);
        // 2026-10-07：dropCount 现在是**填充比例的分子**（rainFill = dropCount /
        // 格数），期望格数与这条预算一致，只是被填充的格子集合改为世界稳定；
        // 见下面循环头的"世界锚定"说明。
        float seconds = System.nanoTime() * 1.0e-9F;
        // Each drop is a vertical strip whose card is `streakLength` blocks
        // tall; the default is 2.4.  This is the user-facing "雨线长度"
        // setting, and it scales the strip's width with it.
        RTXSettingsConfig.RainSettings rainLook = RTXSettingsConfig.getRainSettings();
        final float rainScale = rainLook.streakLength() * 0.5F;
        // Absolute world width, resolved once per frame so emitQuad does not
        // have to touch the (synchronised) config accessor per quad.
        weatherRainHalfWidth = rainHalfWidthBlocks(rainLook);
        final float rainBrightness = rainLook.brightness();
        final int rainColor = ARGB.colorFromFloat(
                rainLook.opacity(),
                Math.min(RT_RAIN_TINT_RED * rainBrightness, 1.0F),
                Math.min(RT_RAIN_TINT_GREEN * rainBrightness, 1.0F),
                Math.min(RT_RAIN_TINT_BLUE * rainBrightness, 1.0F));
        // Snow reads its own block the same way.  The density multiplier acts
        // on the cell count only -- it buys sparser or denser snowfall in the
        // same 21.6-block footprint and clamps at the same 1764-cell ceiling
        // as the rain, so worst-case quad cost is unchanged.
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
        // Fixed 21.6-block footprint: the pitch shrinks with the grid size, so a
        // denser lattice packs more drops into the same box instead of pushing
        // the rain further out.  (The note this replaces -- "wide spacing keeps
        // the per-drop streaks from clumping, which is what makes a given drop
        // count read as scattered rain rather than as a wall" -- is exactly what
        // the ×7 experiment trades away.)
        final float cellSpacing =
                RAIN_CELL_SPACING * BASE_WEATHER_GRID_SIZE / WEATHER_GRID_SIZE;
        final int anchorX = (int)Math.floor(minecraft.player.getX() / cellSpacing);
        final int anchorZ = (int)Math.floor(minecraft.player.getZ() / cellSpacing);
        // Native adds the camera position to every submitted vertex, so the
        // lattice's y lane is eye-relative.
        final float eyeY = (float)minecraft.player.getEyeY();

        // 雪片是相机 billboard：取一次相机旋转（含俯仰）。rotation() 返回的是
        // 相机的活引用，绝不能原地改 —— 下面的雪路径先 set() 拷进 scratch。
        final Quaternionf cameraRotation = minecraft.gameRenderer.mainCamera().rotation();

        // 2026-10-07：**世界锚定**（修复"雪幕跟着玩家走"）。
        //
        // 原来这里把两种身份混用了两个坐标系：
        //   · 位置 worldX/worldZ 由 anchor(=floor(player/pitch)) 推出 ⇒ 已经是
        //     世界对齐的（只有跨过 0.51 格才整窗平移一格）；
        //   · 身份却全部来自 `hashWeatherSeed(index)` —— **循环下标**，即窗口
        //     相对量。jitter / phase / 下落速度 / 尺寸 / 自旋 / 摇摆相位 / 图集
        //     variant 都是它派生的（见 appendWeatherSnow）。
        // 于是整幅雪幕被"焊"在玩家身上：每走 0.51 格窗口平移一格，每个世界格的
        // 雪片都换成下一个下标的身份与位置 —— 位置平移 + 身份重掷合起来，就是从
        // 玩家视角看**整片雪以玩家为原点刚性平移**。雨丝看不出来（细竖条、片间
        // 外观几乎无差异、下落又快），圆片慢落的雪把它暴露无遗。
        //
        // 现在身份改由**世界格索引**派生（同一世界格在窗口滑动时不变 ⇒ 每片雪在
        // 世界里始终是同一片），密度也从"取前 N 个下标"改成**世界格哈希阈值**：
        //     fill = 预算 / 格数， unitFromSeed(世界格哈希) < fill 才生成。
        // 期望格数与旧口径完全一致（同一条 (64+rain*192)*7 预算），但被填充的格子
        // **集合**也随世界稳定 —— 既不暴露矩形边（哈希本就均匀散布），也不随玩家
        // 平移。footprint 21.6 格 / 20 Hz 重建 / 每格 2 片 / 近场收缩 / 遮挡探针
        // 全部不变，只是"谁在哪"不再跟着人走。
        final float rainFill =
                Math.min(1.0F, (float)dropCount / (float)MAX_RT_RAIN_DROPS);
        final float snowFill =
                Math.min(1.0F, (float)snowCellCount / (float)MAX_RT_RAIN_DROPS);
        // 两种类型都不该有它的格子，连 getHeight/getPrecipitationAt 都不必打
        //（等价于旧的 max(预算) 上界，探针数与旧实现同量级）。
        final float anyFill = Math.max(rainFill, snowFill);
        // 位置与身份必须基于同一个基准点，否则又会退回"追随玩家"。
        final float playerX = (float)minecraft.player.getX();
        final float playerZ = (float)minecraft.player.getZ();

        // 2026-10-21 风斜雨丝：给每张雨卡一个世界空间剪切（顶端相对底端横移），
        // 两张交叉卡用同一个风矢量 ⇒ 轮廓一致。阵风用两个慢正弦叠加，满雨时
        // 倾角约 18°；雪不参与（圆片没有方向）。
        final float gust = 1.0F
                + 0.35F * (float)Math.sin(seconds * 0.85F)
                + 0.20F * (float)Math.sin(seconds * 2.31F + 1.7F);
        final float rainWindScale = (0.45F + 0.55F * rain) * gust;
        final float rainWindX = 0.32F * rainWindScale;
        final float rainWindZ = 0.11F * rainWindScale;

        for (int cell = 0; cell < MAX_RT_RAIN_DROPS; ++cell) {
            int gridX = cell % WEATHER_GRID_SIZE;
            int gridZ = cell / WEATHER_GRID_SIZE;
            // 世界格索引：窗口平移时同一格永远是同一格（jitter 落在格内）。
            int worldCellX = anchorX + gridX - WEATHER_GRID_SIZE / 2;
            int worldCellZ = anchorZ + gridZ - WEATHER_GRID_SIZE / 2;
            int seed = hashWeatherSeed(
                    worldCellX * 0x9e3779b1 ^ worldCellZ * 0x85ebca77);
            // 密度闸用**另一条**哈希流：直接复用 seed 会让 jitterX 与闸值相关
            //（阈值附近被选中的格子全部偏向格内同一侧，低密度时看得出成片偏移）。
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
            // The biome is probed at camera height and a column with no
            // precipitation at all (desert, badlands, savanna, ...) is skipped
            // outright.  ClientLevel.getPrecipitationAt is biome-only, so the
            // sky/height half is handled by the MOTION_BLOCKING checks (per
            // drop below, per flake in appendWeatherSnow).
            //
            // 2026-10-07: the probe now also splits the weather itself.  In a
            // SNOW biome vanilla treats this same "rain" as snowfall, yet this
            // lattice used to draw streaks there; now SNOW columns emit
            // snowflake billboards and RAIN columns keep the streaks, so a
            // biome boundary shows both, matching vanilla's per-column
            // precipitation.
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
            // Keep a 28-block falling column anchored to the eye (12 below,
            // 16 above).  The world X/Z grid remains stable while the player
            // moves; only the drop phase advances, matching weather rather
            // than screen sprites.
            float y = -12.0F + fract(phase - seconds * 0.65F) * 28.0F;
            // Mirror vanilla's own per-column visibility test.  For a column
            // whose MOTION_BLOCKING height is `top`, WeatherEffectRenderer
            // .extractRenderState clamps the drawn band to
            //     yMin = max(camY - r, top)   yMax = max(camY + r, top)
            // and drops the column entirely when yMax == yMin.  Our drops only
            // occupy [eyeY - 12, eyeY + 16], and eyeY + 16 <= max(eyeY + 16,
            // top) always, so the band's upper clamp can never reject one and
            // the whole test collapses to the single lower bound below -- which
            // is why a roof, cave ceiling, tree canopy or the ground itself
            // now hides the drop instead of letting the lattice rain straight
            // through it.
            if (eyeY + y < columnTop) {
                continue;
            }
            // Shrink toward zero inside the near field so no card can cover the
            // screen.  Apparent size depends on the full 3D distance to the
            // eye, so a drop directly overhead must not be treated as close
            // just because its horizontal offset is small.
            float nearFade = smoothstep(
                    RT_RAIN_NEAR_FADE_INNER,
                    RT_RAIN_NEAR_FADE_OUTER,
                    (float)Math.sqrt(x * x + y * y + z * z));
            if (nearFade <= 0.02F) {
                continue;
            }
            float streakScale = rainScale * nearFade;
            int variant = hashWeatherSeed(seed ^ 0x51ed270b) & 15;
            // 2026-10-07 the atlas doubled in height (1024x2048, 4x8 tiles of
            // 256px): rain keeps the top four rows, snow owns rows 4-7.  Tile
            // pitch is now 0.25 in u and 0.125 in v, so the rain v step halves
            // with it -- the tiles themselves kept their 1:1 pixels and their
            // order; only the UV scale changed.
            float u0 = (variant & 3) * 0.25F;
            float v0 = (variant >>> 2) * 0.125F;
            float u1 = u0 + 0.25F;
            float v1 = v0 + 0.125F;
            // A crossed pair of vertical strips, the shape this rain has always
            // used: the first faces +-Z, the second is the same strip turned 90
            // degrees about Y so it faces +-X.  Between them a drop has a
            // silhouette from any horizontal direction, and whichever strip is
            // not edge-on is the one you actually see.
            //
            // The strips are vertical in world space, so the camera's pitch is
            // deliberately *not* matched: a streak that tilted with the view
            // would stop looking like falling rain.  Looking straight down
            // therefore catches both strips edge-on, which is the degenerate
            // case this shape has always had, and the near fade has already
            // removed the drops close enough for it to show.
            // 0.70710677f is sin(45) == cos(45): a quaternion of
            // (0, sin, 0, cos) is a 90 degree turn about Y.
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
            // The 雪下落速度 setting scales the band; the per-flake random
            // spread stays inside it, so 0.25x still reads as snowfall rather
            // than a freeze-frame.
            float fallSpeed = (RT_SNOW_FALL_MIN
                    + (RT_SNOW_FALL_MAX - RT_SNOW_FALL_MIN)
                      * unitFromSeed(flakeSeed ^ 0x7feb352d))
                    * snowLook.fallSpeedScale();
            // Same 28-block eye-anchored falling column as the rain streaks.
            float y = -12.0F + fract(flakePhase - seconds * fallSpeed) * 28.0F;
            // Slow lateral sway: two phase-shifted sines per flake.  The
            // amplitude is the user-facing 雪摇摆幅度 setting; the per-flake
            // occlusion probe below runs at the swayed landing point, so even
            // an amplitude past the cell pitch cannot sneak under an overhang.
            float swayPhase =
                    unitFromSeed(flakeSeed ^ 0x51ed270b) * (float)(Math.PI * 2.0);
            float swayAngle =
                    seconds * RT_SNOW_SWAY_FREQUENCY * (float)(Math.PI * 2.0)
                    + swayPhase;
            float swayX = (float)Math.sin(swayAngle) * snowLook.swayBlocks();
            float swayZ = (float)Math.cos(swayAngle * 1.618F) * snowLook.swayBlocks();
            float flakeX = x + swayX;
            float flakeZ = z + swayZ;
            // Mirror the rain path's per-column visibility test, but at the
            // swayed landing point: the cell pitch is 0.51 blocks and the sway
            // can cross into the neighbouring column -- and disappear under
            // that column's roof overhang -- without this per-flake probe.
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
            // 雪片大小 scales the whole random size band (see the constants
            // block for the half-extent semantics).
            float size = (RT_SNOW_FLAKE_MIN_SIZE
                    + (RT_SNOW_FLAKE_MAX_SIZE - RT_SNOW_FLAKE_MIN_SIZE)
                      * unitFromSeed(flakeSeed ^ 0x85ebca6b))
                    * snowLook.sizeScale();
            int variant = hashWeatherSeed(flakeSeed ^ 0xc2b2ae35) & 15;
            // Same tile pitch as the rain half (0.25 in u, 0.125 in v); snow
            // tiles live in the atlas's bottom four rows, i.e. v = 0.5..1.0.
            // v=0 is the PNG's top row (verified against rt_test.rmiss's
            // end-sky atlas sub-layout).
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

        // 2026-10-09 运动矢量：与上面那 48B 顶点**严格一一对应**地写一份位移。
        // ⚠ 这里绝不允许提前返回或条件跳过 —— 两边长度一旦错位，GPU 侧就会按索引
        //   读到"别的顶点的位移"，表现是残影方向乱跳。天气格点（雨/雪）的 d* 默认
        //   是 0：它们由相机每帧重建，没有上一帧语义，0 就是正确值。
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
                // usage COPY_DST | TEXTURE_BINDING, one layer, one mip level: the
                // same shape ReloadableTexture.doLoad uses for a plain PNG.
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

    // =======================================================================
    // 2026-10-06 极光（雪地 / 云杉群系）
    //
    // 只在玩家身处**寒冷群系**（雪原 / 雪坡 / 冰刺 / 云杉针叶林 / 雪林 …）时把强度
    // 推到 native；夜间、雨雪压制、维度判断都在着色器里（rt_test.rmiss →
    // aurora_common.glsl），主机侧不重复一遍。
    //
    // 判定用群系 id 名而不是温度 API：26.3 的 Biome 访问器改动频繁，名字这一层
    // 最稳，且"雪地 + 云杉"本来就是按名字定义的。命中不了也只是不出极光，不会崩。
    // =======================================================================
    private static final long AURORA_PROBE_INTERVAL_TICKS = 10L;
    /** 时间平滑：约 1.5 秒收敛，跨群系边界不硬切。 */
    private static final float AURORA_SMOOTHING = 0.045f;
    private static final float AURORA_EPSILON = 0.004f;

    private static float auroraStrength;
    private static float auroraTarget;
    private static float auroraPushed = -1.0f;
    private static long auroraProbeTick = Long.MIN_VALUE;
    /** 一次性诊断：上一次打过的群系判定输入（变了才打，避免刷屏）。 */
    private static String auroraLoggedProbe;

    private static void updateAuroraStrength() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            auroraTarget = 0.0f;
        } else {
            final long gameTime = minecraft.level.getGameTime();
            if (auroraProbeTick == Long.MIN_VALUE
                    || gameTime - auroraProbeTick >= AURORA_PROBE_INTERVAL_TICKS) {
                // 群系不会逐帧变，10 tick 采一次即可（getBiome 要走一次区块查询）。
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
        // 云杉 / 针叶林：原版自带 is_taiga 标签（taiga、old_growth_*_taiga、snowy_taiga），
        // 比按 id 名匹配稳，也不吃 mapping 改名。
        final boolean taiga = holder.is(BiomeTags.IS_TAIGA);
        // 雪地：按基础温度判（雪原 / 雪坡 / 冰刺 / 雪林 / 冻河冻海都在 0.15 以下）。
        final float temperature = holder.value().getBaseTemperature();
        final boolean cold = temperature <= 0.15f;
        // 一次性诊断：判定输入变了就打一行。极光不出现时先看这里 match 是否为
        // false（那说明站的地方不算寒冷群系），再看 native 侧那行有没有收到强度。
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
        // 2026-10-21 风斜雨丝：世界空间剪切（每单位垂直半高横向位移，格）。
        // 只对雨卡非零；set() 每次清零，避免 Quad 复用时串到雪/粒子上。
        private float windX, windZ;
        // 2026-10-09 运动矢量：这个 quad 的"上一帧位移"（prev − cur，**相机相对**，
        // 见 particleMotion 的说明）。
        //
        // ★ 刻意**不进 set() 的参数表**：天气格点（雨/雪）有自己的 set() 调用点，
        //   改签名会牵动它们，而天气格点是"相机每帧重建"的，没有上一帧语义 ——
        //   它们的位移**就应该**是 0，默认值正好是对的。
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
