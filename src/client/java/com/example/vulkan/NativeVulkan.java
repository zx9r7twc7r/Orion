package com.example.vulkan;

import org.lwjgl.vulkan.VK;
import com.example.client.config.RTXSettingsConfig;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;



public final class NativeVulkan {// 新签名：传递区块坐标，顶点缓冲区的物理内存地址，以及顶点的总数量

    // === 核心扩容：注入时序与抖动数据通道 ===
    public static native void updateCamera(
            float[] viewInvArray,
            float[] projInvArray,
            int frameCount,
            float cameraNear,
            float cameraFar,
            float cameraFov,
            float overworldTimePhase,
            float rainLevel,
            float thunderLevel,
            float dimensionCode,
            float playerX,
            float playerY,
            float playerZ,
            float playerVelocityX,
            float playerVelocityY,
            float playerVelocityZ,
            float playerInWater,
            float waterSurfaceY,
            float cameraInWater
    );

    /**
     * 2026-10-17 实体交互波（掉落物/船）：一次性脉冲入队。(x, z) 是世界坐标，
     * strength 带符号（正 = 入水挤压、负 = 离水回填）。C++ 侧 FIFO（≤64 条），
     * 30 Hz 模拟 tick 时逐条消费进波场高度步。
     */
    public static native void queueWaterImpulse(float x, float z, float strength);

    /**
     * 2026-10-17 实体交互波（船）：每帧整批替换持续移动波源列表。
     * {@code data} 每条船 5 个 float（x, z, vx, vz, 幅值倍率），{@code count} ≤ 8。
     */
    public static native void updateWaterBoats(float[] data, int count);

    public static native void uploadPBRAtlases(long normalPointer, long specularPointer, int width, int height);

    public static native void beginEntityFrame();

    /**
     * 2026-10-09 运动矢量（DLSS-RR）：{@code motionPointer} 是**可选**的位移通道，
     * 每顶点 3 个 float（{@code prev − cur}，<b>相机相对</b>坐标 —— 见 native 侧
     * {@code SectionGeometrySplit::entityMotion} 的方向约定；closest-hit 补 Δcam
     * 之后才是世界位移，所以 <b>Java 不要自己补相机</b>）。
     *
     * <p>2026-10-17 起实体层传的是<b>逐顶点</b>位姿增量展开成的位移（含整体旋转，
     * 不只根平移）；粒子层按粒子逐个上报。{@code 0} 表示"这一批没有位移"：native
     * 会把该段的位移补 0（而不是沿用上一帧的旧值）。</p>
     */
    public static native void uploadEntityBatch(int textureSlot, long vertexPointer, int vertexCount, int vertexStride, long motionPointer);
    public static native void endEntityFrame();
    public static native void updateEntityTexture(int textureSlot, long imageView, long sampler);

    /**
     * 2026-10-09 实体 LabPBR（`_n`/`_s`）：与 {@link #updateEntityTexture} 同槽、
     * 同批推送，区别只是一次带两张贴图的句柄。
     *
     * <p>{@code 0} 表示"这一槽没有该贴图"：native 侧会把它绑回中性 1×1 兜底
     * （normal = (0,0,0,1)、specular = (0,0,0,1)），于是着色器的内容判据自动判成
     * "无贴图"、回落类别值。所以<b>槽被复用</b>时必须显式推 0，不能"什么都不推"。</p>
     */
    public static native void updateEntityPbrTexture(
            int textureSlot,
            long normalView, long normalSampler,
            long specularView, long specularSampler
    );
    public static native void beginParticleFrame();
    /** 同 {@link #uploadEntityBatch} 的 {@code motionPointer} 约定。 */
    public static native void uploadParticleBatch(long vertexPointer, int vertexCount, int vertexStride, long motionPointer);
    public static native void endParticleFrame();

    /**
     * 2026-10-07 动态光源（移动光源）：每帧上报 ≤32 条光源记录，走 RT 描述符集的
     * binding 61（STORAGE_BUFFER，头部自带条数，所以不需要新的 CameraUBO 车道）。
     *
     * <p>记录格式 32 字节：{@code float worldX, worldY, worldZ, emission} +
     * {@code int typeCode, pad, pad, pad}；{@code emission} 是 0..15 的方块光照等级，
     * 颜色由 {@code typeCode} 走既有调色板（{@code vliDecodeEmitterColor}）。</p>
     *
     * <p>{@code recordCount = 0} 表示本帧没有移动光源；内容逐字节没变时 native 侧
     * 整段跳过上传。</p>
     */
    public static native void uploadDynamicLights(long recordPointer, int recordCount, float intensity);
    public static native void updateParticleTexture(int textureSlot, long imageView, long sampler);
    public static native void updateRainStreakTexture(long imageView, long sampler);
    public static native void updateStarMapTexture(long imageView, long sampler);
    public static native void updateEndSkyAtlasTexture(long imageView, long sampler);
    /**
     * 2026-10-06 极光强度 [0,1]。由主持端按玩家所处生物群系（寒冷 / 雪地 / 云杉
     * 针叶林）算出后推来，写进 CameraUBO.auroraParams.x。夜间 / 雨雪 / 维度的
     * 门控在 rt_test.rmiss 里，这里只给强度。
     */
    public static native boolean setAuroraStrength(float value);
    /**
     * 2026-10-10 极光亮度倍率 [0,3]。叠加在生物群系强度之上，写进
     * CameraUBO.auroraParams.y（evaluateAurora 里 `x * y`）。0 = 关，1 = 现网观感。
     */
    public static native boolean setAuroraBrightness(float value);
    /**
     * 2026-10-10 极光流动速度倍率 [0,4]。写进 CameraUBO.auroraParams.z（帘幕时间步
     * 的乘数）。0 = 凝固，1 = 现网观感。
     */
    public static native boolean setAuroraFlowSpeed(float value);
    public static native boolean uploadVoxyLodAtlas(byte[] pixels, int size, int mipLevels);

    // === 新增：物理句柄暂存区 ===
    public static native void updateAtlas(long imageView, long sampler);
    public static long pendingInstanceHandle = 0L;

    /**
     * Distant Horizons 远场纹理句柄（color / depth / blockRatioAtlas）。
     *
     * <p>三个句柄取自 DH 自己的 Blaze3D 纹理包装，与我们的 RT 共用同一个 VkDevice
     * （DH 在 MC 26.3 上走 renderpearl 的 VULKAN 后端）。{@code dhDevice} 是 DH 纹理
     * 自己记录的 {@code VkDevice}，C++ 侧只做<b>纯指针比较</b>——不产生任何 Vulkan
     * 调用，所以即便句柄无效也不会影响驱动。</p>
     *
     * @return 这套句柄是否与我们的设备同源、可以直接用于 descriptor。
     *         返回 false 时不要指望 C++ 会用它们（细节见 run/rt_debug.log 的 {@code [DH TEX]} 行）。
     */
    public static native boolean publishDhTextures(
            long colorImage, long colorView, long colorSampler,
            long depthImage, long depthView, long depthSampler,
            long atlasImage, long atlasView, long atlasSampler,
            long dhDevice, int width, int height);

    /**
     * 只推 DH 的 {@code blockRatioAtlas} 句柄（远场顶点纹理采样用，rchit binding 49）。
     *
     * <p>与 {@link #publishDhTextures} 分开的理由：那个要求 color/depth 成套，而
     * color/depth 只在 DH 自己渲染时才创建。我们把 {@code rendererMode} 留在
     * {@code DISABLED}（几何已被我们接管进 TLAS），它们永远不会出现；atlas 则由 Java
     * 侧主动调 {@code BlazeBlockTextureAtlas.uploadPendingTiles()} 建出来
     * （见 {@code DhTextureHandles}），只能单独推。</p>
     *
     * @return 这张 atlas 是否与我们的设备同源、可以直接绑进 binding 49。
     */
    public static native boolean publishDhBlockRatioAtlas(
            long atlasImage, long atlasView, long atlasSampler,
            long dhDevice);

    /**
     * DH stage B：每帧推入 DH 的逆 MVP 与远场阶段开关（24 个 float，布局见 C++）。
     *
     * <p>纯状态拷贝：C++ 侧只写自己的全局变量，下一帧由 {@code updateCamera}
     * 填进 CameraUBO，<b>不产生任何 Vulkan 调用</b>。全零矩阵 = "失效"信号
     * （换世界时由 {@code DhFarFieldGeometry.invalidate()} 发出），远场随即回落到
     * stage-1 的直通。</p>
     *
     * @return 载荷是否被接受（长度、全零矩阵与 native 是否加载都会影响它）。
     */
    public static native boolean updateDhFarField(
            float[] payload);

    public static native boolean setPOMDepth(
            float value
    );

    public static native float getPOMDepth();

    /**
     * 2026-10-18 视差高度场采样（设置界面按钮）。
     *
     * <p>{@code false = 平滑}（双线性高度场，默认 / 现状）；{@code true = 锐利}
     * （单纹素 texelFetch）。只影响不透明地形 POM 的高度场采样，不动位移 / 深度 /
     * 自阴影。纯状态拷贝：C++ 只写全局变量，下一帧 {@code updateCamera} 填进
     * CameraUBO.pomParams.x，不产生任何 Vulkan 调用。</p>
     */
    public static native boolean setPOMSharp(
            boolean sharp
    );

    public static native boolean getPOMSharp();

    /**
     * 2026-10-25 近场 GI 采样率：0 = 全速 / 1 = 半速 / 2 = 四分之一。
     *
     * <p>跳过的是 GI **信号**（独立随机位 + 权重补偿的无偏时间估计量），像素仍然
     * 每帧全写 ⇒ 不引入几何锯齿。代价是 GI 方差随档位放大（半速约 1.4×、
     * 四分之一约 2× 幅度），由 DLSS-RR 的时间累积抹平。会话内生效，不写配置。
     */
    public static native boolean setGiNearRate(
            int level
    );

    public static native int getGiNearRate();

    /**
     * 2026-10-25 光泽反射按粗糙度降速：roughness ≥ 0.30 半速、≥ 0.65 四分之一，
     * 光滑面保持全速。与距离级合成同一个级别，权重只乘一次。
     */
    public static native boolean setGlossyRoughLod(
            boolean enabled
    );

    public static native boolean getGlossyRoughLod();

    /**
     * 2026-10-22 UE 风格体积云的八项参数（设置界面滑条）。
     *
     * <p>索引与 CameraUBO 的 {@code ueCloudA}（偏移 1536）/ {@code ueCloudB}
     * （偏移 1552）的两组 lane 严格一致：</p>
     * <pre>
     *   0 msOctaves          多重散射 octaves，1..3（UE 文档建议游戏只用 1）
     *   1 msContribution     每阶能量权重 0..1
     *   2 msOcclusion        每阶消光衰减 0.05..1
     *   3 luminanceScale     Cloud Scattering Luminance Scale（octave 能量补偿）
     *   4 groundContribution 地面反照照亮云底 0..1
     *   5 cloudAO            云遮挡天空光强度 0..4
     *   6 atmosphereDensity  逐采样大气透射率强度 0..2（0 = 关）
     *   7 weatherScaleMul    weather map 频率倍率 0..4
     * </pre>
     *
     * <p>纯状态拷贝：C++ 只写全局变量，下一帧 {@code updateCamera} 填进
     * CameraUBO，不产生任何 Vulkan 调用。</p>
     */
    public static native boolean setUeCloudParam(
            int index,
            float value
    );

