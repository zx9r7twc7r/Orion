# HANDOFF 2026-10-30：RT 成本归属收尾（材质 / 非材质全部拆完）

> 本文档自包含。判据与数据表在 **`docs/decisions/rt-cost-attribution.md`**（同一晚写的
> 决策记录），这里只写"改了什么、怎么回退、下一步从哪进"。

---

## 零、一句话状态

`trace` 的归属已经拆完：**材质 43%、主射线固定成本 8.85 ms、GI 弹射遍历 4.35 ms、
反射非材质 ≈0**。

⚠ **最终结果：三项有收益的候选全部撤回**（次级 mip+2 1.43 ms / OMM 2 态 0.00 /
反射 tMax 96 1.85 ms），因为都有**画面副作用**（主要是把室内/无光环境抬亮）。
默认值已全部回到改动前，详见决策记录 §9。**净收益为 0**，这次唯一的产出是
"哪些方向有 1 ms 级收益、各自要付什么代价"的完整账。

---

## 一、改了哪些代码

| 文件 | 改动 | 默认 |
|---|---|---|
| `cpp/VulkanCore.cpp` | `ORION_SECONDARY_MIP_BIAS` 默认值试过 2.0，**已撤回 0**（opt-in） | 生效（=改动前） |
| `cpp/VulkanCore.cpp` | 消融档 17-33（17 个新档）+ `PERF_L10_ABLATION_STAGE_COUNT=34`、两处 `% 34` | 仅探针 |
| `cpp/shaders/rt_test.rgen` | `perfL10Stage()` clamp 上限 16→33；档 28/29/30 的组合 predicate | 生效（0 档无变化） |
| `cpp/shaders/rt_test.rgen` | `SECONDARY_TRAVERSE_FLAGS` → `secondaryTraverseFlags()`（28 个调用点） | 生效，**默认 4 态**（2 态已撤） |
| `cpp/shaders/rt_test.rgen` | 反射距离预算：试过 96，**已撤回 10000** | 生效（=改动前） |
| `cpp/shaders/rt_test.rchit` | 档 17 noMatEval 探针；档 18/19 POM 探针 | 仅探针 |
| `cpp/shaders/material_resolve.glsl` | 档 20-24/27/33 探针；emissive 无损跳过（`specularSample.a >= 1.0` 时跳过 texelFetch） | 仅探针 + 一项无损优化 |
| `cpp/shaders/rt_test.rchit` | POM 自阴影采样边界外提（严格等价） | 生效，**0 收益**（保留，代码更清楚） |

**部署状态**：收尾后需以 `tools/audit_spv_freshness.py` 为准（漂移 0）；
DLL 六处一致、`rt_test.rgen.spv` 与 `rt_test.rchit.spv` 三处一致 —— 具体 hash 见
`git status` 或审计输出（本文写作后又有 4 轮编译，hash 已多次变动，不再逐一记录）。

---

## 二、被否定的方向（一句话版，细节见决策记录 §7）

- **分箱延迟材质**：SER 已经做完同一件事（−60% 材质成本）；mode 4「手工选 key」实测**慢 0.24 ms**
- **BVH 侧投入**：遍历只 1.09 ms / 7%
- **减射线数**：`tierHalf`/`rrGi`/`ao2of4`/`shadow1of2`/`march1` 全在 ±2%
- **材质算术优化**：hoist 掉重复计算后 Δ 1.17→1.12，噪声内
- **OMM 2 态**：0.00 —— 我们的 alpha cutout 是**二值**，4 态里没有 unknown 态
- **反射 tMax 截断**：有 1.95 ms，但画面会空；根因是**打空的射线要遍历整个 BVH**

---

## 三、回退点

| 场景 | 操作 |
|---|---|
| 次级 mip+2 | `ORION_SECONDARY_MIP_BIAS=0`（不用重编） |
| OMM 2 态 | 删 `rt_test.rgen` 里 `gl_RayFlagsForceOpacityMicromap2StateEXT` 那一项（0 收益，删不删都行） |
| 全部探针档 | 不设 `ORION_PERF_ABLATION` 即全部不生效（0 档走 else，逐像素零变化） |
| 源码 | `VulkanCore.cpp.bak_pre_matprobe_20261029`、`rt_test.rchit.bak_pre_pomhoist_20261029`、`rt_test.rgen.bak_pre_tmax_20261029`、`rt_test.rgen.bak_pre_nonmat_20261030`、`rt_test.rgen.bak_pre_omm2state_20261030`、`material_resolve.glsl.bak_pre_b_dfs_20261029` |

---

## 四、下一步唯一入口：B「payload 瘦身」

`HitPayload` 21 字段，**玻璃链专用 56 B ≈ 45%**。不能搬 SSBO（玻璃 anyhit 每层累加，
搬显存每层一次往返，大概率更慢）⇒ 只能 **B2：玻璃独立 payload（location=1）**。

**先做前置实验（30 分钟，别省）**：临时删掉那 7 个玻璃字段、不追求画面正确，
只量 `trace` 变化（同站位跑一局 base 对比）。
- ≤1 ms ⇒ 终止，别再投入
- >1 ms ⇒ 做 B2（一天量级；玻璃路径有过 GPU 挂死先例，必须逐条验）

---

## 五、两个坑（今天又踩了一次）

1. **加消融档必须同步三处**：`PERF_L10_ABLATION_STAGE_COUNT`、两处 `% N`、rgen 的
   `perfL10Stage()` clamp 上限。今天 clamp 停在 30，档 31 被**静默折成 30（noMatNoGi）**，
   量出来的 trace 是 7.64（该值几乎等于 noMatNoGi 的 8.76）—— 这类错位只能靠
   "数字与已知臂对照"才看得出来。**新增档后第一件事：确认它的 trace 与同族臂不雷同。**
2. **`build_and_deploy.bat` 的 exit 1 是既有遗留**：`rt_test.rmiss.spv` 等 6 个
   "未部署"长期存在（`run/` 里是旧版），与本次改动无关；判断编译成败看第 1/3 步的
   `DIFF`/`FAIL` 行，别只看退出码。

---

## 六、环境

- 启动：`.tmp/run_*.bat`（每局一个，已带好 env）；或 `.\gradlew.bat runClient --offline`
- 日志：`run/rt_debug.log`（native）、`run/logs/latest.log`（Java）
- 分析脚本：`.tmp/analyze_ablation.py <offset>`（按 `ablate=` 分组 + 配对差值）
- GPU：**RTX 4070 Laptop**（支持 OMM / SER）
