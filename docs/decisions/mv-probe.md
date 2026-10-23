# MV 读回探针（binding 57）：仪器说明书

> 从 `cpp/VulkanCore.cpp` 搬出（原文）。仪器已退役（写它的 shader 已删），
> 但说明书保留 —— 重建 NRD 链路时这是唯一能直接读出"MV 到底对不对"的手段。

---

## 探针设计：为什么非要有它、读法

原位置：`cpp/VulkanCore.cpp:5817-5849`（33 行）

2026-09-29 ★ MV 读回探针（binding 57）。这是本会话要建的那台"可信 MV 仪器"。

为什么非要有它：IN_MV（binding 55）既是 NRD 的输入**也是它的输出** —— NRD 的
TemporalStabilization pass 会把**它自己跟踪出来的运动**写回同一张图
（Reblur_Diffuse.hpp 把 AsUint(ResourceType::IN_MV) 推进 PushOutput）。后果：
  * validation 视口 3 读到的是 NRD 的回写值，不是我们写的 MV；
  * 合成端 binding 55 读到的同样是被 NRD 覆写后的值。
所以项目此前**不存在**任何能读回"我们自己喂进去的 MV"的仪器 —— 之前"三种不同
的 MV 实现画出逐像素相同的视口 3"就是这么来的。

这张图是私有的：只有 rt_restir_final.rgen 写、rt_nrd_composite.comp 读，
NRD 从不碰它（它是我们自己的描述符项，不在 NRD 的 set 里）。

编码（RGBA16F）：
  R/G = mv.xy，单位是**渲染像素**（rgen 里已乘 renderSize）。UV 直接存的话
        典型量级只有 1e-3，屏幕上看不出任何东西（旧的 DEBUG==6 左半屏就是这样
        失效的）；像素单位可以直接按"每帧移动多少像素"判读。
  B   = mv.z（2.5D 的 viewZ 差，单位与 IN_VIEWZ 同口径）
  A   = 本帧这个像素的 MV 是**怎么得到的**，四态（rgen 侧赋值，取值都是 fp16 精确值）：
          0.00 = 天空 / 无效接收面（提前返回，那条路径只往 IN_MV 写 0）
          0.25 = 内层门 `previousClip.w > 1e-5` 失败 ⇒ MV 保持初值 0
          0.50 = 外闸 `historyValid==0 || frameCount==0` 失败 ⇒ MV 保持初值 0
          1.00 = 完整走完复投影
        加这一维的理由：MV 全 0 有三种完全不同的成因，只画数值无法区分；而
        「内层 previousClip.w 门整屏失败」正是源码里标注的头号嫌疑。

判读（合成端 NRD_COMPOSITE_DEBUG==8，见 rt_nrd_composite.comp）：
  白     = 整个屏幕都是 0.00（天空/无效接收面）
  绿     = 0.25 占了屏幕 ⇒ **内层 previousClip.w 门整屏失败，找到根因**
  黄     = 0.50 占了屏幕 ⇒ 外闸失败（historyValid/frameCount），往上查相机 UBO
  黑/红/青 = 真正算出的 MV 场（黑=0；红=+x/+y；青=−x/−y）
            相机匀速转动时应当是**光滑的渐变**（≈绕屏幕中心的旋转流场）⇒ MV 无罪；
            若是碎斑/随机反号 ⇒ MV 数值本身有问题。