    public static native float getUeCloudParam(
            int index
    );

    /**
     * 2026-10-19 树叶次表面散射（设置界面按钮）。
     *
     * <p>{@code false = 太阳背光}（旧档，只有太阳直射到叶面背面时触发）；
     * {@code true = 真次表面散射}（叶面背半球一条透射射线，miss 时按天空辐亮度
     * 加透射光，不依赖太阳直射）。纯状态拷贝：C++ 只写全局变量，下一帧
     * {@code updateCamera} 填进 CameraUBO.sssParams.x，不产生任何 Vulkan 调用。</p>
     */
    public static native boolean setSSSMode(
            boolean trueSubsurface
    );

    public static native boolean getSSSMode();

    public static native boolean setLocalLightMaxDistance(
            float value
    );

    public static native float getLocalLightMaxDistance();

    /**
     * 本地光源照射强度（设置界面滑块）。
     *
     * <p>纯状态拷贝：C++ 侧只写自己的全局变量，下一帧由 {@code updateCamera}
     * 填进 {@code CameraUBO.historyPadding1}，<b>不产生任何 Vulkan 调用</b>。</p>
     *
     * <p>0.0 = 关掉本地光源，1.0 = 出厂观感（着色器里的
     * {@code LOCAL_LIGHT_ENERGY_SCALE}），上限 4.0。</p>
     *
     * @return 取值是否被接受（非有限值或越界会被拒绝）。
     */
    public static native boolean setLocalLightIntensity(
            float value
    );

    public static native float getLocalLightIntensity();

    public static native boolean setSunVisualSettings(
            float diskLuminance,
            float haloStrength,
            float angularDiameterDegrees
    );

    public static native boolean setAtmosphereSettings(
            float starIntensity,
            float sunTintStrength,
            boolean physicalSkyEnabled,
            float scatterIntensity,
            float ozoneStrength
    );

    public static native boolean setPowderStrength(
            float value
    );

    /** 相位上限过渡 [0,1]：0 = 旧视觉钳制 1.05，1 = 双瓣 HG 自然峰值 2.52（默认）。 */
    public static native boolean setCloudSunForwardScattering(
            float value
    );

    /** 批次1-A：夕阳云体红化 [0,1]（cloudParams6.y）。0 = 关（逐位旧档）。 */
    public static native boolean setCloudSunReddening(
            float value
    );

    /** 批次2-T2b：云顶随云量 Möbius 拉伸 [0,1]（cloudParams6.z）。0 = 关。 */
    public static native boolean setCloudTopStretch(
            float value
    );

    /** 2026-10-18：云缘细节尺度 [0.1,12]（cloudParams6.w，×8 → cfg.detailScale）。 */
    public static native boolean setCloudDetileStrength(
            float value
    );

    /** 2026-10-18：云型 [0,1]（cloudParams5.x）。0=层云 0.5=层积云 1=积云。 */
    public static native boolean setCloudType(
            float value
    );

    /** 2026-10-18：云缘侵蚀幅度 [0,2]（cloudParams5.y，1.0 = 出厂标定）。 */
    public static native boolean setCloudErosionScale(
            float value
    );

    /** 2026-10-18：天气图尺度 [2000,60000] 格（cloudParams1.y，3D 低频周期）。 */
    public static native boolean setCloudWeatherScale(
            float value
    );

    public static native float getCloudWeatherScale();

    /** 2026-10-19：逆光银边强度 [0,1]（cloudParams2.z）。出厂 0.08。 */
    public static native boolean setCloudSilver(
            float value
    );

    public static native float getCloudSilver();

    /** 2026-10-18：云视距 [2000,200000] 格（cloudParams1.z）。 */
    public static native boolean setCloudViewDistance(
            float value
    );

    public static native float getCloudViewDistance();

    // =====================================================
    // 2026-10-04 S2/S3：远云的"分辨率与步长"两个解耦旋钮。
    // 云的 march 分辨率此前恒等于 RR 内部渲染分辨率（超高性能档 707x376 @
    // 2120x1200），地平线掠射带只剩几个像素高 ⇒ 低档位下糊成一条带；只有平衡档
    // （采样多 3 倍）才不糊。第二个成因是整段均匀步长（96 步摊到上百 km ≈ 2 km/步）
    // 把那条带量化成板条。两个旋钮分别针对这两件事，且都与 RR 档位无关。
    // =====================================================

    /** S2：云 march 分辨率下限 = 输出分辨率 × percent%（0 = 关 = 旧行为）。 */
    public static native boolean setCloudResFloorPercent(
            int percent
    );

    public static native int getCloudResFloorPercent();

    // =====================================================
    // 2026-10-07 Perf-L10 交错 A/B 实测后的两项 RT 优化
    //（见 VulkanCore.cpp 的 g_giRoulette / g_sunMarchFarDistance 与 rt_test.rgen 的 stage 11/12）
    // 两者都写进 CameraUBO.rtSampleParams 的保留 lane（.y / .z），只被 rgen 读。
    // =====================================================

    /** GI 循环俄罗斯轮盘：1 = 开（出厂，省 −7.8ms 整帧）/ 0 = 旧档。 */
    public static native boolean setGiRoulette(
            int enabled
    );

    public static native int getGiRoulette();

    // =====================================================
    // 2026-10-26 直接光模式 + 稀疏渲染（CameraUBO.sparseParams 尾追加 lane）。
    // 两者都是运行时原子量 ⇒ 设置界面 Apply 下一帧生效，无新 spv 变体。
    // =====================================================

    /** 直接光模式：0 = 传统 RIS（出厂）/ 1 = RTXDI 式时域 reservoir。 */
    public static native boolean setDirectLightMode(
            int mode
    );

    public static native int getDirectLightMode();

    /**
     * 2026-10-28 间接光模式：0 = GI（出厂）/ 1 = SHaRC。
     * ⚠ SHaRC 的缓冲与 RT 管线在 initDeviceHandlesNative() 里创建，
     * 所以本调用必须发生在它**之前**（见 initDeviceHandles 的 SER/AE 那一批），
     * 运行期改值只会写原子量，需重启客户端才真正生效。
     */
    public static native boolean setIndirectMode(
            int mode
    );

    public static native int getIndirectMode();

    /** 稀疏渲染采样率 %：0 = 关（出厂）/ 100 = 全采样（白噪声逐像素抽签）。 */
    public static native boolean setSparseRatePercent(
            int percent
    );

    public static native int getSparseRatePercent();

    /** 非活跃像素仍更新直接光 reservoir（官方 enableRtxdiReuseForInactivePixels）。 */
    public static native boolean setRtdiReuseInactive(
            int enabled
    );

    /**
     * 2026-10-09 运动矢量（DLSS-RR）**调试视图**：0 = 关（出厂）/ 1 = 幅值热图 /
     * 2 = 有向色。
     *
     * <p>走 CameraUBO.perfAblationStage 的**保留 lane .y**（与 giRoulette 走
     * rtSampleParams.y 同一做法），native 侧只存一个原子量 ⇒ <b>切换下一帧即生效</b>，
     * 不重建任何资源、不需要重启，也不需要重编着色器。</p>
     *
     * <p>判读（详见 rt_test.rgen 的 rtMotionDebugColor）：模式 1 下静态世界与天空
     * 必须是<b>纯黑</b>；出现整屏灰就说明有相机成分漏进了 MV。</p>
     */
    public static native boolean setMotionDebugView(int mode);

    public static native int getMotionDebugView();

    /**
     * 2026-10-09 运动矢量**总开关**：0 = 关 / 1 = 开（出厂）。
     *
     * <p>关掉时 rgen 会把 MV 图**写 0**（不是"不写"）⇒ 与接 MV 之前逐位一致。
     * 它有两个用途：出问题时立即退路；以及把"画面异常是否来自 MV"一刀切开
     * （关掉后异常消失 ⇒ 就是 MV；还在 ⇒ 与 MV 无关）。</p>
     *
     * <p>同样走 CameraUBO.perfAblationStage 的保留 lane（.z），切换下一帧生效。</p>
     */
    public static native boolean setMotionVectorsEnabled(int enabled);

    public static native int getMotionVectorsEnabled();

    // 2026-10-22（C1）：运动矢量档位 0..3（0=关 1=像素 2=像素y反 3=UV）。
    // 与 setMotionVectorsEnabled 的区别：这个同时决定 mvecScale 的取值，
    // 下一帧生效（constants 每帧重填），不需要重启。
    public static native int setMotionVectorScaleMode(int mode);

    public static native int getMotionVectorScaleMode();

    /** 太阳透射 march 的远距简化距离（格）：超过即只用 1 步；0 = 关 = 固定 6 步。 */
    public static native boolean setSunMarchFarDistance(
            int blocks
    );

    public static native int getSunMarchFarDistance();

    /** S3：远段步长加密 [1,4]。1 = 旧档（逐位不变）；越大外层步长越细。 */
    public static native boolean setCloudFarStepDensity(
            float value
    );

    public static native float getCloudFarStepDensity();

    /** 批次2：步进旋钮（cloudMarchParams）。见 CloudMarchSettings.toParamArray()。 */
    public static native boolean setCloudMarchParams(float[] params);

    /**
     * 2026-10-03 远淡出是否跟随 RR 档位。
     *
     * <p>地平线那条掠射带（弦长可达 200+ km）同时被两件事打薄：云的 march 渲染
     * 分辨率 = RR 档位反查出的 renderWidth/Height（超高性能档实测 707x376 @
     * 2120x1200），以及该带自身的步长量化。项目原本的收口手段就是远淡出
     * （{@code cloudFarFadeDistance}，出厂 80 km），所以开着时按上采样倍率收短：
     * 倍率 &ge; 2（性能 / 超高性能）时 淡出距离 / 倍率；平衡/质量/DLAA 不动。</p>
     *
     * <p>关掉 = 逐字节回到"淡出与档位无关"的旧行为。</p>
     */
    public static native boolean setCloudFadeFollowsRrMode(boolean enabled);

    public static native boolean getCloudFadeFollowsRrMode();

    /**
     * 2026-10-06 远云剥离 RR：云层在输出分辨率渲染、合成挪到 RR evaluate 之后。
     *
     * <p>原状是云在 RR <b>之前</b>合成进 RR 的颜色输入（{@code g_rtOutputImage}，
     * render 分辨率），于是云的清晰度被 RR 从 render 分辨率上采样到输出分辨率的
     * 那一步限死 —— 档位越低、render 分辨率越低，远景云越糊（超高性能档
     * 707x376 → 2122x1129）。开启后云不进 RR，直接以输出分辨率合成到 RR 的输出上，
     * 清晰度与档位无关。</p>
     *
     * <p>代价：云的 march 与合成都在输出分辨率跑（像素数按
     * (输出/render)² 增长，超高性能档约 9 倍）。</p>
     *
     * <p>关掉 = 逐字节回到"云进 RR 颜色输入"的旧档。</p>
     */
    public static native boolean setCloudPostRrComposite(boolean enabled);

    public static native boolean getCloudPostRrComposite();

    /** 批次3：水体观感四件套（waterParams）。见 WaterParamsSettings.toParamArray()。 */
    public static native boolean setWaterParams(float[] params);

