# `ORION_*` 环境变量总表

后端（`rtx_backend.dll`）的行为闸门全部通过**进程环境变量**读取，共 81 个（2026-10-29
按 `cpp/VulkanCore.cpp` 里 `"ORION_*"` 字面量去重实测；原表写"47 个"已过期），全部在
`cpp/VulkanCore.cpp`。之所以用环境变量而不是 `-D` 系统属性：C++ 侧是 `std::getenv()`，
Java 的 `-D` 传不进去（`build.gradle` 里的 `vmArg` 也到不了游戏 JVM）。

> **所有闸门都是"第一次调用时读一次"**（`static const X = [](){ ... }();`），
> 改完**必须重启客户端**。少数支持热切换的会在"备注"里单独注明。

取值约定（除个别注明）：

| 写法 | 含义 |
|---|---|
| 不设 / 空串 | 用默认值 |
| `1` / 非 `0` 开头 | 打开 |
| `0` | 关闭（回退到闸门上线前的旧行为） |

---

## A. 预算与容量（只影响耗时/显存，不改画面）

| 变量 | 默认 | 作用 |
|---|---|---|
| `ORION_GEOMETRY_BUDGET_MS` | `0`（不限制） | 每次 Section 重建的时间预算（毫秒） |
| `ORION_GEOMETRY_BATCH_CAP` | `0`（不覆盖） | 单次重建的 Section 批次上限。2026-10-29 起**可直接覆盖自适应值（可放大）**；此前是 `min(env, 自适应)`，而自适应在流式加载时被压到 14~24，所以设 64 无效。配合 `ORION_GEOMETRY_BUDGET_MS` 一起调（预算会在块与块之间中断） |
| `ORION_GEOMETRY_LOCK_WAIT_MS` | `3` | 重建入口取 `g_chunkMutex` 的等待预算；`0` = 退回旧的阻塞行为 |
| `ORION_VLI_BUDGET_MS` | `4.0` | 体素光注入（VLI）每帧预算 |
| `ORION_VLI_BUDGET_FRACTION` | `0.08` | VLI 页池容量上限 = 显存预算 × 本比例（替代旧的固定 4096 页） |
| `ORION_VLI_SECTIONS_PER_FRAME` | `2` | 每帧最多推进几个待注入 Section |
| `ORION_VRAM_GOVERNOR` | `2` | `0` 关 / `1` 只做甲（slab 清理）/ `2` 甲+乙（默认） |
| `ORION_VRAM_GOVERNOR_HIGH` | `0.72` | 高水位：显存占用率超过它即回落 |
| `ORION_VRAM_GOVERNOR_LOW` | `0.62` | 低水位：回落到它以下才停止驱逐 |
| `ORION_VRAM_EVICT_KEEP_RADIUS` | `96`（格） | 比它近的 Section 一律**不驱逐**（补传阈值 = 本值 × 1.25，中间是防抖带） |
| `ORION_VRAM_EVICT_PER_PASS` | `16` | 每批驱逐上限；刻意取小，避免过冲后又补传回来 |
| `ORION_VRAM_REHYDRATE_PER_PASS` | `16` | 每批补传上限；刻意取大，避免转身后地形空缺太久 |
| `ORION_SECTION_CHUNK_WASTE_LOG2` | `1` | Section 池"块内浪费"统计的对数分桶（2 的幂） |
| `ORION_WATER_ADAPTIVE` | 开 | 水面自适应分辨率 |
| `ORION_WATER_CAUSTIC_HZ` | 不设（跟随 FFT 30 Hz） | 折射焦散图（1024²×2 层×11 mips）的**独立**重建时钟：不设 = 闸门上线前行为（跟着 FFT 时钟，帧率≈30fps 时每帧都重建）；`10`/`15` = 该频率上限；`0` = 永不重建（进世界后图是空的，水下没光斑）。实测 30 Hz 下该 pass **5.82 ms = 整帧 21%**（`perf/step0/perf-20261028-moving_*.log`），而同一时刻 FFT 只有 0.07 ms、波方程模拟≈0。⚠ 焦散图本该每帧重建还是降频，取决于用户对"水下光斑更新顺滑度"的接受度，默认保持老行为 |

