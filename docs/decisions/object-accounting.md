# 对象创建总账（诊断）：为什么要第二把刀

> 从 `cpp/VulkanCore.cpp` 搬出（原文）。代码里保留"这个宏拦什么、拦不到什么"。

---

## 2026-10-15 两局实测：vk 账冻结而 RSS 仍涨

原位置：`cpp/VulkanCore.cpp:667-704`（38 行）

2026-10-15 对象创建总账（诊断：「越玩越卡」那条宿主内存增长的第二把刀）

为什么还要一把刀。[RT MEM] 的 vkAllocMB/vkFreeMB/vkLiveMB 只覆盖
vkAllocateMemory/VkDeviceMemory（见上面的宏）。2026-10-15 两局实测：
  · 18:37:54 → 18:39:29（95 秒 / 6660 帧 / 70.1 fps）：vkAllocMB、vkFreeMB、
    vkLiveMB、vkAllocN、vkFreeN、slabAllocMB、slabFreeMB **逐字节冻结**，
    vramUsedMB 也冻结，NMT total committed 平 ⇒ 这 95 秒一次 vkAllocateMemory
    都没做；而同一窗口 rssMB 12309.1 → 15026.7（+2718MB = 28.6MB/s）、
    privateMB +2861MB、sysAvailMB −2676MB，折算 **0.408MB/帧**。
  · 19:27:32 → 19:28:55（83 秒）：NMT 全类别合计只 +62.6MB（Java Heap +72，
    Thread/Code/GC/Metaspace/Arena/Class 全部 ±5 内），而 rssMB +2778MB
    ⇒ 扣掉已知项后仍有 ≈26MB/s 无法归属。
⇒ 增长既不经过 vkAllocateMemory、也不在 JVM 里。剩下的只有两类，而这两类
  可以用一把刀切开：
    (a) **不调 vkAllocateMemory 的 Vulkan 对象**——描述符池/集、命令池/缓冲、
        pipeline layout / shader module / compute pipeline / image view / sampler。
        它们在驱动侧各占一块宿主内存，且命令池、描述符池**从不收缩**：
        vkFreeCommandBuffers / vkFreeDescriptorSets 只是把对象还给池，池自己
        申请过的那几块 chunk 不会退给 OS。本文件里 vkResetCommandPool 与
        vkResetDescriptorPool 都是 **0 处**（靠 alloc/free 复用），所以"每帧
        alloc/free 一个池对象"是这条曲线上最典型的一种形态。
    (b) **驱动 / Streamline（sl.dlss*.dll）内部**——本模组的任何包装都看不见它
        （本文件上方 :8174-8203 的注释已经把这条路指出来了）。

口径。只数**创建/分配**侧，不数销毁侧。本刀要回答的是"谁在按帧/按批造对象"：
建数平就说明不在这一层，不需要成对；而为了成对要在 9 个销毁 API 上再各插一刀，
风险大一截、信息量为零。复数型 API 计**对象数**（取 count 字段）而不是调用次数
（descSet / cmdBuf / pipe 三项），因为占内存的是对象个数。只在 VK_SUCCESS 时计
（失败时并没有对象产生）。

判读：
  · 某一项随会话单调涨（尤其每帧/每批固定条数）⇒ 就是它，去那张未池化的表找；
  · 全部平、而 rssMB/privateMB 仍按 ≥26MB/s 涨 ⇒ **不在 Vulkan 层**，转去查
    驱动 / Streamline（下一步上 VK_EXT_device_memory_report 逐笔报驱动侧分配）。

闸门：ORION_MEM_OBJ_TRACK（默认开；=0 时包装只转发、不计数）。
本刀**零行为变更**：返回值、参数、调用顺序与包装前逐位一致。