    public static native boolean setCloudSettings(
            boolean enabled,
            float bottom,
            float thickness,
            float coverage,
            float density,
            float detailStrength,
            float windSpeed,
            float evolution,
            int quality,
            float size,
            float distribution,
            float baseFeather,
            float sunlightStrength
    );
    // phi_fwd 多重散射漫射场 + MS 残量尾巴（2026-10-02）：4 个 lane 一次下发，
    // 见 RTXSettingsConfig.CloudMsSettings.toParamArray() 与
    // CameraUBO.cloudMsParams。
    /**
     * 2026-10-25 v5（HPVolumeCloud 移植）的 28 项全参数，一次下发。
     * 顺序必须与 RTXSettingsConfig.CLOUD_HP_LABELS / CameraUBO.hpShapeA..hpMiscA 一致。
     */
    public static native boolean setCloudHpParams(float[] params);

    /** 读回原生侧当前生效的 28 项（调试 / UI 同步用），长度不足 28 返回 false。 */
    public static native boolean getCloudHpParams(float[] out);

    public static native boolean setCloudMsParams(float[] params);

    /**
 * 平面云：9 个分量一次下发
     * （启用 / 高度偏移 / 尺度 / 速度 / 密度 / 覆盖率 / 八度数 / 淡出距离 / 亮度）。
     * 见 RTXSettingsConfig.PlaneCloudSettings.toParamArray() 与
     * CameraUBO.highCloudParams（偏移 944）。
     */
    public static native boolean setPlaneCloudParams(float[] params);

    /**
     * 参考重构（2026-10-02）：phi_fwd 四件套（边界受光置信度 / MSBuildScale /
     * Compress / CONE_RATIO）。见 RTXSettingsConfig.CloudPhiSettings.toParamArray()。
     */
    public static native boolean setCloudPhiParams(float[] params);

    /**
     * 参考重构（2026-10-02）：受光逻辑三个尺度量（有效源强曲线尺度 / 曲线指数 /
     * 环境 upward-AO 强度）。见 RTXSettingsConfig.CloudLightSettings.toParamArray()。
     */
    public static native boolean setCloudLightParams(float[] params);

    /**
     * 参考重构（2026-10-02）：云底软化 + 两级步进阈值。**密度场共享** ⇒ 视图、
     * 云影、反射、光柱四个消费者同源。见
     * RTXSettingsConfig.CloudShapeSettings.toParamArray()。
     */
    public static native boolean setCloudShapeParams(float[] params);

    /**
     * 参考重构（2026-10-02）：物理月光项（强度 / 月相 = 被照亮比例）。见
     * RTXSettingsConfig.CloudMoonSettings.toParamArray()。
     */
    public static native boolean setCloudMoonParams(float[] params);

    /**
     * 第四阶段（2026-10-02）：细节/形状旋钮（细节尺度 / 细节对比 / 廓形曲线）。
     * 密度场共享 ⇒ 视图、云影、反射、光柱同源。见
     * RTXSettingsConfig.CloudShape2Settings.toParamArray()。
     */
    public static native boolean setCloudShape2Params(float[] params);

    /**
     * 参考重构（2026-10-02）**#2 环境光接天空**：天顶/地面环境倍率、朝阳侧暖环境
     * 强度、天光色相跟随（0 = 固定冷蓝 = 旧档）。见
     * RTXSettingsConfig.CloudAmbientSettings.toParamArray()。
     */
    public static native boolean setCloudAmbientParams(float[] params);

    /**
     * 2026-10-14 **日落天光修正**：天光冷化强度 / 天光能量衰减强度（0 = 关 = 逐位
     * 旧档）。把环境光色相与直射色相解耦（日落天顶天光是蓝紫，不是直射的橙红），
     * 并让天光能量在低太阳下真正衰减。见
     * RTXSettingsConfig.CloudSkyHueSettings.toParamArray()。
     */
    public static native boolean setCloudSkyHueParams(float[] params);

    /**
     * 2026-10-15 云地面反射与显式视觉回退参数：
     * x = 均匀水平地面 Lambert 反照率，y = 地面反射近似强度，
     * z = 非物理自阴影残光回退，w = 非物理相位地板回退。
     * 反照只覆盖均匀水平地面受直射日光的近似，不包含地形遮挡或场景级环境积分；
     * 两种回退默认均为 0（关闭）。见 RTXSettingsConfig.CloudPhysicsSettings.toParamArray()。
     */
    public static native boolean setCloudPhysicsParams(float[] params);

    /**
     * **#6 散射染色 + MS 级联三参数**：染色强度 / 级数(1..3) / 衰减率 / 偏心率。
     * 见 RTXSettingsConfig.CloudMs2Settings.toParamArray()。
     */
    public static native boolean setCloudMs2Params(float[] params);

    /**
     * **#5 光路太阳色红化 + #7 粉末口径**：越深处的直射越红；粉末不施加于方向性
     * 散射（保云缘亮边）。见 RTXSettingsConfig.CloudLight2Settings.toParamArray()。
     */
    public static native boolean setCloudLight2Params(float[] params);

    /**
     * **#3 剖面云型**（Cu/Tcu/Cb 三段插值 + 云型随区域幅度）。**密度场共享** ⇒
     * 视图、云影、反射、光柱同源。见
     * RTXSettingsConfig.CloudProfileSettings.toParamArray()。
     */
    public static native boolean setCloudProfileParams(float[] params);

    /**
     * **#4 两族侵蚀**（絮状强度 / 边缘宽度 / 下沉量 / 顶部高度）。**密度场共享**，
     * 用形状噪声体此前未使用的 B/A 胞元通道实现，零额外纹理读。见
     * RTXSettingsConfig.CloudDetailSettings.toParamArray()。
     */
    public static native boolean setCloudDetailParams(float[] params);

    /**
 * 2026-10-10 **#5 晨雾 / #6 彩虹 + 闪电**（CameraUBO.
     * fogExtrasParams，偏移 1376）：动态光源强度 / 地面锚定 / 天空环境项 /
     * 时域抖动。四项出厂 1.0 = 开，0 = 逐位旧档。
     * 见 RTXSettingsConfig.FogExtrasSettings.toParamArray()。
     *
     * 2026-10-23：四个字段的**含义换新**（原来是彩虹/闪电/晨雾/晨雾标高，
     * 那三项自 2026-10-19 体积雾重做后已无着色器消费者）。JNI 符号与数组长度不变。
     */
    public static native boolean setFogExtrasParams(float[] params);

    /**
     * 2026-10-23 雨天地面雾浪（CameraUBO 的 cloudParams4.y/.z/.w + cloudParams9.w）。
     * 数组 4 项：{enabled(0/1), 强度, 波长(格), 漂移速度(格/秒)}。
     * 见 RTXSettingsConfig.FogWispSettings.toParamArray()。
     */
    public static native boolean setFogWispParams(float[] params);

    /**
 * 2026-10-10 **#3 天光自遮挡 / #4 时域速度抑制**
     * （CameraUBO.cloudTemporalParams，偏移 1392）：天光自遮挡强度 / 步数 /
     * 时域速度抑制强度 / 保留。三项出厂 0 = 关（逐位旧档）。见
     * RTXSettingsConfig.CloudTemporalSettings.toParamArray()。
     */
    public static native boolean setCloudTemporalParams(float[] params);

    /**
     * 2026-10-10 借鉴 **#1 云后空气透视 / #2 地面遮挡衰减**
     * （CameraUBO.cloudAerialParams，偏移 1408）：云层大气透视强度 /
     * 地面大气透视强度 / 地面大气透视遮挡衰减 / 保留。三项出厂 0 = 关（逐位旧档）。
     * 见 RTXSettingsConfig.CloudAerialSettings.toParamArray()。
     */
    public static native boolean setCloudAerialParams(float[] params);

    /**
     * 2026-10-03 薄层 SSS 四件套（参考 HPWater BSDF 的 thinLayerSSS）：
     * 非线性光程混合 / 散射能量补偿 / 相位各向异性 g / 总强度。
     * 见 RTXSettingsConfig.WaterSssSettings.toParamArray()。
     */
    public static native boolean setWaterSssParams(float[] params);

    /**
     * 2026-10-02 水面波面参考重构（HPWater）—— **波面法线侧**（CameraUBO.
     * waterWaveParams，偏移 1232，rt_test.rchit / rt_test.rgen 读）：
     * 交互场法线标定（1 = 参考 1/(2Δ)）/ 强度倍率 / 波面混合模式（1 = 参考
     * XZ 相加 Y 相乘）。见 RTXSettingsConfig.WaterWaveSettings.toParamArray()。
     * <p>数组**第 5 个**量（2026-10-20 新增）= 反射焦散强度，写进本块原保留 lane w3。
     * 长度 < 5 时该项停在 C++ 出厂值 4.0 ⇒ 旧 jar 配新 DLL 逐位不变。
     */
    public static native boolean setWaterWaveParams(float[] params);

    /**
     * 2026-10-02 水面波面参考重构（HPWater）—— **波动方程侧**（water_sim.comp 的
     * push constant，不占 UBO lane）：海绵层宽度 / 障碍物诺伊曼反射 / 高斯波源 /
     * 阻尼倍率。见 RTXSettingsConfig.WaterSimWaveSettings.toParamArray()。
     */
    public static native boolean setWaterSimWaveParams(float[] params);

    /**
     * 2026-10-03 FFT 频谱海面（Tessendorf/Phillips 谱）：
     * 开关 / 波高倍率(振幅) / 波峰锐化 / 平铺周期(格) / 波浪细节(0 = 关)。
 *（2026-10-17：删风速/风向/涌浪强度三项死设置；
     *   2026-10-19 新增第 5 项「波浪细节」= 细分子层倍率。）
     * 见 RTXSettingsConfig.WaterFftSettings.toParamArray()。
     */
    public static native boolean setWaterFftParams(float[] params);

    /**
     * 2026-10-03 后处理（新「后处理」设置页）：色彩映射模式（0=Uchimura/GT
     * 1=AgX 2=PBR中性 3=ACES 4=Uncharted2 5=Reinhard 6=ACES 2.0）+ 自动曝光参与度 +
     * 对比 + 饱和 + gamma。见 RTXSettingsConfig.PostProcessingSettings。
     * 2026-10-23：values[8] = 泛光强度，values[9..11] = 调色调 R/G/B 增益（1 = 恒等）；
     * 长度不足的旧数组按"该 lane 保持默认"处理，两侧可以各自独立部署。
     */
    public static native boolean setPostProcessingParams(float[] params);

    /**
     * 2026-10-26 **色彩分级**（设置页「色彩映射与调色」）24 个 lane，顺序与
     * {@link com.example.client.config.RTXSettingsConfig#COLOR_GRADE_LABELS} 一致：
     * 手动曝光 / 黑点 / 白点 / 显示对比 / 对比支点 / Lift RGB / Gamma RGB /
     * Gain RGB / 阴影色相+强度 / 高光色相+强度 / 自然饱和度 / 褪色 /
     * 暗角强度+半径 / 色相旋转 / 绿品红。
     *
     * <p>作用点是 rr_sdr_post.comp 里 tonemap + gamma + 饱和度**之后**的显示域；
     * 全部中性值时着色器整块跳过 ⇒ 没调过的人画面逐位不变。</p>
     *
     * <p>走独立入口而不是塞进 {@code setPostProcessingParams}：那个数组已经到
     * 12 个 lane，再加 24 个会让"改一个旋钮要同时改 C++ / Java / JNI 签名"。</p>
     *
     * @return 长度不是 24、或含非有限值时为 false（native 侧逐项钳制到
     *         RTXSettingsConfig.COLOR_GRADE_RANGES 的同一区间）。
     */
    public static native boolean setColorGradeParams(float[] params);