| `ORION_MEM_PRIORITY` | 开 | `VK_EXT_memory_priority`（2026-10-29）：分配时链 `VkMemoryPriorityAllocateInfoEXT`。分层是**自创**的 —— host-visible 0.80 / ≥8 MiB device-local 0.35 / 其余 0.50。只改驱动在显存压力下的驱逐顺序，不改数据正确性；`0` = 不插结构（回到旧行为）。扩展未启用时自动失效 |
| `ORION_EXT_OPTCAP` | 开 | **Java 侧**读取（`VulkanBackendMixin.rtx$injectOptionalCapabilities`，不在 `VulkanCore.cpp`）：`0` = 一次不启用 2026-10-29 新增的全部 12 项可选扩展（设备清单从 36 条回到 24 条），用于 A/B 排除这批扩展 |
| `ORION_EXT_PAGEABLE` | 开 | **Java 侧**读取：`0` = 只不启用 `VK_EXT_pageable_device_local_memory`。该扩展允许驱动把 device-local 分配分页到系统内存，是显存压力下帧时间抖动的第一嫌疑 |
| `ORION_AS_REFIT` | **开**（2026-10-29 起） | Section BLAS 的 `MODE_UPDATE` 快路。`0` = 硬关（回退到全量重建）；`1` = 硬开并忽略标记文件；未设 = 默认开，可用 `run/as_refit.off` 热切换（每 600 帧探测）。同局 A/B 实测 `createN`/`retireN` −72%、`asChunkMB` −2 MB、`frame`/`trace` 各 −0.1/−0.28 ms |

## B. 诊断与仪器（默认多加日志/计数，不改画面）

| 变量 | 默认 | 作用 |
|---|---|---|
| `ORION_LEAK_PROBE` | 关 | 每帧 JNI 入口的 `GetProcessMemoryInfo` 记账（默认关：180fps 下每秒 1000+ 次采样的开销不值得） |
| `ORION_MEM_OBJ_TRACK` | **开** | 逐对象显存记账（零行为变更，只多一次 relaxed 原子加） |
| `ORION_VRAM_PROBE` | 开 | 显存探针：周期性打印占用与预算 |
| `ORION_VRAM_WARN` | `90`（%） | 探针告警阈值 |
| `ORION_LOG_FRAMESKIP` | 关 | `recordFrameCommand` 早退原因日志（定位"画面变回原版"那类问题） |
| `ORION_TLAS_DELTA` | 关 | 实例集合变化时逐项记账（判定"是谁在变"：粒子 / 剔除轮换 / 几何上传） |
| `ORION_DEVICE_INIT_STOP` | `0` | 设备初始化在第 N 个里程碑停下（二分定位初始化崩溃） |
| `ORION_RT_PIPE_RECYCLE_SEC` | `0`（关） | 每 N 秒换一次 RT 管线，主动归还显存（`0` = 不换） |
| `ORION_RT_PIPE_NO_CACHE` | 关 | 建 RT 管线时不读写管线缓存 |
| `ORION_RT_PIPE_NO_OPT` | 关 | 建 RT 管线时不做优化（更快出管线，跑得更慢） |
| `ORION_GLASS_ALPHA_BLEND_DBG` | `0` | 玻璃 alpha blend 的**逐项排查档**（`glass_alpha_blend.comp`，每帧读 ⇒ 改完重启）：`1` 层诊断色 / `6` 只显示 bgT 灰度 / `9` 完全旁路（surface 归零、bgT 当 1）/ `10` 表面项归零（只剩 背景×bgT）/ `11` 表面项不按背景亮度重标定 / `12` 表面项 = 常量 1（只剩 albedo×a）/ `13` 表面项 = 本像素背景辐射（旧路径） |
| 标记文件 `glass_layer_first.flag` / `glass_layer_last.flag` | 无 | 把 `randomThreshold` 钉成 0 / 1（`perfAblationStage.w` = 5 / 6），即**确定性选层**；用来判断"逐帧随机选层"是不是半透明颗粒的来源。建/删文件 + 重启即可，不必重编 |

## C. A/B 与回退闸（默认保持新行为，设 `0` 回退旧行为）

| 变量 | 默认 | 作用 |
|---|---|---|
| `ORION_CAPTURE_TIME_GATE` | 开 | 几何捕获的"单次唤醒时间闸"；`0` 退回只受条数约束 |
| `ORION_UPLOAD_RAW_MOVE_GATE` | 开 | `uploadChunk` 锁内拷贝外移；`0` 退回在 `g_chunkMutex` 内分配+拷贝 |
| `ORION_REBUILD_FENCE_GATE` | 开 | Section 重建的 fence 闸 |
| `ORION_LOCAL_LIGHT_REBUILD` | **关** | 本地光聚合缓冲（bindings 17/18）的每帧重建；`1` 打开 |
| `ORION_SUNSHADOW_FAR` | 开 | 1024² 远场太阳阴影图的生产 pass |
| `ORION_SUNSHADOW_FAR_EXTENT` | `384`（格） | 远场阴影图覆盖范围（< 64 时回落默认） |
| `ORION_SUN_SHADOW_ALWAYS` | 关 | 每帧强制重建太阳阴影（不看签名） |
| `ORION_TERRAIN_HEIGHT_ALWAYS` | 关 | 每帧强制重建地形高度场 |
| `ORION_SYNC_GEOMETRY` | 关 | `1` = 关掉几何捕获线程，改为调用线程同步捕获（排线程问题用） |
| `ORION_MV_LAYERS` | `1` | 位移段发给哪些层：`0` 全不发 / `1` 只 ENTITY / `2` 只 PARTICLE / `3` 两者（旧行为） |
| `ORION_SL_FEATURES` | `all` | Streamline 特性子集（`all` 或逗号分隔的特性名） |
| `ORION_AS_REFIT` | 关（看标记文件） | `1` = 进程内恒开 Section BLAS refit；不设则看 `<gameDir>/as_refit.on` 标记文件（**热切换**，只影响之后的重建） |

