# OMM 缓冲池化：为何保持不设限

> 从 `cpp/VulkanCore.cpp` 搬出（原文）。结论已写回代码：本次不作限制，
> 要不要收紧留作单独一轮 A/B。

---

## churnTop 现场与"收紧到 2u 会退化成 32MB 分配抖动"的推导

原位置：`cpp/VulkanCore.cpp:18755-18788`（34 行）

2026-10-10 OMM 缓冲池化

现场（[RT MEM] 的 churnTop，2026-10-10 22:58~23:00 那一局）：
  use=0x820000 (MICROMAP_BUILD_INPUT_READ_ONLY|DEVICE_ADDRESS) n=13572 liveN=8   liveMB=0.1
  use=0x20020  (STORAGE_BUFFER|DEVICE_ADDRESS)                 n=8435  liveN=8   liveMB=0.4
  use=0x1000000(MICROMAP_STORAGE)                              n=6786  liveN≈500 liveMB≈10
前四名占全场分配的 99.5%，且 0x820000 的次数**正好是** 0x1000000 的 2 倍
⇒ createSectionOpacityMicromap 每次构建固定新建 4 个缓冲；实测 51 次构建/秒
⇒ 约 204 次 vkAllocateMemory/秒。可是句柄数几乎不动 ⇒ 全是"新建-即销毁"。
同期 vkAllocMB/vkFreeMB 显示 ~50MB/s 的分配吞吐，而进程提交以 15~30MB/s 涨
⇒ 每次 allocate/free 循环都在驱动侧留下可观的主机侧代价（碎片/记账），
  而不是某个活引用在漏。

做法与 Section 顶点/BLAS 完全相同：**只池化 VkDeviceMemory**。
  · VkBuffer 在 Vulkan 里只能被绑定一次（VUID-vkBindBufferMemory-buffer-07459），
    所以 buffer 仍每次新建 —— 那是 µs 级，不产生 vkAllocateMemory；
  · 内存从 Section slab 里切 chunk ⇒ 真 vkAllocateMemory 只在"没有可复用 chunk"
    时发生，204 次/秒直接降到近乎 0，并共用同一套命中统计（poolHit/poolMiss）。
=====================================================
2026-10-13：**刻意保持 32u（不设限）**，与本次改动之前的行为逐位相同。

上面写意图是 2u，意思是"OMM 几十 KB 的请求别从 4MB-chunk 的几何 slab 里切走
整 4MB"。但 takeSectionChunkFromSlabs 里那个上限当时用循环变量做基准，**从未
生效**（详见该函数 2026-10-13 的修正说明）—— OMM 一直是在借用几何 slab。

修好基准之后 2u 会真的生效，而那是另一件事，不能混进本次"只改几何"的改动：
  · OMM 的 4MB 借用是**极短命**的（[RT MEM] 实测 0x820000/0x20020 的
    liveN=8、liveMB=0.1，n=13572 ⇒ 借完立刻还），既不长期占容量也不形成浪费；
  · 一旦收紧到 2u，OMM 会退化成"自己买 slab"：请求几十 KB ⇒ chunkBytes=64KB
    ⇒ chunkCount 撞 512 上限 ⇒ 每块 **32MB**；而超预算时
    sectionMemoryPoolEffectiveKeepSlabs() = 0，空掉即 vkFreeMemory，
    以 OMM 的构建速率（实测 51 次构建/秒）会变成 32MB 级的分配抖动 ——
    正是本项目一直在躲的那种卡顿（[LOD PERF] max=1231ms 那一类）。
所以本次保持不设限。要不要收紧、以及配合什么样的保留策略，留作单独一轮 A/B。