    /**
     * 2026-10-03 RR guide 设置（rr_guide_relax.comp 的两个职责）。
     *
     * <p>{@code farCloudDistance} = 云的前线命中距离阈值（格 = 米）：像素被云遮挡
     * 且云距离不小于该阈值时，把 RR 的 guide/深度换成"云面"记录（反照率 = 云散射色、
     * roughness = 1、specular = 0、hit distance/深度 = 云距离）。云是在 rgen 之后才
     * 合成进 RR 颜色输入的，guide 却由 rgen 写，所以云像素上 RR 拿到的是云后那个面
     * （通常是天空：深度 0、65504 距离）⇒ 远处云被按"无穷远静态背景"重投影而糊成一团。
     * 0 = 关闭（逐字节回旧档），上限 80000。</p>
     *
     * <p>{@code relaxEnabled} = 跨 delta 的粗糙度定向放宽（2026-10-01 既有功能）。</p>
     *
     * @return 取值是否被接受（非有限值或越界会被拒绝）。
     */
    public static native boolean setRrGuideSettings(
            boolean relaxEnabled,
            float farCloudDistance
    );

    /** 当前生效的远云距离（格；0 = 关闭）。回滚 UI 用。 */
    public static native float getRrFarCloudDistance();

    /**
     * 2026-10-02 **波形本体**（CameraUBO.waterWaveShapeParams，偏移 1248）：
     * 波长倍率 / 主波向（度）/ 涌浪陡度 / 波峰扭曲。作用在共享波场内部 ⇒
     * 可见水面与解析焦散自动同源。见 RTXSettingsConfig.WaterWaveShapeSettings。
     */
    public static native boolean setWaterWaveShapeParams(float[] params);

    /**
     * 2026-10-14 **波面后端**（复用退役的 CameraUBO.waterWaveShapeParams，偏移 1248）：
     * 基波长（格）/ 域扭曲 warp / 波数 / 波长带宽。
     * <p>2026-10-20：可见水面定档 FFT 涌浪、ROUTE C 整体 `#if 0` 后，只有 index 0/2
 * 还有编译端读者；1/3 两项的
     * GUI 入口已按"死设置"移除，但 **wire 仍发 4 个量**，1/3 填 ROUTE C 出厂常数
     * 0.90 / 8.0 ⇒ 将来放开那段 `#if 0` 不会读到 0。
     * <p>方法名与 JNI 符号名**刻意沿用 "Afl"**：改名会让"只换 jar 不换 DLL"（或反之）
     * 在 JNI 解析符号时失败并让整个模组启动即崩。见 RTXSettingsConfig.WaterWaveAflSettings。
     */
    public static native boolean setWaterWaveAflParams(float[] params);

    /**
     * 体积雾（2026-10-19 B 方案）。
     * 完整实现见 cpp/shaders/volumetric_fog.glsl；这里只有落在 cloudParams7/8/9
     * 的 11 个字段：
     *
     * <pre>
     *   density         VFOG_DENSITY_BASE：参考高度处的密度基准
     *   height          VFOG_HEIGHT：双高度带的上界（世界 Y，格）
     *   scaleHeight     VFOG_HEIGHT_2：双高度带的下界（世界 Y，格）
     *   anisotropy      Henyey-Greenstein 的 g（0 = 各向同性）
     *   sunStrength     VFOG_SUNLIGHT_DENSITY：太阳入散射倍率
     *   ambientStrength VFOG_FOG_DENSITY：环境项倍率
     *   maxDistance     雾的最远作用距离（格）
     *   startDistance   近处留白距离（格）
     *   sunSteps        沿太阳方向的密度 march 步数（1..8，不是阴影图取样）
     *   rainBoost       VFOG_RAIN_DENSITY_MUL：雨强对密度的倍率
     * </pre>
     *
     * 旧档的噪声尺度 / 漂移速度 / 细节层数 / 贴地薄雾 / Rayleigh / 彩虹 / 闪电
     * 等字段已随旧实现删除，不要再按那些字段理解本方法。
     */
    public static native boolean setVolumetricFogSettings(
            boolean enabled,
            float density,
            float height,
            float scaleHeight,
            float anisotropy,
            float sunStrength,
            float ambientStrength,
            float maxDistance,
            float startDistance,
            int sunSteps,
            float rainBoost
    );

    /**
     * Rain run-off on vertical faces (the wet film and the droplets running
     * down walls in {@code rt_test.rgen}).  Unlike {@code rainOpacity} and
     * friends, which ParticleRtCapture reads straight out of the config while
     * rebuilding the weather lattice, these have to reach the shader through
     * {@code CameraUBO.rainWallParams}, so this call can fail and the caller
     * must keep the previous values rather than assume they took.
     * <p>
     * The five scale fields are relative to the shader's own constants, so 1.0
     * is the calibrated look; {@code distance} is the one cost control.
     */
    public static native boolean setRainWallSettings(
            boolean enabled,
            float wetness,
            float dripDensity,
            float dripSpeed,
            float dripSize,
            float dripBump,
            float distance
    );

    /**
     * Water style dials (Waterlight turquoise look): wave scale, turquoise
     * tint mix, absorption density scale, caustic strength, natural-wave
     * speed and the noise-wave octave spectrum (swell / mid / fine).  The
     * shaders read these from {@code CameraUBO.waterStyleParams}, so this
     * call can fail and the caller must keep the previous values rather than
     * assume they took.  {@code waveScale}/{@code tintMix}/{@code
     * densityScale}/{@code waveSpeed}/{@code swellScale}/{@code midScale}/
     * {@code fineScale} are relative dials (1.0 is the shipped look;
     * waveSpeed 0.0 freezes the natural waves, an octave dial 0.0 flattens
     * that octave); {@code causticStrength} is the absolute brightness
     * multiplier.
     */
    public static native boolean setWaterStyleSettings(
            float waveScale,
            float tintMix,
            float densityScale,
            float causticStrength,
            float waveSpeed,
            float swellScale,
            float midScale,
            float fineScale
    );

    /**
     * 2026-10-08 末地银河旋钮（Shadertoy "Spiral galaxy" 移植的观感控制）。
     * 着色器从 {@code CameraUBO.galaxyParams} 每帧读取，所以与雨痕/水色同一条
     * 应用规则：本调用可能失败，调用方必须保留旧值而不是假设已生效。
     * {@code scale} 是大小倍率（调小 = 银河更大，出厂 0.85）；{@code tiltDegrees}
     * 是银河盘面与水平面的夹角（度，出厂 30）；{@code yawDegrees} 是核心方位的
     * 世界方位角（度，出厂 -45；银河随星图自转，这是相对星空的初始方位）；
     * {@code mix} 是叠加强度（出厂 0.5）—— 2026-10-08 起图集星空恒做底，银河
     * 按这个权重加在上面，0 = 只剩原版图集星空，1 = 两者都满亮。
     */
    public static native boolean setGalaxySettings(
            float scale,
            float tiltDegrees,
            float yawDegrees,
            float mix
    );

    /**
     * 体积光（2026-10-19：空气体积光退役，本组设置主要控制水下分支）。
     * <p>
     * 空气散射现在完全由体积雾的 rayColor 提供；volumetric_light.comp 的
     * {@code VL_AIR_GODRAYS = 0}，默认只执行 marchUnderwaterGodRays()。
     * {@code VL_AIR_GODRAYS = 1} 时才会恢复旧空气路径用于 A/B。
     * <p>
     * {@code steps} 与 {@code occlusionSamples} 是纯成本旋钮（水下步数 / 云影
     * march），{@code intensity} 乘在昼夜 strength 上（1.0 = 标定档，0 = 关，
     * 上限 4.0）。2026-10-02 的 {@code selfShadow} 已随设置删除，不再存在。
     */
    public static native boolean setVolumetricLightSettings(
            boolean enabled,
            int steps,
            int occlusionSamples,
            float intensity
    );

    /**
     * 2026-10-04（P1）：体积光时间重投影的历史权重（0 = 关，0.85 = 出厂）。
     * 刻意独立成入口：给上面的 setter 加形参一旦漏改某个调用点，JNI 会在启动期
     * 解析失败并直接崩模组。走 cloudProfileParams.z（保留位）。
     */
    public static native boolean setGodRayReprojection(float weight);

    // 2026-10-06 设置清理：setFogBlobModeNative 随 fogBlobs 设置一起从 Java 侧
    // 删除（密度模型已统一成单一公式，不再有"仅高度衰减 / 团雾"两个分支）。
    public static long pendingPhysicalDeviceHandle = 0L;
    public static long pendingDeviceHandle = 0L;
    public static long pendingQueueHandle = 0L;
    public static int pendingQueueFamilyIndex = -1;
    public static boolean handlesPending = false;

    public static native void clearWorldGeometry();

    // === 核心修正：增加 layerId 参数以隔离渲染层级 ===
    public static native void uploadChunk(int sectionX, int sectionY, int sectionZ, int layerId, long vertexPointer, int vertexCount, int vertexStride);

    /**
     * 一次擦掉一个 Section 的多个地形层（位 = layerId：bit0 SOLID、bit1 CUTOUT、
     * bit2 TRANSLUCENT）。
     *
     * <p>语义与"逐层发零顶点 {@link #uploadChunk}"完全相同，只是把取锁与
     * {@code markWorldGeometryDirty} 合并成一次：原先 SectionCompilerMixin 对本次
     * 编译没有几何的每一层各发一次（最多 3 次/section），FarFieldGate 每次状态迁移
     * 连发 3 次、单帧最多 24 次。</p>
     *
     * <p>只有真的擦掉了缓存条目才会标脏该空间 Section，所以对从未有几何的层重复
     * 调用是廉价空操作 —— 与原路径的语义一致。</p>
     *
     * @return 是否真的擦掉了缓存（观测用）。
     */
    public static native boolean clearChunkLayers(int sectionX, int sectionY, int sectionZ, int layerMask);

    /** Voxy's provider ABI supplies 64-byte LOD quads and therefore has a separate JNI path. */
    public static native void uploadVoxyLodMesh(
            long sourceKey, long version,
            int originX, int originY, int originZ, int width, int dataPointWidth,
            byte[] opaque, byte[] transparent
    );

    public static native void removeVoxyLodMesh(long sourceKey);

    public static native void clearVoxyLodMeshes();

    /**
     * DH 远场几何（C 阶段）：<b>一个 DH section 一份</b>，per-section 增量上传。
     *
     * <p>与 {@link #uploadVoxyLodMesh} 走同一条原生通路：解码成我方 {@code Vertex} 后
     * 进 {@code g_chunkMeshes}，因而<b>自动</b>拿到 Section BLAS、自动进 TLAS、
     * 自动参与 GI / 阴影 / 反射（rchit 只输出 payload，光照统一在 rgen 里算）。</p>
     *
     * <p>字节布局由 Orion 自己定义，不复用 voxy 那套（voxy 把 material 塞在顶点 0
     * 的 b 通道上，同一字节两种语义）：每 quad 64 字节 = 4 顶点 × 16 字节，
     * material 位于顶点 0 的 +14 字节。Java 侧编码见
     * {@code com.example.client.compat.dh.DhFarFieldUploader} 的 {@code convert()}。</p>
     *
     * <p>{@code sectionKey} 是 DH 自己的 {@code DhSectionPos} 编码，作为该节的稳定身份；
     * {@code version} 单调递增，重复上传同一个 key 时 C++ 只认更新的 version
     * （与 voxy 通路的 {@code sourceKey/version} 同一套语义，见 {@code VulkanCore.cpp}
     * 的 {@code Java_..._uploadVoxyLodMesh}）。</p>
     *
     * <p>原点语义：{@code originX/Y/Z} 是该 section 的最小角（DH 顶点只带 section 内
     * 局部 short 坐标），{@code width} 是方块边长。</p>
     */
    public static native void uploadDhLodSection(
            long sectionKey, long version,
            int originX, int originY, int originZ, int width, byte[] quads
    );