## D. 实验特性（默认关，用于评估与采价）

| 变量 | 默认 | 作用 |
|---|---|---|
| `ORION_SHARC` | 关 | SHaRC 空间哈希辐射缓存（4M 元素 ≈ 176MB 显存） |
| `ORION_SHARC_ENTRIES` | `22`（2²²） | 缓存元素数的 2 的幂（≥ 18） |
| `ORION_SHARC_SCALE` | `50.0` | 体素尺度（`HashGridParameters::sceneScale`） |
| `ORION_SHARC_QUERY` | 跟随 `ORION_SHARC` | 是否查询缓存（关掉 = 只跑 update + Resolve，用于拆成本/收益） |
| `ORION_SHARC_UPDATE` | 跟随 `ORION_SHARC` | 是否跑 update pass |
| `ORION_SHARC_PROBE` | 跟随 `ORION_SHARC` | 寻址探针（3 帧后回读判定，不改变可见像素） |
| `ORION_SHARC_AB` | 关 | 同局内交替开/关 query，每 120 帧换臂，日志打 `[SHARC AB] arm=A/B` |
| `ORION_SER_LADDER` | 关 | 同局内轮换 SER 档位（0/1/4/5/6），每 60 帧一档、300 帧一周期 |
| `ORION_PERF_ABLATION` | 关 | 消融级联（`alt:3` / `alt:1,2,3,6,10,12` …），逐级关掉 GI / 光泽 / POM 采价格表。**档 17-31 是 2026-10-30 加的材质/非材质拆解档，语义与实测值见 `docs/decisions/rt-cost-attribution.md` §6；加新档必须同步 `PERF_L10_ABLATION_STAGE_COUNT`、两处 `% N`、以及 rgen 的 `perfL10Stage()` clamp 上限**（漏第三处会让新档被静默折成上限值，2026-10-30 已踩过一次） |
| `ORION_SECONDARY_MIP_BIAS` | `0`（**opt-in**） | 次级命中（非 `PRIMARY_SKY`）的 mip 偏移，写进 `CameraUBO.historyPadding0`，shader 侧 `pbrLod = min(pbrLod + bias, pbrMaxLod)`（不跨 sprite、不串色）。收益实测（`alt:0,27`，143 窗口）base 21.31 → 19.88，**Δ = 1.43 ms（6.7%）**。**但 2026-10-30 试过默认 2.0 后已撤回**：mip 是下采样平均，方块贴图的平均色亮于其暗部像素 ⇒ **室内/无光环境整体被抬亮**（法线同样被平均，光照响应也变），实机不可接受。需要这 1.43 ms 时自行设 `=1~3` |
| `ORION_PERF_TIMESTAMPS` | 关 | GPU pass 时间戳 + CPU 帧间隔（`[PERF L10 GPU]` / `[PERF L10 CPU]`） |
| `ORION_GI_NEAR_RATE` | `0` | 近场 GI 降速级：`0` 全速 / `1` 半速 / `2` 四分之一。跳过的是 GI **信号**（独立位 + 权重补偿，无偏时间估计量），像素照常每帧全写 ⇒ 不出锯齿；代价是 GI 方差按 2×/4× 放大，靠 DLSS-RR 抹平。距离 LOD（64 格以外）在此之前已经生效，两项相乘 |
| `ORION_GLOSSY_ROUGH_LOD` | 关 | 光泽反射按粗糙度降速：`roughness ≥ 0.30` 半速、`≥ 0.65` 四分一（与距离级合成一个级别，权重只乘一次）。实测室内 `noGlossy Δ` 从 4.27ms 降到 2.50ms，光滑面（<0.30）保持全速 |
| `ORION_GI_NO_POM` | 关 | GI 射线跳过 POM 与 POM 自阴影（`RT_STATE_GI_NO_POM_BIT`）。**已在 10-21 被用户否决过**（"POM 在次级命中不可砍"），实测室内也无收益，保留仅为诊断 |
| `ORION_GI_HALF` | 关 | 半分辨率 GI（`HANDOFF-2026-10-24-GI-HALFRES.md`）：GI 回路搬到 `rt_test_gi_half.rgen.spv`，按 (w+1)/2 × (h+1)/2 dispatch、四帧轮转四个子像素；主 raygen 改从 `giHalfImage` 做 3×3 深度感知上采样。关掉 = 建图/建管线/dispatch 全不做，UBO 的 `giHalfParams.x` 写 0 ⇒ 主 raygen 走原来的全分辨率 GI 回路（逐位旧画面）。**启用后第一帧仍走旧路径**（图还没建好），第二帧切换。可与 `ORION_PERF_TIMESTAMPS` 同开，用 `rt` 段差值判读 |
| `ORION_DIRECT_LIGHT_MODE` | `0` | 直接光采样模式（`HANDOFF-2026-10-26-RTDI-SPARSE.md`，性能页有同项）：`0` = 传统单帧 RIS（每像素 16 提案 + 1 根可见性射线，旧画面）；`1` = RTXDI 式时域 reservoir（重投影 + 灯身份/修订号验证 + WRS 合并 + **只对选中项打一根射线**）。1 档省掉提案的阴影步，代价是光源/接收面快速运动时多一帧滞后。启动时若本变量非空，Java 恢复不会下发配置值（否则会压住 env）；在界面上点应用即改为 UI 覆盖 |
| `ORION_SPARSE_RATE` | `0` | 稀疏渲染活跃采样率（%，性能页有同项，`0` = 关）。**2026-10-27 起语义按 RTX Remix 对齐**：这个百分比是"请求值"，实际有效采样率 = `rate * (2227*1238 / 渲染像素数)^0.69`，clamp 到 [1/16, 1]；有效值 > `0.75` 时稀疏**自动关闭**（Remix `maxSamplingRate` 默认 0.75），没有 DLSS-RR 时同样关闭。判定用 Remix 默认的 `BlueNoise128x128x64x8`（`threshold <= rate`），由 `sparse_active_mask.comp` 生成 4×2 块的活跃掩码，raygen 读 binding 78。非活跃像素：次级射线（RTAO/太阳软影/局部光 RIS/GI/glossy）不跑，且**不覆盖本帧颜色**（保留上一帧，等价 Remix 的"不写 radiance"）；活跃像素的稀疏辐射乘 `1/max(rate, 1/255)` 无偏重建。采样率变化时强制 reset 历史。深度/运动矢量/引导图始终密写 |
| `ORION_RTDI_REUSE_INACTIVE` | `0` | **自创开关，不是 Remix 的移植**：Remix 的 RTXDI initial/temporal/spatial reuse 三个 pass 全部按活跃像素列表启动（`rtx_rtxdi_rayquery.cpp:518/524/593`），非活跃像素没有线程，不存在"仍合并/老化 reservoir"这一行为；`enableRtxdiReuseForInactivePixels` 在 dxvk-remix 全仓库 0 命中。`1` = 非活跃像素仍做 reservoir 验证/老化（只在 `ORION_DIRECT_LIGHT_MODE=1` 时有效）；`0` = 与非活跃像素完全跳过（默认，贴近参考） |

