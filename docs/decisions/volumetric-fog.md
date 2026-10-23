# 体积雾：决策记录

> 这里保存 `cpp/shaders/volumetric_fog.glsl` 头注释里被移出的**历史与取舍**。
> 代码里只保留契约（参数车道、平台差异、编译期常量、include 顺序）。
> 改实现前先读这份，能避免把已经排除过的方案再做一遍。

---

用户 2026-10-19 定案：**B = 完全照抄参考标定**。所以密度剖面、步长公式、
太阳项自吸收、能量合成、常量取值都按参考；上一版的"解析闭式高度雾"
（单高度指数 + 无 march + 阴影图固定间隔取样）整体作废。

平台适配（参考没有、但本工程必须补的，逐条标注）：
  1) 参考在 composite 里直接 `color = T*color + fog + ray`；
     本工程由调用方（volumetric_clouds*.comp）统一合成，所以这里返回
     FogResult{scattering, transmittance}，内部**仍是参考的 fogColor+rayColor**。
  2) 参考的 shadowmap 查询（shadowtex1，第 168-180 行）⇒ 换成本工程的
     sunShadowVisibility()（光追阴影图，近图+远图）。
  3) 参考的 BlueNoiseTemporal()/bayer64（第 75-79 行）⇒ 换成本文件的
     fogDitherPhase()：固定 4×4 Bayer（bayer64 左上子块）。clouds 的 4 个变体
     不保证都带 temporalJitter()，且雾没有时域累积，逐帧抖动只会变成噪点。
  4) 参考的 globalCloudShadow（第 214/228 行）在本工程的太阳颜色里已由调用方
     携带（directLight 走 c3CloudSunTransmittance），这里不再重复乘。
  5) 参考的 eyeBrightnessSmoothCurved（室内门控）本工程调用方已有等效门控。

2026-10-23 四项扩展：

  · 地面锚定（fogExtrasParams.y → 函数参数 groundAnchor）
    fogDensityAt() 的密度带是**绝对世界 Y**（VFOG_HEIGHT / VFOG_HEIGHT_2），
    世界一起伏它就失效：相机在谷底时雾带悬在头顶，雾带埋进山坡时坡面没雾。
    这里把采样点的 Y 减去 (该列地面 Y − 参考海平面 63) × 强度，再喂给参考的
    密度函数 —— 带厚与剖面形状**一个字符都不改**，只换输入的 Y，于是整条带
    跟着地形升降（强度 0 ⇒ 逐位旧档）。不能把 VFOG_HEIGHT 直接当"离地高度"：
    那是 50..65，会变成"离地 50-65 格的雾"，飘到半空去。
    地面高度用 terrain_height.glsl 的 terrainGroundHeight()（每帧 512² 射线
    追踪的产物，与旧档贴地薄雾同一个数据源）。

  · 时域抖动（fogExtrasParams.w → temporalJitter）
    原抖动是固定 4×4 Bayer，同一个像素永远同一个相位 ⇒ 只能压空间条纹、
    不能压"沿视线离散步进"造成的同心带。这里把逐帧低差异相位（黄金比序列，
    与 volumetric_clouds.comp 的 temporalPhase() 同一招）加到 Bayer 上，
    让每个像素的步进相位逐帧滑动。**本文件不自己做累积** —— 消费它的是既有的
    volumetric_cloud_resolve.comp（云/雾共用一条 scattering/transmittance 链，
    历史权重 cam.cloudParams2.w），所以零新增缓冲、零新增 descriptor。

  · 天空辐亮度当环境项（在**调用点**做，不在这里）
    原来的环境入散射是 `mix(夜色, 日色, dayAmount)` 两色常量，日出日落时雾
    永远是同一个蓝。调用点改成按 fogExtrasParams.z 混入 clearSkyInscatter()
    沿视线方向的真实晴空辐亮度 —— 太阳低时雾自己泛红/泛紫，不需要调色板。

  · 动态光源照亮雾（fogExtrasParams.x → dynamicLightStrength）
    火把/岩浆/发光方块的 in-scatter。灯光数据与 rgen 的本地光表是**同一份**
    （binding 12/13，见 local_light_grid_common.glsl 的头注释），亮度口径也
    照抄那里的 3.2 × 0.35 × intensity × (emission/15) × 距离衰减 × 范围淡出。
    自创部分只有"相位"这一步：介质里没有 N·L，用一个各向同性 + 轻微前向的
    相位代替（FOG_LIGHT_PHASE_*），这是体积散射的常规定义，不是我的发明。

编译期常量 = 参考出厂值（Settings.glsl）：
VFOG_DENSITY 0.01 / VFOG_FALLOFF 40 / VFOG_QUALITY 6 /