    /** 撤掉一个 DH section（该节不再被 DH 启用 / 换世界）。 */
    public static native void removeDhLodSection(long sectionKey);

    /** 撤掉全部 DH 远场节（关开关 / 换世界）。 */
    public static native void clearDhLodSections();

    /** One 4-state record per CUTOUT triangle; unknown records keep Any-Hit. */
    public static native void uploadCutoutOmmSection(
            int sectionX,
            int sectionY,
            int sectionZ,
            long dataPointer,
            int dataBytes,
            int triangleCount,
            int subdivisionLevel,
            int knownMicroTriangleCount
    );

    // VLI source cache: exactly VLI_SOURCE_WORDS uint32 values (32 KiB) for
    // one 16x16x16 Section.  The declaration matches the native JNI entry.
    public static native void uploadVoxelLightSection(
            int sectionX,
            int sectionY,
            int sectionZ,
            long voxelPointer,
            int voxelBytes
    );

    // =====================================================
    // RT Local Lights - L2
    //
    // lightPointer points to lightCount records.
    //
    // Current record layout:
    // int worldX
    // int worldY
    // int worldZ
    // int emissionLevel
    // int typeCode
    //
    // lightStride = 20 bytes in the current Java producer.
    //
    // lightCount == 0 and lightPointer == 0 means:
    // clear/replace this section with an empty light list.
    // =====================================================
    public static native void uploadLightSection(
            int sectionX,
            int sectionY,
            int sectionZ,
            long lightPointer,
            int lightCount,
            int lightStride
    );
    // =====================================================
// RT Local Lights - L6
// Remove every cached light Section in one unloaded chunk.
// =====================================================

    public static native void removeLightChunk(
            int chunkX,
            int chunkZ
    );

    private static boolean loaded = false;

    // =====================================================
    // Bundled Windows native runtime
    //
    // Every DLL under:
    //   src/main/resources/natives/windows/
    //
    // is copied to the user's real Fabric game directory
    // before rtx_backend.dll is loaded.
    // =====================================================

    private static final String MOD_ID =
            "orion";

    private static final String NATIVE_RESOURCE_DIR =
            "natives/windows";


    /**
     * 2026-10-19：把随包分发的 SPIR-V 释放到游戏目录的 shaders/。
     *
     * 着色器是 C++ 用文件路径加载的（不是 classpath），所以必须落盘。
     * 内容相同就跳过（用 Files.mismatch 比较），避免每次启动都重写。
     *
     * 2026-10-21：**移除双工作区支持**（用户要求「抹掉延迟版，只维护生产版」）。
     * 源目录固定为 `shaders/`，原 `-Drtx.shaderSet=shaders_deferred` 分支一并删除。
     * 找不到 `shaders/` 时仍按原逻辑报错、绝不静默回退。
     */
    private static void extractBundledShaders(
            Path gameDir
    ) throws IOException {

        final ModContainer mod =
                FabricLoader
                        .getInstance()
                        .getModContainer(MOD_ID)
                        .orElse(null);

        if (mod == null) {
            return;
        }

        final Path targetDir =
                gameDir.resolve("shaders");

        // 2026-10-21：固定为生产档目录（延迟档已移除，见上方注释）。
        final String shaderSet = "shaders";

        int copied = 0;

        boolean foundSet = false;


        for (Path root : mod.getRootPaths()) {

            final Path shaderRoot =
                    root.resolve(shaderSet);

            if (!Files.isDirectory(shaderRoot)) {
                continue;
            }

            foundSet = true;


            try (Stream<Path> stream = Files.walk(shaderRoot)) {

                for (Path source : stream.filter(Files::isRegularFile).toList()) {

                    final Path target =
                            targetDir.resolve(
                                    source.getFileName().toString()
                            );

                    boolean needsCopy = true;

                    if (Files.isRegularFile(target)) {
                        try {
                            needsCopy =
                                    Files.mismatch(source, target) != -1L;
                        } catch (IOException ignored) {
                            needsCopy = true;
                        }
                    }

                    if (needsCopy) {
                        Files.createDirectories(targetDir);
                        Files.copy(
                                source,
                                target,
                                StandardCopyOption.REPLACE_EXISTING
                        );
                        copied++;
                    }
                }
            }
        }

        if (!foundSet) {
            System.err.println(
                    "[RTX] shaderSet='" + shaderSet + "' 未在 mod 根目录找到；"
                            + targetDir + " 保持原样（不回退到生产档）。"
                            + "延迟档请先跑 _build_deferred.bat + _deploy_deferred_set.py。"
            );
            return;
        }

        // 无条件打一行：日志里能直接看出这次跑的是哪一档、哪个 gameDir。
        System.out.println(
                "[RTX] shaders: set=" + shaderSet
                        + " target=" + targetDir
                        + " copied=" + copied
        );
    }


    private static void extractBundledDlls(
            Path gameDir
    ) throws IOException {

        final ModContainer mod =
                FabricLoader
                        .getInstance()
                        .getModContainer(MOD_ID)
                        .orElseThrow(
                                () -> new IOException(
                                        "Cannot find Fabric mod container: "
                                                + MOD_ID
                                )
                        );


        int bundledDllCount = 0;


        for (Path root : mod.getRootPaths()) {

            final Path nativeRoot =
                    root.resolve(
                            NATIVE_RESOURCE_DIR
                    );


            if (!Files.isDirectory(nativeRoot)) {
                continue;
            }


            try (Stream<Path> stream =
                         Files.walk(nativeRoot)) {

                final java.util.List<Path> dlls =
                        stream
                                .filter(Files::isRegularFile)
                                .filter(
                                        path ->
                                                path
                                                        .getFileName()
                                                        .toString()
                                                        .toLowerCase(Locale.ROOT)
                                                        .endsWith(".dll")
                                )
                                .toList();


                for (Path source : dlls) {

                    final Path target =
                            gameDir.resolve(
                                    source
                                            .getFileName()
                                            .toString()
                            );


                    boolean needsCopy =
                            true;


                    if (Files.isRegularFile(target)) {

                        try {
                            needsCopy =
                                    Files.size(source) !=
                                            Files.size(target) ||
                                    Files.mismatch(
                                            source,
                                            target
                                    ) != -1L;

                        } catch (IOException ignored) {
                            needsCopy = true;
                        }
                    }


                    if (needsCopy) {

                        Files.copy(
                                source,
                                target,
                                StandardCopyOption.REPLACE_EXISTING
                        );


                        System.out.println(
                                "[NativeVulkan] Extracted native: "
                                        + target
                        );

                    } else {

                        System.out.println(
                                "[NativeVulkan] Native already current: "
                                        + target
                        );
                    }


                    ++bundledDllCount;
                }
            }
        }


        if (bundledDllCount == 0) {
            throw new IOException(
                    "No bundled DLL files found under "
                            + NATIVE_RESOURCE_DIR
            );
        }


        System.out.println(
                "[NativeVulkan] Bundled native DLL count="
                        + bundledDllCount
        );
    }

    public static long currentSwapchainImage = 0;
    public static long currentCommandBuffer = 0;

// =====================================================
// RT -> Minecraft main target -> GUI composition
// =====================================================

    public static long pendingGuiTargetImage = 0L;

    public static int pendingGuiTargetWidth = 0;
    public static int pendingGuiTargetHeight = 0;

    public static boolean guiRtPending = false;

    public static boolean rtInjectedToMainTarget = false;


    public static void resetGuiRtState() {

        pendingGuiTargetImage = 0L;

        pendingGuiTargetWidth = 0;
        pendingGuiTargetHeight = 0;

        guiRtPending = false;

        rtInjectedToMainTarget = false;
    }

    // 在原有的 uploadTextureAtlasNative 下方追加：
    public static native void updateBindlessDescriptorsNative();

    // 在 NativeVulkan.java 中定义：
// type: 0 = Albedo (基础色), 1 = Normal (法线), 2 = Specular (高光/粗糙度)
    public static native void setInWorld(boolean inWorld);

    // =====================================================
    // 2026-10-24 项 1：把原版可见性图的 Section 集合推给 native，
    // 用于 TLAS 主射线掩码剔除（只清 mask 的 bit0，实例保留给次级射线）。
    //
    // coords = [x0,y0,z0, x1,y1,z1, ...]，Section 坐标（不是方块坐标）。
    // count <= 0 表示本帧没有可见性数据，native 侧会自动关闭剔除。
    // 调用点见 LevelRendererMixin.rtx$uploadVisibleSections。
    // =====================================================
    public static native void setVisibleSections(int[] coords, int count);

    static {
        try {
            final Path gameDir =
                    FabricLoader
                            .getInstance()
                            .getGameDir()
                            .toAbsolutePath()
                            .normalize();


            Files.createDirectories(
                    gameDir
            );


            // Extract every bundled Windows DLL to the user's
            // actual Minecraft/Fabric instance directory first.
            extractBundledDlls(
                    gameDir
            );

            // 2026-10-19：同时释放随包分发的 SPIR-V（shaders/ 目录）。
            // 着色器由 C++ 通过**文件路径**加载（不是 classpath），
            // 所以别人装上这个 mod 时必须先落到游戏目录，否则云不渲染。
            //
            // ⚠️ 2026-10-20 **必须 try-catch**：这里一旦抛 IOException，
            // 会中断整个 load()（DLL 都不加载）⇒ 用户看到的就是"原版渲染"。
            // 有一个用户正是这样（rt_debug.log 只有 1 KB、没有 VK INIT）。
            // 着色器解压失败顶多让云不渲染，绝不能让整个 RTX 后端陪葬。
            try {
                extractBundledShaders(
                        gameDir
                );
            } catch (Throwable shaderExtractFailure) {
                System.err.println(
                        "[RTX] bundled shader extraction failed (RTX backend will still load): "
                                + shaderExtractFailure
                );
            }


            final Path backendPath =
                    gameDir.resolve(
                            "rtx_backend.dll"
                    );


            if (!Files.isRegularFile(backendPath)) {
                throw new IOException(
                        "Bundled rtx_backend.dll was not extracted: "
                                + backendPath
                );
            }


            System.out.println(
                    "[NativeVulkan] gameDir="
                            + gameDir
            );


            System.out.println(
                    "[NativeVulkan] backendPath="
                            + backendPath
            );


            System.load(
                    backendPath.toString()
            );


            System.out.println(
                    "[NativeVulkan] Loaded rtx_backend"
            );


            loaded = true;

        } catch(Throwable e) {

            System.err.println(
                    "[NativeVulkan] Native runtime extraction/load failed"
            );

            e.printStackTrace();

            loaded = false;
        }
    }
    // =====================================================
// No-direct-light ambient brightness
//
// range: 0.00 - 1.00
// default: 0.10
// =====================================================

    public static native boolean setNoDirectLightAmbient(
            float value
    );

    public static native float getNoDirectLightAmbient();

