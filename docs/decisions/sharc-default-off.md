# SHaRC 默认关闭：现状、门控位置与它的后果

> 从 `cpp/VulkanCore.cpp:15782-15793`（`sharcEnabled()`）、`:24978-24993`
> （`ensureSharcUpdatePipeline()`）、`cpp/shaders/rt_test.rgen:21802-21812`
> 三处头注释与门控代码整理。代码里保留契约（默认值、门控位置、回退方式），
> 这里保留**为什么门控必须写在这些位置**，以及关掉之后 GI 的成本结构变成什么样。

---

## 现状（契约）

```cpp
// ORION_SHARC=1 启用 SHaRC（默认关；关时连那 176MB 都不分配）
static bool sharcEnabled()
{
    const char* raw = std::getenv("ORION_SHARC");
    return raw != nullptr && raw[0] != '\0' && raw[0] != '0';
}();
```

默认 **关**。关着的时候：

- 4 个缓存缓冲（hash entries / accumulation / resolved / lock）不分配 —— 约 176MB；
- 第二条 RT 管线（`rt_test_sharc_update.rgen.spv`）**不建**；
- 探针与回路体里的两个仪器计数器都被 `sharcMode() != 0u` 挡住，"一行缓存都不碰"。

`ORION_SHARC_SCALE`（默认 12）、`ORION_SHARC_ENTRIES`（默认 22 = 4M）只在开关打开时才有意义。

## 为什么门控写在 `ensureSharcUpdatePipeline()` 内部，而不是调用点

2026-10-12 实测：这条管线原先放在 init 里无条件创建，于是 `ORION_SHARC=0` 时
仍要多等 **114 秒**（12:44:21 开始 → 12:46:15 完成，驱动编译第二条 RT 管线），
用户感知是"关了 SHARC 还是卡在加载页"。

放在函数内部而非调用点，是为了让将来任何惰性调用路径也一起受门控 —— 这是一条
**结构约束**，不是当时的临时补丁。

## 为什么回路体第一条语句必须先过 `sharcMode()`

`rt_test.rgen:21802` 记的根因：那两条诊断计数器 `atomicAdd` 只挂空间散列地址，
不看模式。`ORION_SHARC=0` 时设备地址是 0 ⇒ 向地址 0 做 atomicAdd ⇒ GPU 页错误
⇒ pass 永不完成 ⇒ **5 秒信号量超时，进世界即崩**。

修法是把两个计数器与查询同源门控（`sharcMode() != 0u && ...`）。教训是：
**任何"只挂地址、不挂模式"的访问都是一个新的崩溃入口**，不是一条无害的仪器。

## 关掉之后 GI 的成本结构

- `g_perfGiBounces` 默认 **1**（`cpp/VulkanCore.cpp:7937`，Java 侧可调 0–4）。
- 每像素固定约 4 条射线：主射线 + 太阳 NEE + 1 次 GI 反弹 + 反弹处的 NEE；
  本地光另有 ≤32 条可见性射线。
- **没有缓存摊薄那次反弹**：GI 的全部时域负担压在 DLSS RR 上，没有第二个降噪源。
  这解释了玻璃内 1spp 白噪为何只被压到有限程度（见 `rt_test.rgen:5136` 的读数），
  它是预算结构决定的，不是降噪器能力问题。

## 遗留死重（运行期干净，构建侧不干净）

| 项 | 状态 |
|---|---|
| `rt_test_sharc_update.rgen.spv` | 570 KB，仍编入 8 个必编产物、仍随包发货，但默认永不加载 |
| 宿主 SHARC 管线/缓冲/探针代码 | 约 500 行（`:24208-24290`、`:24705-24740`、`:24978` 起），默认路径不执行 |
| `rt_test.rgen` 的 `#if SHARC_UPDATE` 分支 | 只在 update 变体里编入 |
| `rt_test.rgen:20310` 起"SHaRC 缓存值的归一化基准"长注释 | 20 余行推理，现已无处消费 |

两个选择：

1. **保留实验开关**（`ORION_SHARC=1` 仍能开）—— 别动 SPV 与代码，但要保证
   本文件存在，否则后来人会从发货清单反推出"它在用"。
2. **退役** —— 删 `#if SHARC_UPDATE` 分支 + 该 SPV + 宿主那 500 行。收益是每个
   shader 改动少编一个 570 KB 产物、打包少 570 KB；代价是重开要重写。

当前选择：**1（保留）**。因为它仍然是一个可复现的对照臂（`ORION_SHARC_AB`），
而退役的收益只有构建时间。
