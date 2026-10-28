// ===========================================================================
// camera_ubo.glsl —— `CameraProperties` 块体的**唯一真源**（std140）。
//
// 用法：块体里 include，绑定号与实例名留在各着色器自己那边 ——
//
//     layout(set = 0, binding = N) uniform CameraProperties
//     {
//     #include "camera_ubo.glsl"
//     } cam;
//
// 契约
// ----
// 1. 本文件的成员**顺序与类型**必须与 cpp/VulkanCore.cpp 的 `struct CameraUBO`
//    逐项对应。偏移由 tools/check_camera_ubo.py 机械校验（编译本头 → 反汇编
//    SPIR-V → 对照 C++ 的 static_assert 与 sizeof）。改完请跑：
//        python tools/check_camera_ubo.py
// 2. 新增字段**只能追加在末尾**。std140 允许块提前结束，但**不允许跳字段继续**
//    —— 中间插入会让后面每一个读整体平移，而且编译器不报错。追加的代价只有一
//    处（宿主结构体 + 本文件），**消费方着色器不需要改**。
// 3. 2026-10-08 之前，本块是每个着色器各自手抄一份「前缀」（谁读到哪就抄到哪），
//    于是"改一个字段要记得去改 N 个文件"是本工程最容易出静默错位的地方：
//      · volumetric_light.comp 早期从 cloudParams9 直接跳到 godRayParams，漏了
//        32 字节的 skyParams ⇒ godRayParams0 落在 skyParams0 上，把太阳盘亮度
//        当成自己的开关；
//      · 某处漏了 cloudMoonParams(1120) ⇒ 后面每块平移 16 字节，云型读到相邻 lane。
//    现在统一在这里：着色器一律声明全链（未读成员零成本，std140 不会为它们发
//    任何 load），一致性由脚本兜住。
//
// 唯一的局部别名已取消：贴地薄雾那四个 lane 现在统一叫 godRayParams1
// （原生是 godRayParams[4..7]）。见 volumetric_fog.glsl 顶部注释。
// ===========================================================================

    mat4 viewInverse;
    mat4 projInverse;
    uvec2 vertexBufferAddr;
    uint frameCount;
    uint padding;

    // x near, y far, z fov, w reserved
    vec4 cameraParams;

    uint historyReadIndex;
    uint historyValid;
    uint historyPadding0;
    uint historyPadding1;

    // Perf-L10 性能控制位（两个 uvec4，先于 history 那一段）。
    uvec4 perfPrimary;
    uvec4 perfSecondary;

    mat4 prevViewInverse;
    mat4 prevProjInverse;

    // x/y = 上一帧 jitter, zw reserved
    vec4 historyParams;

    vec4 playerPositionAndWater;
    vec4 playerVelocityAndSpeed;

    // x = 水面世界 Y, yz = 水模拟窗口原点 XZ, w = 每纹素世界尺寸
    vec4 waterSimSurfaceAndOrigin;
    uvec4 waterSimState;
    vec4 waterSimImpulse;

    // x rain[0,1], y thunder[0,1], z 维度码（1 主世界 / 2 下界 / 3 末地）, w 保留
    vec4 weatherParams;

    // ── cloudParams[40] ──
    vec4 cloudParams0; // bottom, thickness, coverage, density
    vec4 cloudParams1; // detail strength, wind speed, evolution, enabled
    vec4 cloudParams2; // view steps, light steps, extinction, history weight
    vec4 cloudParams3; // world anchor x/z, cloud size, distribution variation
    vec4 cloudParams4; // base feather, reserved
    // x/y：2026-10-16 移除「云顶/云底亮度」手动倍率后保留写 0（着色器不再读；
    //      云顶/底明暗改由 upwardAO 遮蔽与 (1-height) 权重物理决定）。
    // z = 太阳照云强度，w = 云粉状暗边（powder）。
    vec4 cloudParams5; // x/y reserved, sunlight strength, powder strength
    vec4 cloudParams6;
    vec4 cloudParams7; // fog enabled, amount, band lift above ground, band height
    vec4 cloudParams8; // fog noise scale, wind speed, detail layers, view distance
    vec4 cloudParams9; // fog steps, extinction, rain boost, dawn/dusk boost

    // ── skyParams[8] ──
    // x = sun disk luminance, y = halo strength, z = angular diameter, w = stars
    vec4 skyParams0;
    // x = sun-tint strength, y = physical sky enabled, z = scatter, w = ozone
    vec4 skyParams1;

    // ── godRayParams[12] ──
    // x = enabled, y/z = 雾太阳项自阴影步长/步数, w = 强度（设置界面「体积光强度」）
    vec4 godRayParams0;
    // 贴地薄雾四件套（原生 godRayParams[4..7]，见 volumetric_fog.glsl）：
    // x = enabled, y = 离地高度（格）, z = 消光, w = march 距离（格）
    vec4 godRayParams1;
    // x = march steps, y = occlusion samples, z/w = reserved
    vec4 godRayParams2;

    // ── rainWallParams[8] ──
    vec4 rainWallParams0; // enabled, wetness scale, drip density, drip speed scale
    vec4 rainWallParams1; // trail scale, effect distance, drip bump, reserved

    // ── waterStyleParams[8] ──
    // x wave scale, y tint mix, z density scale, w caustic strength
    vec4 waterStyleParams0;
    // x = wave speed multiplier (0..3, shipped 1.0) —— 水下波闪与水面波带同速
    vec4 waterStyleParams1;

    // Perf-L10.7 会话内 SER A/B 开关（x = toggle）。刻意**独立尾块**：本读者会读满
    // perfPrimary/perfSecondary 的每个 lane，共用会把 A/B 偏向那一格驱动的特性。
    uvec4 perfSerAb;

    // ── Distant Horizons 远场 ──
    // x = 远场可用位, y = DH 矩阵管线有效位, z = DH 自己的近平面, w = DH 自己的远平面
    vec4 dhFarFieldParams;
    // DH 逆 MVP（只用于求**标量距离**，位置锚在我们自己的主射线上）
    mat4 dhFarFieldInvMvp;
    // x = stage mode, y = relight strength, z = relight gain, w = variant override
    vec4 dhFarFieldLight;
    // DH 自己的前向 MVP（按**方向**重采样远场图像；w = 0 故不需要相机位置）
    mat4 dhFarFieldMvp;

    // 远场太阳阴影图：x = extentXY(格), y = extentZ(格), z = 1/分辨率, w = 启用位
    vec4 sunShadowFarParams;

    // ── highCloudParams[20] ──
 // 2026-10-09 起为**平面云**：
    //   hc0 = 启用, 高度偏移（距体积云顶，格）, 尺度, 速度
    //   hc1 = 密度, 覆盖率, 八度数, 淡出距离（格）
    //   hc2 = 亮度, 保留, 保留, 保留
    //   hc3 / hc4 = 保留（宿主写 0）
    // 2026-10-02~10-09 期间这里是退役的 photon 双层薄板（20 项旋钮）；退役期间
    // 宿主 populateHighCloudParams 写全 0。这一段 lane 的**大小与偏移从未变过**
    // （944，紧接 sunShadowFarParams），所以复用不触发任何错位。
    vec4 highCloudParams0;
    vec4 highCloudParams1;
    vec4 highCloudParams2;
    vec4 highCloudParams3;
    vec4 highCloudParams4;

    vec4 cloudMsParams;    // phi 强度, 云底深度形状, 云底偏移, 散射级联
    vec4 cloudMarchParams; // 自阴影光程, 两级步进, 保留, 平均深度重投影

    // 水体观感尾块（偏移 1056）：
    // x = 波峰透光强度（0=关，默认 1；HPWater diffT 背光透射）
    // y = 焦散色散（0=关，默认 0.4；法线陡度近似的三通道色散）
    // z = 焦散水深弥散（0=关，默认 0.5；密度随水深向 1 回退）
    // w = 水下物理相位（0/1，默认 0；5% Rayleigh + 95% Mie + 各向同性化）
    vec4 waterParams;

    vec4 cloudPhiParams;
    vec4 cloudLightParams;
    // x = 云底软化区间（<0 回落编译期宏；0 = 关闭）
    // y = 云底软化曲线指数（<=0 回落宏）
    // z = 两级步进占用阈值（<0 回落宏）
    // w = 保留
    vec4 cloudShapeParams;
    // ⚠ 不可省：漏掉它会让后面每一块整体错位 16 字节。
    vec4 cloudMoonParams;
    // 细节尺度 / 细节对比 / 廓形曲线（密度场共享）
    vec4 cloudShape2Params;

    // ── 参考重构（#2~#7）尾块，布局与 volumetric_clouds.comp 的声明一致 ──
    vec4 cloudAmbientParams;  // #2 环境光接天空
    vec4 cloudMsParams2;      // #6 散射染色 / MS 三参数
    // x = 光路红化强度（#5）；y = 云光路大气消光（0..1，<0 回落编译期宏 1.0；
    //     2026-10-10 逐样本 Chapman 斜程高度增益，见 sky_chapman_common.glsl）；
    // z = 粉末口径（#7）；w = 粉末强度
    vec4 cloudLightParams2;
    vec4 cloudProfileParams;  // #3 剖面云型（密度场共享）
    vec4 cloudDetailParams;   // #4 两族侵蚀（密度场共享）

    // 水面波面参考重构（偏移 1232）：
    // x = 交互场法线标定 / y = 交互场强度倍率 / z = 波面混合模式
    // w = 反射焦散强度（2026-10-20 复用原保留 lane，绝对值 0..8，出厂 4；
    //     0 = 关。唯一消费者 = rt_test.rgen 的 traceWaterReflectedSun，
    //     与折射那份的「焦散亮度」waterStyleParams0.w 各走各的）
    vec4 waterWaveParams;
    // 波形本体（偏移 1248）：波长倍率 / 主波向（度）/ 涌浪陡度 / 波峰扭曲
    // ⚠ 2026-10-13 该 16 字节已**复用**为「afl getwaves 波面后端」三件套
    // （x 波形尺度格 λ₀ / y 域扭曲 drag / z 细节层数，见 water_wave_field.glsl 的
    // WATER_WAVES_AFL）。旧的波形本体四件套只喂 water_sim 的 push constant，
    // 不再进这个尾巴。名字与偏移均未变 ⇒ 布局不动。
    vec4 waterWaveShapeParams;
    // 薄层 SSS（偏移 1264）：x 非线性光程混合 / y 散射能量补偿 / z 相位 g / w 总强度倍率
    vec4 waterSssParams;
    // 原生是 `float waterFftParams[8]`（1280..1312）⇒ 本工程约定一律拆成同名的两个
    // vec4（名字必须逐字如此，water_seascape.glsl 就是这么读的）：
    //   x 平铺周期(格) / y 强度倍率（0 = 关）/ z 波峰锐化 / w 浪速
    vec4 waterFftParams;
    // x = 波浪主向（度）/ y = 镜面弹射层数 / z = 涌浪强度（FFT 涌浪底倍率，
    //   2026-10-15；宿主在 FFT 模块不可用或海浪总开关关时恒写 0）/ w 保留
    vec4 waterFftParams2;

    // 极光（偏移 1312）：x 生物群系强度 / y 亮度倍率（用户滑杆，默认 1）/ z 流动速度倍率（用户滑杆，默认 1）/ w 保留
    vec4 auroraParams;
    // 每像素采样数（偏移 1328）：x = spp（0.5 / 1 / 2 / 3 / 4）
    //   y = GI 俄罗斯轮盘开关（0/1，rt_test.rgen:622 读）
    //   z = 太阳透射 march 远距简化阈值（格，rt_test.rgen:644 读）
    //   w = 平滑后实测 GPU 帧时间 ms（0 = 未启用，rt_test.rgen 水自适应 LOD 读）
    // ⚠ y/z/w 早就不保留了（2026-10-07/08 起陆续占用）；别再按"保留 lane"往里塞新东西。
    vec4 rtSampleParams;
    // Perf-L10 RT 分段剥离的「级」（偏移 1344）：x = stage 0..14
    //   y = MV 调试图档位（0/1/2，rgen 末尾读）  z = MV 总开关（0/1，rgen 写 MV 图处读）
    //   w = 表面临时诊断车道（由 surface_*.flag 标记文件选档，rgen 末尾读）
    // ⚠ y/z/w 早就不保留了（2026-10-09/14 起陆续占用）；同上，别再当保留 lane 用。
    uvec4 perfAblationStage;
    // 末地星系（偏移 1360）：x 尺寸 / y 倾角 / z 偏航 / w 保留
    vec4 galaxyParams;

    // ── 雾附加项（偏移 1376）──
 // 2026-10-23 语义换新：原来这里装的是 2026-10-10 
    // 彩虹 / 闪电 / 晨雾 / 晨雾标高，那三项自 2026-10-19 体积雾重做后
    // **没有任何着色器读取点**（空旋钮）。现在装四项雾扩展：
    //   x = 动态光源强度（火把/岩浆/发光方块进雾的 in-scatter；0 = 关）
    //   y = 地面锚定强度（密度带锚到逐列地面；0 = 绝对世界 Y 带）
    //   z = 天空环境项强度（环境入散射接 clearSkyInscatter；0 = 旧两色插值）
    //   w = 时域抖动强度（逐帧低差异相位；0 = 固定 4×4 Bayer）
    // 唯一消费者：volumetric_clouds*.comp 的三个雾调用点 + volumetric_fog.glsl。
    // 四项 0 = 逐位回到 2026-10-19 的 B 方案移植版。详见 volumetric_fog.glsl 头注释。
    vec4 fogExtrasParams;
    // 云时域/天光质量旋钮（偏移 1392）：
    //   x = 天光自遮挡强度（#3：竖直向上 march 求天光可见度；0 = 关，用解析近似）
    //   y = 天光自遮挡步数（<=1 回落编译期 CLOUD_SKY_VIS_STEPS，上限 CLOUD_SKY_VIS_MAX_STEPS）
    //   z = 时域速度抑制强度（#4：按相机速度连续压 history；0 = 关，用旧硬台阶）
    //   w = 底部遮蔽强度（2026-10-16：云底环境光的权重从几何代理 (1-h) 换成真实
    //       向下光学深度。与 x 共用同一条竖直 march —— 按 h 分配上下采样数，
    //       **总采样数不变** ⇒ 零新增成本。0 = 关 = 逐位旧档）
    vec4 cloudTemporalParams;

    // ── 2026-10-10 借鉴尾块（偏移 1408）──
    // 解析晴空大气透视（clear_sky_aerial.glsl）的两个消费点各一组旋钮。三项
    // 出厂 0 = 关 ⇒ 画面逐位旧档。数学是**共享**的（rgen 的地形 A1 与云层空气
    // 透视同一份），所以两边不会各说各话。
    //   x = 云层大气透视强度（#1；0 = 关。云层按**透射率加权代表点**过一遍解析
    //       晴空：乘透过率 + 补 in-scatter，再按云自身不透明度混回。见
    //       volumetric_clouds.comp 末尾的调用点与 clear_sky_aerial.glsl）
    //   y = 地面大气透视强度（#2 载体；0 = 关 = 现网 AP_BASE_EXTINCTION_SCALE）
    //   z = 地面大气透视遮挡衰减（#2；沿视线查远影图求"被地形挡住的比例"，
    //       削掉阴影段产生的 in-scatter。0 = 不衰减）
    //   w = 保留（0；2026-10-26 泡沫读层 lane 已随泡沫回退作废）
    vec4 cloudAerialParams;

    // SHaRC 缓存缓冲的 64 位设备地址（偏移 1424）。buffer_reference 按地址访问内存，
    // 不经描述符绑定；分量顺序：x = hashEntries / y = accumulation / z = resolved /
    // w = lock。低 32 位在 sharcBuffersLo、高 32 位在 sharcBuffersHi，着色器用
    // packUint2x32(uvec2(lo, hi)) 还原。四项全 0 = 未启用（模式闸门恒为 0）。
    uvec4 sharcBuffersLo;
    uvec4 sharcBuffersHi;

    // SHaRC 标量参数（偏移 1456）：x = sceneScale（0 = 未启用）/ y = 缓存元素数 /
    // z = radianceScale / w = **模式 lane**（0 = 不碰缓存；1 = 5a 寻址探针；
    // 2/3 预留 query / update+query）。容量必须与 C++ 真正分配的缓冲元素数一致。
    uvec4 sharcCacheParams;

    // ---------------------------------------------------------------------
    // 2026-10-14 天光色相/能量调节参数，追加在 CameraUBO 末尾（偏移 1472）。
    // 它调节现有 top/bottom 天光近似，不代表已经实现天空半球辐亮度或方向可见度积分。
    //   x = 天光色相向冷紫代理色插值强度（0 = 关；1 = 曙暮窗口内完全插值）
    //   y = 天光能量衰减强度（0 = 旧 daylight 上界 0.15；1 = 上界 0.35）
    //   z = 2026-10-17 ② **方向性天光强度**（0 = 关 = 逐位旧档；1 = 天光色相完全
    //       取自三方向 clearSkyInscatter 的 HG 加权混合；>1 过冲到 2）
    //   w = 2026-10-17 **顶底性质统一强度**（0 = 关 = 逐位旧档；1 = 云底基色改为
    //       「天顶天光 × 地面反照率」+ 直射高度增益归零。**不**接管「云底遮蔽
    //       强度」——那条竖直消光口径（0.02/步 × 638 格）尚未裁定）
    // 哨兵语义：>= 0 以 lane 为准（0 是合法值）；< 0 回落编译期默认。
    // ---------------------------------------------------------------------
    vec4 cloudSkyHueParams;

    // ---------------------------------------------------------------------
    // 2026-10-15 云地面反射与显式视觉回退参数，追加在 CameraUBO 末尾（偏移 1488）。
    // 尾追加不移动已有成员；当前消费者为 volumetric_clouds.comp。
    //
    //   x = groundAlbedo      均匀水平地面 Lambert 反照率 [0,0.8]，默认 0.20。
    //   y = groundBounce      该近似的强度 [0,1]，默认 1.0。
    //   z = sunlightResidual  非物理自阴影残光回退 [0,0.25]，默认 0（关闭）。
    //   w = phaseFloor        非物理黄金/月光相位地板回退 [0,1]，默认 0（关闭）。
    //
    // 地面反射只估算均匀水平地面所受直射日光，不是地形遮蔽或场景级反照模型；
    // 残光和相位地板是观感回退，均不属于物理基线。
    // 哨兵语义：>= 0 以 lane 为准（0 是合法值）；< 0 回落编译期默认。
    // ---------------------------------------------------------------------
    vec4 cloudPhysicsParams;

    // ---------------------------------------------------------------------
    // 2026-10-18 视差高度场采样开关（偏移 1504），追加在 CameraUBO 末尾 —— 尾追加
    // 不移动任何已读偏移。唯一消费者：rt_test.rchit（不透明地形 POM）。
    //   x = 0（默认 / 旧 DLL）＝ 平滑：双线性高度场
    //       > 0.5        ＝ 锐利：单纹素 texelFetch（PARALLAX_MODE1）
    //   yzw = 保留（0；2026-10-26 泡沫原点/纹素 lane 已随泡沫回退作废）
    // 只切换高度场采样，不动位移 / 深度 / 自阴影口径。
    // ---------------------------------------------------------------------
    vec4 pomParams;

    // ---------------------------------------------------------------------
    // 2026-10-19 树叶次表面散射模式（偏移 1520），追加在 CameraUBO 末尾 ——
    // 尾追加不移动任何已读偏移。唯一消费者：rt_test.rgen（matId 0.25 的
    // 树叶/花瓣背光项）。
    //   x = 0（默认 / 旧 DLL 越界读回 0）＝ 太阳背光：GI_FOLIAGE_BACKLIGHT
    //       原档，只在太阳高于地平线且叶面背光时触发。
    //       > 0.5                         ＝ 真次表面散射：叶面背半球一条
    //       余弦分布透射射线（跳过自身方块），miss 时按天空辐亮度加透射光，
    //       不依赖太阳直射；太阳穿叶项保留。
    //   yzw 保留。
    // ---------------------------------------------------------------------
    vec4 sssParams;

    // ---------------------------------------------------------------------
    // 2026-10-22 UE 风格体积云的八项参数（偏移 1536 / 1552），追加在 CameraUBO 末尾 ——
    // 尾追加不移动任何已读偏移。唯一消费者：volumetric_clouds_v4.comp（--model ue 那套，
    // 见 cloud_ue_core.glsl 的 UeCloudConfig）。bevy 模型完全不读这两块。
    //
    //   ueCloudA.x = msOctaves         [DOC] Multi Scattering Octaves（1..3，游戏建议 1）
    //   ueCloudA.y = msContribution    [DOC] 每阶能量权重
    //   ueCloudA.z = msOcclusion       [DOC] 每阶消光衰减
    //   ueCloudA.w = luminanceScale    [DOC] Cloud Scattering Luminance Scale
    //   ueCloudB.x = groundContribution[DOC] 地面反照照亮云底
    //   ueCloudB.y = cloudAO           [DOC] 云遮挡天空光强度
    //   ueCloudB.z = atmosphereDensity [DOC] 逐采样大气透射率强度（0 = 关）
    //   ueCloudB.w = weatherScaleMul   weather map 频率倍率
    //
    // 哨兵语义与本文件其他尾块一致：< 0 表示"未写入"，着色器回落硬编码默认值。
    // 这样旧 DLL 配新 SPV（或反之）都不会把 0 当成用户设定值。
    // ---------------------------------------------------------------------
    vec4 ueCloudA;
    vec4 ueCloudB;

    // ---------------------------------------------------------------------
    // 2026-10-24 半分辨率 GI（HANDOFF-2026-10-24-GI-HALFRES.md），偏移 1568 ——
    // 尾追加不移动任何已读偏移。
    //   x = 0（默认 / 旧 DLL）＝ 旧路径：主 raygen 自己跑全分辨率 GI 回路。
    //       > 0.5            ＝ 新路径：主 raygen 从 giHalfImage 上采样，GI 由
    //       独立 pass 在半分辨率执行（宿主 ORION_GI_HALF=1 时 dispatch）。
    //   y = 半图相位是否按帧轮转（2026-10-25：默认 0 = 固定相位；轮转会让每个
    //       texel 代表的世界位置每帧平移一个像素，RR 的时间累积失效 ⇒ 闪点）
    //   z = GI 射线跳过 POM（含 POM 自阴影，2026-10-25 A/B；宿主 ORION_GI_NO_POM）
    //   w = 光泽反射按粗糙度降速（2026-10-25；宿主 ORION_GLOSSY_ROUGH_LOD）
    // ⚠ 两个变体共用同一份 UBO，这一格是**唯一**开关；宿主在资源没建好 / 设备
    //   不支持 ray tracing 时必须写 0，否则主变体会去读未写描述符。
    // ---------------------------------------------------------------------
    vec4 giHalfParams;

    // ---------------------------------------------------------------------
    // 2026-10-25 近场 GI 采样率（偏移 1584，尾追加不动任何已读偏移）。
    //   x = 0 全速 / 1 半速 / 2 四分之一（宿主 ORION_GI_NEAR_RATE）
    //       消费者：rt_test.rgen 的 rtLodDiffuseGiEnabled 之后那一段
    //   yzw 保留（宿主写 0）
    // ---------------------------------------------------------------------
    vec4 giRayParams;

    // ---------------------------------------------------------------------
    // 2026-10-25 v5（HPVolumeCloud 移植）全参数尾块，偏移 1600..1712 ——
    // 尾追加不动任何已读偏移。唯一消费者：volumetric_clouds_v5.comp
    //（写进 cloud_v5_core.glsl 的 HPCloudConfig）。
    //
    // 哨兵语义与其他尾块一致：**负值 = 未写入** ⇒ 保留 hpDefaultConfig 的出厂值。
    // 28 项顺序（Java 侧 setCloudHpParams 的数组顺序必须一致）：
    //   hpShapeA = detailStrengthCu / detailStrengthTcu / detailStrengthCb / bottomSmoothHeight
    //   hpShapeB = wispyEdgeWidth / wispyReach / wispyTopHeight / wispyTopHardness
    //   hpShapeC = wispyWeight / billowyWeight / loCoverTopMax / loCoverTopStr
    //   hpShapeD = covMapScale / scWorleyStrength / scHeightScale / scCellThickStr
    //   hpLightA = forwardEcc / backwardEcc / phiFwdIntensity / phiFwdDepthPow
    //   hpLightB = phiFwdDepthBias / phiFwdCompress / scatterODScale / scatterCurvePow
    //   hpMiscA  = msEccentricity / aoUpwardScale / fadeStart / powderIntensity(保留)
    // ---------------------------------------------------------------------
    vec4 hpShapeA;
    vec4 hpShapeB;
    vec4 hpShapeC;
    vec4 hpShapeD;
    vec4 hpLightA;
    vec4 hpLightB;
    vec4 hpMiscA;

    // ---------------------------------------------------------------------
    // 2026-10-26 直接光模式 + 稀疏渲染（HANDOFF-2026-10-26-RTDI-SPARSE.md），
    // 偏移 1712，尾追加不动任何已读偏移。消费者：rt_test.rgen。
    //   x = 直接光采样模式：0 = 传统单帧 RIS（默认 = 逐位旧画面）；
    //       1 = RTXDI 式时域 reservoir（重投影上一帧 reservoir + 灯身份验证
    //       （世界坐标+类型）+ 本帧 RIS 候选 WRS 合并 + 1 条可见性射线）。
    //       灯数组 revision（localLightBuffer.header.y）变化 ⇒ reservoir 重置。
    //   y = 稀疏渲染活跃采样率（%）：0 = 关（默认 = 逐位旧画面）；
    //       >0 时每像素白噪声抽签（pcg_hash(pixel,frame) ≤ rate/100），
    //       非活跃像素跳过次级射线段（RTAO / 太阳软影 / 本地光 / GI / glossy），
    //       但 depth / MV / guides / 天空照常全量写。与被证伪的 rtSpp=0.5
    //       棋盘（整像素不写 ⇒ guides 全陈旧）本质不同：这里 guides 每帧
    //       新鲜密集，只有 MC 类辐射度稀疏，DLSS-RR 重建（Remix sparse
    //       rendering 同构：per_pixel_rate.slangh 白噪声默认 + composite 的
    //       密集 guide / radianceScale 语义）。
    //   z = RTXDI×稀疏联动：1（默认）= 非活跃像素仍做廉价 reservoir 验证/老化、
    //       不发着色射线（对齐官方 rtx.sparseRendering.enableRtxdiReuseFor-
    //       InactivePixels=true，防再激活闪烁）；0 = 非活跃像素完全跳过。
    //   w = 保留（RTXDI 空间重用档位，本期不实现；宿主写 0）。
    // ---------------------------------------------------------------------
    vec4 sparseParams;
