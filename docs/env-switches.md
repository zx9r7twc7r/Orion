# `ORION_*` 环境变量总表

后端（`rtx_backend.dll`）的行为闸门全部通过**进程环境变量**读取，共 47 个，全部在
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
| `ORION_GEOMETRY_BATCH_CAP` | `0`（不覆盖） | 单次重建的 Section 批次上限；用于 A/B 定位"批次大小"与卡顿的关系 |
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
| `ORION_PERF_ABLATION` | 关 | 消融级联（`alt:3` / `alt:1,2,3,6,10,12` …），逐级关掉 GI / 光泽 / POM 采价格表 |
| `ORION_PERF_TIMESTAMPS` | 关 | GPU pass 时间戳 + CPU 帧间隔（`[PERF L10 GPU]` / `[PERF L10 CPU]`） |
| `ORION_GI_HALF` | 关 | 半分辨率 GI（`HANDOFF-2026-10-24-GI-HALFRES.md`）：GI 回路搬到 `rt_test_gi_half.rgen.spv`，按 (w+1)/2 × (h+1)/2 dispatch、四帧轮转四个子像素；主 raygen 改从 `giHalfImage` 做 3×3 深度感知上采样。关掉 = 建图/建管线/dispatch 全不做，UBO 的 `giHalfParams.x` 写 0 ⇒ 主 raygen 走原来的全分辨率 GI 回路（逐位旧画面）。**启用后第一帧仍走旧路径**（图还没建好），第二帧切换。可与 `ORION_PERF_TIMESTAMPS` 同开，用 `rt` 段差值判读 |

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
