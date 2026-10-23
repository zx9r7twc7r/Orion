# 水面波场（water_wave_field.glsl）：移除解析波带的记录

> 从 `cpp/shaders/water_wave_field.glsl` 头注释移出（原文）。
> 代码里保留结论：形态只由波方程场决定，不要再往这里加解析波带。

---

2026-10-03：**旧解析自然波面已整体删除**（用户要求）。

现在水面形态由**波方程场单独决定**：
  · water_sim.comp 的常驻风源 + 交互脉冲 → 波高/速度场（RG16F 双缓冲）
  · 本文件只负责把该场转成光学斜率（waterInteractionSlope）
这与参考 HPWater 的机制一致（HPWaterFluidDynamics.cs：没有解析波带、没有风场，
只有一套波方程场 + 外部波源；HPWaterFluidHeightToNormal 就是同一套中心差分）。

被删除的内容：两条 Gerstner 长波带、三组域扭曲、三档噪声倍频程、以及只服务它们
的辅助函数 accumulateFineGerstnerOpticalWave。
副作用：GUI 的「波长倍率 / 主波向 / 涌浪陡度 / 波峰扭曲」失去作用对象（它们只
作用在被删掉的解析波带上）；解析焦散也失去波前细节（焦散与波面同源）。
需要这两层时，再按波方程场给它们重新接靶。

