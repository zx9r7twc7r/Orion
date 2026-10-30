# 决策记录（decision records）

这里存放从源码注释里迁出的**过程叙事**：为什么这么改、当时排除了什么、被推翻过什么结论。
代码里只保留契约（不变的约束、生效值、回退方式、指针）。

规则：

1. **契约留在代码里**——"改格式必须同步哪三处"、"这个常量为什么是这个数"、"怎么回退"；
2. **过程搬到这里**——几轮试错、当时的实测读数、被推翻的判断、后来作废的方案；
3. 每份文档都标了**原文件与行号范围**，便于对照；
4. 搬走时原文照搬，不改写（措辞本身也是证据）。

| 文档 | 内容 |
|---|---|
| [`volumetric-fog.md`](volumetric-fog.md) | 体积雾 B 方案的重做定案、与参考实现的 5 条平台差异、四项扩展（地面锚定 / 时域抖动 / 天空环境项 / 动态光源）的动机 |
| [`god-rays.md`](god-rays.md) | 体积光介质五度改动史；空气分支为何整体退役、只留水下分支 |
| [`sun-shadow.md`](sun-shadow.md) | 为什么用阴影图而不是逐 march 样本射线；为什么存深度而不是二值可见性 |
| [`water-wave-field.md`](water-wave-field.md) | 2026-10-03 移除解析波带的清单与副作用（GUI 四个滑条与解析焦散失去作用对象） |
| [`nrd-tuning.md`](nrd-tuning.md) | NRD 本地光去噪：lag tuning 第二遍、antilag 对齐、motionVectorBias 曾被误判为根因、本地光全灭的定责过程、关掉 diffuse prepass blur 的依据 |
| [`ser-overhead.md`](ser-overhead.md) | SER 开销归因：可见性组的键形状/融合调用 A/B，结论是三者都在噪声带内、生产 pin 回 mode 1 |
| [`mv-probe.md`](mv-probe.md) | MV 读回探针（binding 57）的仪器说明书：为什么必须有它、四种伪彩各代表什么 |
| [`omm-pool.md`](omm-pool.md) | OMM 缓冲池化为何保持不设限：churnTop 现场、收紧到 2u 会退化成 32MB 分配抖动的推导 |
| [`object-accounting.md`](object-accounting.md) | 对象创建总账（诊断）：为什么在 vkAllocMB 之外还要逐个 `vkCreate*` 记账，2026-10-15 两局读数 |
| [`russian-roulette.md`](russian-roulette.md) | RR 生存率曲线与参考实现的定量对比；地板是方差取舍不是偏倚；η² 在薄壁玻璃下恒为 1；"照抄参考 RR"为何被否决 |
| [`sharc-default-off.md`](sharc-default-off.md) | SHaRC 默认关闭的现状；为什么管线门控写在 `ensureSharcUpdatePipeline()` 内部（114 秒教训）；关闭后的 GI 成本结构与构建侧死重 |
| [`cloud-lightstep-perf.md`](cloud-lightstep-perf.md) | 体积云光步降档：光步占云 pass 6~8 成采样量、参考的 mip 机制在本仓空转、两个开关的实测加速（1.33×）与外观代价；地球遮挡为何被否决 |
| [`cloud-coverage-size-split.md`](cloud-coverage-size-split.md) | 云量与云大小解耦（presence 遮罩 + 常数阈值、按 R 分布标定 presCut、大小滑条方向修正）、天气图 1024²→512² 与少一档倍频、3D 噪声 mip 链（本机收益为 0 的实测） |

其它文档：

- [`../env-switches.md`](../env-switches.md) — 47 个 `ORION_*` 环境变量总表