    public static boolean setPerformanceSettings(
            RTXSettingsConfig.PerformanceSettings settings
    ) {
        return setPerformanceSettingsNative(
                settings.aoNearSamples(),
                settings.aoMidSamples(),
                settings.giBounces(),
                settings.sunShadowSamples(),
                settings.localLightRisCandidates(),
                settings.localLightAreaSamples(),
                settings.waterReflectionInterval(),
                settings.glossyFullDistance(),
                settings.dynamicRtasInterval(),
                settings.rtSpp(),
                settings.volumetricRestir()
        );
    }

    private static native boolean setPerformanceSettingsNative(
            int aoNearSamples,
            int aoMidSamples,
            int giBounces,
            int sunShadowSamples,
            int localLightRisCandidates,
            int localLightAreaSamples,
            int waterReflectionInterval,
            int glossyFullDistance,
            int dynamicRtasInterval,
            float rtSpp,
            int volumetricRestir
    );

    private NativeVulkan() {}

    public static boolean isLoaded() {
        return loaded;
    }

    public static native void initStreamlineEarly();

    // =====================================================
// FG-2.6
// Streamline Vulkan requirements bridge
// =====================================================

    public static native boolean isFGRequirementsReady();

    public static native String[] getFGInstanceExtensions();

    public static native String[] getFGDeviceExtensions();

    public static native String[] getFGVulkan12Features();

    public static native String[] getFGVulkan13Features();

    public static native int getFGGraphicsQueuesRequired();

    public static native int getFGComputeQueuesRequired();

    public static native int getFGOpticalFlowQueuesRequired();
    // =====================================================
// FG-2.11
// Java Vulkan queue plan -> C++ Streamline bridge
// =====================================================

    public static native void setStreamlineQueuePlan(
            int graphicsFamily,
            int graphicsStartIndex,
            int computeFamily,
            int computeStartIndex,
            int opticalFlowFamily,
            int opticalFlowStartIndex,
            boolean useNativeOpticalFlow
    );

    // =====================================================
// FG-3
// Vulkan Manual Hooking
// =====================================================

    public static native boolean prepareStreamlineVulkan(
            long instance,
            long physicalDevice,
            long device
    );


    public static native int slCreateWin32Surface(
            long instance,
            long hwnd,
            long surfaceOutAddress
    );


    public static native void slDestroySurface(
            long instance,
            long surface
    );


    public static native int slCreateSwapchain(
            long device,
            long createInfoAddress,
            long swapchainOutAddress
    );


    public static native void slDestroySwapchain(
            long device,
            long swapchain
    );


    public static native int slGetSwapchainImages(
            long device,
            long swapchain,
            long countAddress,
            long imagesAddress
    );


    public static native int slAcquireNextImage(
            long device,
            long swapchain,
            long timeout,
            long semaphore,
            long fence,
            long imageIndexAddress
    );


    public static native int slQueuePresent(
            long queue,
            long presentInfoAddress
    );


// =====================================================
// FG-4.1
// HUD-less / Final snapshots + binary UI Alpha
// =====================================================

    public static native void setFGBackbufferFormat(
            int vkFormat
    );


    public static native void captureFGFinalAndBuildUIAlpha(
            long commandBuffer,
            long finalColorImage,
            int width,
            int height
    );


    public static native void testFrame();
    public static native boolean probeHDRSurface(
            long surfaceHandle
    );

    // HDR-2.3: the settings switch, and the surface capability reported from
    // the swapchain format pick (which runs before the swapchain is created).
    public static native void setHDREnabledNative(boolean enabled);
    public static native void setHDRSurfaceSupportedNative(boolean supported);

    // Set when the committed HDR setting changes, so the surface can force a
    // reconfigure - the swapchain format is only re-read on a swapchain rebuild.
    public static volatile boolean hdrSwapchainDirty = false;

    // RR-7 auto exposure: the switch and its one user-facing knob.  Neither
    // needs a swapchain reconfigure - the exposure is applied inside the display
    // compute pass, so both land on the next frame.  Switched off, the running
    // exposure is driven back to neutral rather than frozen, which makes "off"
    // match the pre-auto-exposure build exactly.
    //
    // The compensation is in EV on top of the shader's calibrated key, so 0.0
    // is the tuned look.  The setter reports failure the way the other numeric
    // knobs do; the switch cannot fail.
    public static native void setAutoExposureEnabledNative(boolean enabled);
    public static native boolean setAutoExposureCompensationNative(float ev);
    public static native float getAutoExposureCompensation();

    // =====================================================
    // 2026-10-16 诊断闸门（默认开；=0 / false 关闭）
    //
    // 现场：进程提交量有一条与玩法无关的固定速率增长（Vulkan 后端下约 38MB/s；
    // OpenGL 后端下为 0）。已用 orion.streamline.earlyInit 排除"Streamline 早
    // 初始化/NGX 加载"这一块，本闸门再切一刀：**保留 MC 的 Vulkan 后端，但跳过
    // 我们自己的设备级初始化**（这一步会把 instance/physicalDevice/device/queue
    // 交给 native，进而建 RR/FG/PCL/管线）。
    //   · 关掉后泄漏仍在 ⇒ 与本模组的 native 链无关（MC/driver/注入侧）；
    //   · 关掉后泄漏消失 ⇒ 责任在我们的设备级初始化或它开出的每帧路径。
    // **只用于诊断**：RT/DLSS/FG 全部不可用。
    // 写法照 DhFlags.flag()：先 -D 属性，再大写环境变量；"0"/"false" 都算关。
    // =====================================================
    private static final String DEVICE_INIT_PROPERTY =
            "orion.native.deviceInit";

    private static boolean deviceInitEnabled() {
        String raw =
                System.getProperty(DEVICE_INIT_PROPERTY);

        if (raw == null) {
            raw = System.getenv(
                    DEVICE_INIT_PROPERTY
                            .toUpperCase(java.util.Locale.ROOT)
                            .replace('.', '_')
            );
        }

        if (raw == null) {
            return true;
        }

        final String value = raw.trim();

        return !("0".equals(value) || "false".equalsIgnoreCase(value));
    }