---

## 与 `ORION_PERF_ABLATION` / `ORION_SER_LADDER` / `ORION_SHARC_AB` 的互斥

三者都靠 `frameSerial` 轮换窗口，**不能同时开**。日常那一局建议全关，需要采价时用
IDE 里独立的运行配置（如 `Minecraft Client (ABLATE)`）。

## 新增闸门的约定

1. 用 `static const X = [](){ std::getenv("ORION_..."); ... }();` 包住，避免每帧 `getenv`；
2. 默认值必须等于"闸门上线前的行为"，这样不设变量就是老行为；
3. 在函数上方注释里写明：**为什么加、怎么判读、怎么回退**；
4. 加完在本文件补一行，否则半年后没人知道它还在。

## Java / 启动参数侧的开关

DH 集成那一套走的是 Java 系统属性（`-Dorion.dh.*`），不是环境变量，见
`src/client/java/com/example/client/compat/dh/DhFlags.java`；IDE 运行配置
`.idea/runConfigurations/Minecraft_Client.xml` 里有完整的参数说明。

基准会话与相机路径同理，走 Java 系统属性：`orion.bench.*`（逐帧落盘、心跳、
可信性判定）与 `orion.campath.*`（录制/回放，固定步长采样）。全部默认关闭，
完整清单与判读口径见 [benchmarking](benchmarking.md)。

实体 MV 的四肢摆动（逐顶点位置历史）默认开启，`-Dorion.limbMv=0`（或
`ORION_LIMB_MV=0`）退回根位姿增量，用于 A/B 判断"摆动的四肢到底有没有进 MV"。
详见 `EntityRtCapture` 的 `LIMB_MV_ENABLED`。