    public static void initDeviceHandles(

            long instance,
            long physicalDevice,
            long device,
            long queue,
            int queueFamily
    )
    {
        if (!deviceInitEnabled()) {
            System.err.println(
                    "[NativeVulkan] device init DISABLED by "
                            + DEVICE_INIT_PROPERTY
                            + " -- the native RT chain stays inert (diagnostic mode)."
            );

            return;
        }

        System.err.println(
                "[JAVA TEST] initDeviceHandles ENTER"
        );

        System.err.println(
                "[JAVA TEST] Calling initDeviceHandlesNative"
        );

        setSerEnabledNative(
                RTXSettingsConfig.isSerEnabled()
        );

        // RR-7: same reasoning as SER -- these are plain device-side switches,
        // so they go across before the native init rather than after it.
        setAutoExposureEnabledNative(
                RTXSettingsConfig.isAutoExposureEnabled()
        );

        setAutoExposureCompensationNative(
                RTXSettingsConfig.getAutoExposureCompensation()
        );

        // 2026-10-28 间接光模式：**必须**在 initDeviceHandlesNative() 之前下发 ——
        // SHaRC 的四块缓冲（176MB）与第二条 RT 管线是在 native init 里按它创建的。
        // 若挪到 directLightMode 那一批（init 之后）就会永远读不到 1，模式永不生效。
        final String indirectModeEnv =
                System.getenv("ORION_INDIRECT_MODE");
        if((indirectModeEnv == null || indirectModeEnv.isEmpty())
                && !setIndirectMode(
                        RTXSettingsConfig.getIndirectMode()
                )) {
            System.err.println("[RTX CONFIG] Failed to restore indirect mode");
        }


        initDeviceHandlesNative(
                instance,
                physicalDevice,
                device,
                queue,
                queueFamily,
                0L,
                0L
        );


        // =====================================================
        // RTX Settings - restore saved NVIDIA Reflex mode
        //
        // 必须位于 initDeviceHandlesNative() 返回之后。
        // 此时 Streamline / Reflex 已完成 native 初始化。
        //
        // 0 = Off
        // 1 = On
        // 2 = On + Boost
        // =====================================================

        if(isReflexAvailable())
        {
            final int savedMode =
                    RTXSettingsConfig.getReflexMode();


            final int currentMode =
                    getReflexMode();


            if(currentMode != savedMode)
            {
                final boolean restored =
                        setReflexMode(
                                savedMode
                        );


                if(restored)
                {
                    System.out.println(
                            "[RTX CONFIG] Restored Reflex mode="
                                    + savedMode
                    );
                }
                else
                {
                    System.err.println(
                            "[RTX CONFIG] Failed to restore Reflex mode="
                                    + savedMode
                    );
                }
            }
            else
            {
                System.out.println(
                        "[RTX CONFIG] Reflex mode already active="
                                + savedMode
                );
            }
        }

        final boolean savedFGFrameSync =
                RTXSettingsConfig.isFGFrameSyncEnabled();
        setFGFrameSync(savedFGFrameSync);
        // 2026-10-22（B2）：刷新率钳制开关随配置恢复。
        setFGRefreshCapEnabled(
                RTXSettingsConfig.isFGRefreshCapEnabled()
        );
        setFGMultiplier(RTXSettingsConfig.getFGMultiplier());
        setFGBaseFrameLimit(RTXSettingsConfig.getFGBaseFrameLimit());

        // 2026-10-22（MV 总开关）：链路一直在，但**从来没有人下发过** ——
        // RTXSettingsConfig.motionVectorsEnabled 只被读进 GUI，原生侧永远停在校验
        // 默认值的 1。这里补上恢复，并顺便用 setMotionVectorScaleMode 下发档位
        // （它同时决定 g_mvScaleMode 与 g_motionVectorsEnabled：0 = 关，1/2/3 = 开）。
        try {
            setMotionVectorScaleMode(
                    RTXSettingsConfig.getMotionVectorsEnabled()
            );
        } catch (Throwable ignored) {
            // 原生未加载时 getMotionVectorScaleMode 会抛 UnsatisfiedLinkError；
            // 这条恢复路径与其余设置组一样，不能在初始化阶段把客户端带崩。
        }

        // 渲染路径恒为 RR：SR 超分入口已从设置界面移除（强依赖 RR）。不再回读
        // 配置里可能残留的 upscalerMode=1(SR)——那会让游戏一起步就落在已无入口
        // 的 SR 路径上。srMode 只在 useSR 时被读取，路径恒 RR 后无需下发。
        setUpscalerMode(RTXSettingsConfig.UPSCALER_MODE_RR);

        // =====================================================
// RTX Settings - restore saved RR quality
// =====================================================

        final int savedRRMode =
                RTXSettingsConfig.getRRMode();

        final int currentRRMode =
                getRRMode();


        if(currentRRMode != savedRRMode)
        {
            final boolean restored =
                    setRRMode(
                            savedRRMode
                    );


            if(restored)
            {
                System.out.println(
                        "[RTX CONFIG] Restored RR mode="
                                + savedRRMode
                );
            }
            else
            {
                System.err.println(
                        "[RTX CONFIG] Failed to restore RR mode="
                                + savedRRMode
                );
            }
        }
        else
        {
            System.out.println(
                    "[RTX CONFIG] RR mode already active="
                            + savedRRMode
            );
        }
        // =====================================================
// RTX Settings - restore no-direct-light ambient
// =====================================================

        final float savedAmbient =
                RTXSettingsConfig.getNoDirectLightAmbient();


        if(setNoDirectLightAmbient(
                savedAmbient
        ))
        {
            System.out.println(
                    "[RTX CONFIG] Restored ambient="
                            + savedAmbient
            );
        }
        else
        {
            System.err.println(
                    "[RTX CONFIG] Failed to restore ambient="
                            + savedAmbient
            );
        }

        final float savedPOMDepth =
                RTXSettingsConfig.getPOMDepth();


        if(setPOMDepth(
                savedPOMDepth
        ))
        {
            System.out.println(
                    "[RTX CONFIG] Restored POM depth="
                            + savedPOMDepth
            );
        }
        else
        {
            System.err.println(
                    "[RTX CONFIG] Failed to restore POM depth="
                            + savedPOMDepth
            );
        }

        final boolean savedPOMSharp =
                RTXSettingsConfig.isPOMSharp();


        if(setPOMSharp(
                savedPOMSharp
        ))
        {
            System.out.println(
                    "[RTX CONFIG] Restored POM sampling sharp="
                            + savedPOMSharp
            );
        }
        else
        {
            System.err.println(
                    "[RTX CONFIG] Failed to restore POM sampling sharp="
                            + savedPOMSharp
            );
        }

        final boolean savedSSSMode =
                RTXSettingsConfig.isTrueSubsurfaceScattering();


        if(setSSSMode(
                savedSSSMode
        ))
        {
            System.out.println(
                    "[RTX CONFIG] Restored true subsurface scattering="
                            + savedSSSMode
            );
        }
        else
        {
            System.err.println(
                    "[RTX CONFIG] Failed to restore true subsurface scattering="
                            + savedSSSMode
            );
        }

        // =====================================================
        // RTX Settings - restore local light intensity
        // =====================================================

        final float savedLocalLightIntensity =
                RTXSettingsConfig.getLocalLightIntensity();


        if(setLocalLightIntensity(
                savedLocalLightIntensity
        ))
        {
            System.out.println(
                    "[RTX CONFIG] Restored local light intensity="
                            + savedLocalLightIntensity
            );
        }
        else
        {
            System.err.println(
                    "[RTX CONFIG] Failed to restore local light intensity="
                            + savedLocalLightIntensity
            );
        }

        RTXSettingsConfig.SunVisualSettings sunVisuals =
                RTXSettingsConfig.getSunVisualSettings();
        if(!setSunVisualSettings(
                sunVisuals.diskLuminance(),
                sunVisuals.haloStrength(),
                sunVisuals.angularDiameterDegrees()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore sun visual settings");
        }

        RTXSettingsConfig.AtmosphereSettings atmosphere =
                RTXSettingsConfig.getAtmosphereSettings();
        if(!setAtmosphereSettings(
                atmosphere.starIntensity(),
                atmosphere.sunTintStrength(),
                atmosphere.physicalSkyEnabled(),
                atmosphere.scatterIntensity(),
                atmosphere.ozoneStrength()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore atmosphere settings");
        }
        if(!setPowderStrength(
                RTXSettingsConfig.getPowderStrength()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore powder strength");
        }
        if(!setCloudSunForwardScattering(
                RTXSettingsConfig.getCloudSunForwardScattering()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud sun forward scattering");
        }
        if(!setAuroraBrightness(
                RTXSettingsConfig.getAuroraBrightness()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore aurora brightness");
        }
        if(!setAuroraFlowSpeed(
                RTXSettingsConfig.getAuroraFlowSpeed()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore aurora flow speed");
        }
        if(!setCloudSunReddening(
                RTXSettingsConfig.getCloudSunReddening()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud sun reddening");
        }
        if(!setCloudTopStretch(
                RTXSettingsConfig.getCloudTopStretch()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud top stretch");
        }
        if(!setCloudDetileStrength(
                RTXSettingsConfig.getCloudDetileStrength()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud detile strength");
        }
        if(!setCloudType(
                RTXSettingsConfig.getCloudType()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud type");
        }
        if(!setCloudErosionScale(
                RTXSettingsConfig.getCloudErosionScale()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud erosion scale");
        }
        if(!setCloudWeatherScale(
                RTXSettingsConfig.getCloudWeatherScale()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud weather scale");
        }
        if(!setCloudSilver(
                RTXSettingsConfig.getCloudSilver()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud silver");
        }
        if(!setCloudViewDistance(
                RTXSettingsConfig.getCloudViewDistance()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud view distance");
        }
        // 2026-10-04 S2/S3：远云分辨率下限与远段步长加密（0 / 1.0 = 旧行为）。
        if(!setCloudResFloorPercent(
                RTXSettingsConfig.getCloudResFloorPercent()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud res floor");
        }
        if(!setCloudFarStepDensity(
                RTXSettingsConfig.getCloudFarStepDensity()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud far step density");
        }
        // 2026-10-07 Perf-L10 实测后的两项 RT 优化（写 CameraUBO.rtSampleParams
        // 的 .y/.z；这两格当时是保留 lane，现在已有读者 —— 见 camera_ubo.glsl 声明注释）。
        if(!setGiRoulette(
                RTXSettingsConfig.getGiRoulette()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore GI roulette");
        }
        if(!setSunMarchFarDistance(
                RTXSettingsConfig.getSunMarchFarDistance()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore sun march far distance");
        }
        // 2026-10-26 直接光模式 / 稀疏渲染（CameraUBO.sparseParams 尾块）。
        // env 非空时**不下发**配置值：native 的三级解析是「UI 覆盖 → env → 出厂」，
        // 这里若无条件下发，启动恢复会永远压住 ORION_* 实验入口（IDE 运行配置里
        // 设了也白设）。在界面上点应用才算一次显式覆盖。
        final String directLightModeEnv =
                System.getenv("ORION_DIRECT_LIGHT_MODE");
        if((directLightModeEnv == null || directLightModeEnv.isEmpty())
                && !setDirectLightMode(
                        RTXSettingsConfig.getDirectLightMode()
                )) {
            System.err.println("[RTX CONFIG] Failed to restore direct light mode");
        }
        final String sparseRateEnv =
                System.getenv("ORION_SPARSE_RATE");
        if((sparseRateEnv == null || sparseRateEnv.isEmpty())
                && !setSparseRatePercent(
                        RTXSettingsConfig.getSparseRatePercent()
                )) {
            System.err.println("[RTX CONFIG] Failed to restore sparse rate percent");
        }
        // 2026-10-09 运动矢量调试视图 / 总开关（写 perfAblationStage 的 .y/.z；
        // 这两格当时是保留 lane，现在已有读者 —— 见 camera_ubo.glsl 声明注释）。
        if(!setMotionDebugView(
                RTXSettingsConfig.getMotionDebugView()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore motion debug view");
        }
        // 2026-10-22 定案：运动矢量不再是用户可调项。
        // 固定档位 1 = "MV 数据是像素位移 ⇒ mvecScale = 1/w, 1/h"（实测正确）；
        // 层掩码在 native 内部固定为"只发 ENTITY（生物/掉落物）"，
        // 粒子层与玩家自身层都不发（那是"整世界鬼影"的来源）。
        // 需要实验时仍可用 ORION_MV_LAYERS / ORION_DIAG_* 环境变量覆盖。
        setMotionVectorScaleMode(1);
        if(!setCloudMarchParams(
                RTXSettingsConfig.getCloudMarchSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud march settings");
        }
        // 2026-10-03 远淡出跟随 RR 档位（默认开；关掉回到旧行为）。
        if(!setCloudFadeFollowsRrMode(
                RTXSettingsConfig.isCloudFadeFollowsRrMode()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud far fade mode");
        }
        // 2026-10-06 远云剥离 RR（默认开；关掉回到"云进 RR 颜色输入"的旧档）。
        if(!setCloudPostRrComposite(
                RTXSettingsConfig.isCloudPostRrComposite()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud post-RR composite");
        }
        if(!setWaterParams(
                RTXSettingsConfig.getWaterParamsSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water params");
        }
        // 2026-10-02 水面波面参考重构（HPWater）：波面法线侧 + 波动方程侧两组。
        if(!setWaterWaveParams(
                RTXSettingsConfig.getWaterWaveSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water wave params");
        }
        if(!setWaterSimWaveParams(
                RTXSettingsConfig.getWaterSimWaveSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water sim wave params");
        }
        if(!setWaterFftParams(
                RTXSettingsConfig.getWaterFftSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water FFT params");
        }
        // 2026-10-13 afl getwaves 波面后端（复用 waterWaveShapeParams 尾块）。
        if(!setWaterWaveAflParams(
                RTXSettingsConfig.getWaterWaveAflSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water wave afl params");
        }
        // 2026-10-03 TLAS 剔除距离 0 = **自适应**（游戏视距 × 1.25，随视距设置联动）；
        // 启动恢复时游戏视距可直接读，无需等玩家打开设置。
        int restoredTlasCull =
                RTXSettingsConfig.getPostProcessingSettings().tlasCullDistance();
        if(restoredTlasCull == 0) {
            try {
                restoredTlasCull = Math.min(512, Math.max(64,
                        Minecraft.getInstance().options.renderDistance().get() * 16 * 5 / 4));
            } catch(Throwable ignored) {
                restoredTlasCull = 0;
            }
        }
        if(!setPostProcessingParams(new float[] {
                RTXSettingsConfig.getPostProcessingSettings().tonemapMode(),
                RTXSettingsConfig.getPostProcessingSettings().exposureStrength(),
                RTXSettingsConfig.getPostProcessingSettings().contrast(),
                RTXSettingsConfig.getPostProcessingSettings().saturation(),
                RTXSettingsConfig.getPostProcessingSettings().gamma(),
                RTXSettingsConfig.getPostProcessingSettings().mirrorBounceCount(),
                restoredTlasCull,
                RTXSettingsConfig.getPostProcessingSettings().tlasFrustumCullMargin(),
                RTXSettingsConfig.getPostBloomStrength(),
                RTXSettingsConfig.getPostGainR(),
                RTXSettingsConfig.getPostGainG(),
                RTXSettingsConfig.getPostGainB() })) {
            System.err.println("[RTX CONFIG] Failed to restore post processing params");
        }
        // 2026-10-26 色彩分级 24 项（「色彩映射与调色」页）。与后处理参数分开下发，
        // 因为它走的是独立的 push lane（PostProcessingPush 的 lane 8..31）。
        if(!setColorGradeParams(
                RTXSettingsConfig.getColorGradeSettings().toParamArray())) {
            System.err.println("[RTX CONFIG] Failed to restore color grade params");
        }
        // 2026-10-03 远云 guide 补写：0 = 关闭（回到"远处云也走 RR 的错误 guide"）。
        if(!setRrGuideSettings(
                true,
                RTXSettingsConfig.getPostProcessingSettings().rrFarCloudDistance())) {
            System.err.println("[RTX CONFIG] Failed to restore RR guide settings");
        }
        if(!setWaterWaveShapeParams(
                RTXSettingsConfig.getWaterWaveShapeSettings().toParamArray()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water wave shape params");
        }

        RTXSettingsConfig.CloudSettings clouds =
                RTXSettingsConfig.getCloudSettings();
        RTXSettingsConfig.CloudMsSettings cloudMs = RTXSettingsConfig.getCloudMsSettings();
        if(!setCloudMsParams(cloudMs.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud ms settings");
        RTXSettingsConfig.CloudHpSettings cloudHp = RTXSettingsConfig.getCloudHpSettings();
        if(!setCloudHpParams(cloudHp.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud hp settings");
        RTXSettingsConfig.PlaneCloudSettings planeCloud =
                RTXSettingsConfig.getPlaneCloudSettings();
        if(!setPlaneCloudParams(planeCloud.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore plane cloud settings");
        RTXSettingsConfig.CloudPhiSettings cloudPhi = RTXSettingsConfig.getCloudPhiSettings();
        if(!setCloudPhiParams(cloudPhi.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud phi settings");
        RTXSettingsConfig.CloudLightSettings cloudLight = RTXSettingsConfig.getCloudLightSettings();
        if(!setCloudLightParams(cloudLight.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud light settings");
        RTXSettingsConfig.CloudShapeSettings cloudShape = RTXSettingsConfig.getCloudShapeSettings();
        if(!setCloudShapeParams(cloudShape.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud shape settings");
        RTXSettingsConfig.CloudMoonSettings cloudMoon = RTXSettingsConfig.getCloudMoonSettings();
        if(!setCloudMoonParams(cloudMoon.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud moon settings");
        RTXSettingsConfig.CloudShape2Settings cloudShape2 = RTXSettingsConfig.getCloudShape2Settings();
        if(!setCloudShape2Params(cloudShape2.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud shape2 settings");
        // 2026-10-02 参考重构 #2~#7（五组尾块参数）。
        RTXSettingsConfig.CloudAmbientSettings cloudAmbient =
                RTXSettingsConfig.getCloudAmbientSettings();
        if(!setCloudAmbientParams(cloudAmbient.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud ambient settings");
        // 2026-10-14 日落天光修正。
        RTXSettingsConfig.CloudSkyHueSettings cloudSkyHue =
                RTXSettingsConfig.getCloudSkyHueSettings();
        if(!setCloudSkyHueParams(cloudSkyHue.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud sky hue settings");
        // 2026-10-15 体积云物理性质补齐。
        RTXSettingsConfig.CloudPhysicsSettings cloudPhysics =
                RTXSettingsConfig.getCloudPhysicsSettings();
        if(!setCloudPhysicsParams(cloudPhysics.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud physics settings");
        RTXSettingsConfig.CloudMs2Settings cloudMs2 = RTXSettingsConfig.getCloudMs2Settings();
        if(!setCloudMs2Params(cloudMs2.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud ms2 settings");
        RTXSettingsConfig.CloudLight2Settings cloudLight2 = RTXSettingsConfig.getCloudLight2Settings();
        if(!setCloudLight2Params(cloudLight2.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud light2 settings");
        RTXSettingsConfig.CloudProfileSettings cloudProfile =
                RTXSettingsConfig.getCloudProfileSettings();
        if(!setCloudProfileParams(cloudProfile.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud profile settings");
        RTXSettingsConfig.CloudDetailSettings cloudDetail =
                RTXSettingsConfig.getCloudDetailSettings();
        if(!setCloudDetailParams(cloudDetail.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud detail settings");
 // 2026-10-10（#3/#4 云时域 + #5/#6 雾附加）。
        RTXSettingsConfig.CloudTemporalSettings cloudTemporal =
                RTXSettingsConfig.getCloudTemporalSettings();
        if(!setCloudTemporalParams(cloudTemporal.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud temporal settings");
        RTXSettingsConfig.FogExtrasSettings fogExtras = RTXSettingsConfig.getFogExtrasSettings();
        if(!setFogExtrasParams(fogExtras.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore fog extras settings");
        RTXSettingsConfig.FogWispSettings fogWisp = RTXSettingsConfig.getFogWispSettings();
        if(!setFogWispParams(fogWisp.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore fog wisp settings");
        // 2026-10-10 借鉴（#1 云后空气透视 + #2 地面遮挡衰减）。
        RTXSettingsConfig.CloudAerialSettings cloudAerial =
                RTXSettingsConfig.getCloudAerialSettings();
        if(!setCloudAerialParams(cloudAerial.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore cloud aerial settings");
        // 2026-10-03 薄层 SSS。
        RTXSettingsConfig.WaterSssSettings waterSss = RTXSettingsConfig.getWaterSssSettings();
        if(!setWaterSssParams(waterSss.toParamArray()))
            System.err.println("[RTX CONFIG] Failed to restore water SSS settings");
        if(!setCloudSettings(
                clouds.enabled(),
                clouds.bottom(),
                clouds.thickness(),
                clouds.coverage(),
                clouds.density(),
                clouds.detailStrength(),
                clouds.windSpeed(),
                clouds.evolution(),
                clouds.quality(),
                clouds.size(),
                clouds.distribution(),
                clouds.baseFeather(),
                clouds.sunlightStrength()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore cloud settings");
        }

        RTXSettingsConfig.VolumetricFogSettings fog =
                RTXSettingsConfig.getVolumetricFogSettings();
        if(!setVolumetricFogSettings(
                fog.enabled(),
                fog.density(),
                fog.height(),
                fog.scaleHeight(),
                fog.anisotropy(),
                fog.sunStrength(),
                fog.ambientStrength(),
                fog.maxDistance(),
                fog.startDistance(),
                fog.sunSteps(),
                fog.rainBoost()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore volumetric fog settings");
        }
        // 2026-10-06 设置清理：雾形态开关（setFogBlobModeNative）随 fogBlobs 删除
        // —— 密度模型已统一成单一公式，不再有"仅高度衰减 / 团雾"两个分支。

        RTXSettingsConfig.VolumetricLightSettings godRays =
                RTXSettingsConfig.getVolumetricLightSettings();
        if(!setVolumetricLightSettings(
                godRays.enabled(),
                godRays.steps(),
                godRays.occlusionSamples(),
                godRays.intensity()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore volumetric light settings");
        }
        if(!setGodRayReprojection(godRays.reprojection())) {
            System.err.println("[RTX CONFIG] Failed to restore volumetric light reprojection");
        }

        RTXSettingsConfig.RainWallSettings rainWall =
                RTXSettingsConfig.getRainWallSettings();
        if(!setRainWallSettings(
                rainWall.enabled(),
                rainWall.wetness(),
                rainWall.dripDensity(),
                rainWall.dripSpeed(),
                rainWall.dripSize(),
                rainWall.dripBump(),
                rainWall.distance()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore rain wall settings");
        }

        RTXSettingsConfig.WaterStyleSettings waterStyle =
                RTXSettingsConfig.getWaterStyleSettings();
        if(!setWaterStyleSettings(
                waterStyle.waveScale(),
                waterStyle.tintMix(),
                waterStyle.densityScale(),
                waterStyle.causticStrength(),
                waterStyle.waveSpeed(),
                waterStyle.swellScale(),
                waterStyle.midScale(),
                waterStyle.fineScale()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore water style settings");
        }

        RTXSettingsConfig.GalaxySettings galaxy =
                RTXSettingsConfig.getGalaxySettings();
        if(!setGalaxySettings(
                galaxy.scale(),
                galaxy.tiltDegrees(),
                galaxy.yawDegrees(),
                galaxy.mix()
        )) {
            System.err.println("[RTX CONFIG] Failed to restore galaxy settings");
        }

        if(!setPerformanceSettings(
                RTXSettingsConfig.getPerformanceSettings()
        ))
        {
            System.err.println("[RTX CONFIG] Failed to restore performance settings");
        }
    }


    private static native void initDeviceHandlesNative(

            long instance,
            long physicalDevice,
            long device,
            long queue,
            int queueFamily,
            long getInstanceProcAddr,
            long getDeviceProcAddr
    );

    private static native void setSerEnabledNative(
            boolean enabled
    );

    public static native void recordFrameCommand(
            long commandBufferHandle,
            long targetImageHandle,
            int width,
            int height,
            boolean targetIsMainRenderTarget
    );
// =====================================================
// NVIDIA Reflex
//
// mode:
// 0 = Off
// 1 = On
// 2 = On + Boost
// =====================================================

    public static native boolean setReflexMode(int mode);

    public static native boolean isReflexAvailable();

    public static native int getReflexMode();

    // DLSS-G queue synchronization. True keeps generated-frame work ordered
    // with the presenting client queue.
    public static native boolean setFGFrameSync(boolean enabled);

    // 2026-10-22（B2）：按显示器刷新率给帧生成倍率设上限。
    // 官方 DLSS-G §7.4：60Hz 安全上限 4×、75Hz 5×，再高只是把渲染时间换成
    // 队列延迟。关掉后只受驱动 numFramesToGenerateMax 限制。
    public static native boolean setFGRefreshCapEnabled(boolean enabled);

    public static native boolean getFGRefreshCapEnabled();

    // 2026-10-22（D2）：Reflex Flash 指示器（RFI）。左键按下的**那一沿**发一次。
    // 原生侧会校验 ReflexState::flashIndicatorDriverControlled，未由驱动接管时
    // 直接返回 false（不发无意义的 marker）。
    public static native boolean pclTriggerFlash();

    // 2026-10-22（D2）：PCL 延迟测量 ping（PC Latency Stats 热键那条）。
    // 目前没有热键绑定入口，方法先暴露出来，接上输入层后即可使用。
    public static native boolean pclLatencyPing();

    // 2026-10-22（D3）：是否从 natives 目录下的 sl_debug_dev\ 加载
    // development 构建（仅它带 sl.imgui）。**必须在 initStreamlineEarly 之前**
    // 调用，且**下次启动才生效**：interposer 一旦加载就无法换版本。
    public static native boolean setStreamlineDevPluginsEnabled(boolean enabled);

    // DLSS Frame Generation multiplier (倍增值): 2 = 2x, 4 = 4x, 6 = 6x.
    // Stored as a request; native clamps it against numFramesToGenerateMax on
    // the next apply, so asking for 4x on Ada without the multi-frame unlock
    // runs at 2x instead of failing.
    public static native boolean setFGMultiplier(int multiplier);

    // Ceiling the DLSS-G plugin reports, in multiplier units (2 = 2x). It reads
    // 2 until the first frame-generation state query, which needs a world.
    public static native int getFGMultiplierMax();

    // Base frame-rate cap for frame generation, in rendered frames per second:
    // -1 pairs it with the display (refresh / multiplier), 0 removes it, and a
    // positive value is an explicit target. Pushed to the Reflex frame limiter,
    // which is the pacing path Streamline names for use with DLSS-G.
    public static native boolean setFGBaseFrameLimit(int fps);

    // Hz the cap resolved to, so the label can show what "automatic" means.
    public static native int getFGBaseFrameLimit();

    // Refresh rate of the display the desktop currently runs on.
    public static native int getDisplayRefreshRateHz();

    // Pushed every frame: true while a full-screen menu (or the main menu) owns
    // the screen. Frame generation is switched off for those frames and the base
    // frame-rate cap is lifted with it, so a menu never renders at the paired
    // rate.
    public static native boolean setFullscreenMenuOpen(boolean open);
    // =====================================================
// DLSS Ray Reconstruction quality
//
// 0 = Performance
// 1 = Balanced
// 2 = Quality
// 3 = DLAA
// =====================================================

    public static native boolean setRRMode(int mode);

    public static native int getRRMode();

    public static native boolean setUpscalerMode(int mode);
    public static native boolean setSRMode(int mode);


    public static native boolean beginStreamlineFrame();
    public static native boolean markSimulationStart();

    public static native boolean markSimulationEnd();
    public static native boolean markRenderSubmitStart();

    public static native boolean markRenderSubmitEnd();
    public static native boolean markPresentStart();

    public static native boolean markPresentEnd();
    public static native void cleanup();
}
