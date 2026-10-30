# 第一人称手部与叠加层（描边 / 挖掘裂纹）：现状与方案

这份文档是**调研结论 + 待决策方案**，不含代码改动。起因：想照搬参考实现
（NVIDIA rtx-remix-minecraft）的 `rtx.viewModel.enable` / `perspectiveCorrection`
与 `RemixBlockOutlinePass` / `RemixBlockDamagePass`。逐行读完之后，这两处在本工程都
**不能直接照搬**，原因如下。

---

## 1. 第一人称手：本工程刻意走原版光栅

| 事实 | 证据 |
|---|---|
| RT 合成在**手之前**上膛，手随后由原版光栅画在 RT 之上 | `mixin/GameRendererMixin.java` `rtx$armRtInjectionBeforeHand`（`renderItemInHand` HEAD），注释原文："明确**不把第一人称手放进光追**、让原版光栅画它" |
| 这是"手看不见"的根治点（此前 RT 在 GUI 前上膛，把手整片盖掉） | 同上方法注释 |
| `EntityRtCapture.beginHandPhase()` **全仓没有调用者** | `DynamicLightCapture.java` 注释直接承认："捕获链路在本工程里其实是断的" |
| 即使把窗口打开，手部批次会**把玩家皮肤临时换成方块图集** | `EntityRtCapture.java:1115-1128`，注释自称"仅诊断用" |

于是 `beginHandPhase` 是一段**未完成的诊断代码**：它既没有调用者，开起来还会把手的
贴图换成方块图集。参考实现的 view-model 相机（同一位置/朝向、另一套投影、单独合成）
是给**被光追的手**用的；我们的手根本不进光追，所以"独立相机 + 透视校正"在这个结构下
没有作用对象。

## 2. 描边与挖掘裂纹：原版有，RT 里没有

| 事实 | 证据 |
|---|---|
| 描边经 `SubmitNodeCollector.submitShapeOutline(..., RenderTypes.linesDepthBias(), ...)` 提交，是**线段** | MC 26.3 `LevelRenderer.submitBlockOutline`（`submitHitOutline`） |
| 挖掘裂纹经 `LevelRenderer.submitBlockDestroyAnimation` 同样走 node collector | 同上 |
| 原版世界光栅输出被 RT 整帧替换 ⇒ `renderLevel` 里画的东西全部消失 | `TextGlyphRendererMixin.java:19-21` 注释 |
| 我们这边**没有任何** outline / destroy 相关代码 | `src/**` grep `outline|destroyStage|crumbling` 仅命中 `LevelRendererMixin` 两个未使用的形参；`cpp/VulkanCore.cpp` 0 命中 |
| 裂纹可能被通用实体路径顺带捕获（`beginCustomSubmit` 只拒 `shadow` 与 `screen_effect`） | `EntityRtCapture.java:2190-2211`；若捕获则与方块面**共面**，必然闪烁 |

注意：RT 管线是三角形-only，被当成三角形的线段会退化；所以"把原版几何照收"不是选项。

## 3. 要做的两条路线（需先决定，再动 70k 行的单个 TU）

### 路线 A：RT 侧"无光照叠加层"（描边 + 裂纹，推荐先做）

新增一个与 ENTITY / LOCAL_PLAYER 同构的 **OVERLAY 层**，只接受 `(位置, 常量色, alpha)`
三角形、不采样贴图、不参与光照与阴影、带固定法线外扩（避免与方块共面）：

1. 原生：新增 `uploadOverlayBatch(...)`（无 textureSlot），复用实体层已有的顶点/索引
   路径与 TLAS 实例机制，但实例掩码只给主射线与主反射；
2. shader：rchit 里按层标记直接返回常量色（不查材质），`rt_test.rgen` 里跳过叠加层的
   二次弹射；
3. Java：`LevelRendererMixin` 已经拿到 `renderOutline` 形参与 `blockOutlineRenderState`，
   在 `submitBlockOutline` 的同一时刻把 `VoxelShape` 的边转成细长方体（宽度按屏幕
   约 1.5 像素折算），裂纹则用 `BlockDestructionProgress.stage` 贴图的四边面 + 外扩
   1e-3 格；
4. 验收：站立不动时描边与裂纹**不闪**、随昼夜变亮变暗但不自发光过曝、进水面/岩浆里
   不出现黑边；关掉开关回到当前行为。

代价：原生约 200~300 行（含新层 key、清空、退役、TLAS 掩码）+ shader 两处分支 +
Java 约 150 行。风险集中在单个 2.6MB TU，**必须用 `.tmp/patch_*.py` 逐条 `count(old)==1`
打补丁**（见仓库既有约定），不支持用文件编辑器直接改。

### 路线 B：手部进光追 + 独立 view-model 相机（大工程，暂缓）

必须同时做四件事，缺一件就是坏画面：

1. 在 `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` 上开窗（该类存在，
   等价于 `beginHandPhase/endHandPhase`），并**去掉那段贴图替换的诊断代码**；
2. ~~手部几何改用"相机相对 + view→world 旋转"（`EntityRtCapture.java:1382-1402` 已有）~~ ——
   **2026-10-25 更正：这一步不需要，而且现有实现有害。** MC 26.3 的
   `GameRenderer.renderItemInHand`（`GameRenderer.java:382-396`）在提交前已经
   `poseStack.mulPose(viewRotationMatrix.invert())`，交出来的顶点就是"世界朝向 + 相机相对"，
   与实体层约定一致。因此 `handRotation` / `handTransformMode` 那套旋转与三选一启发式
   建立在错误前提上，正确处理是删除（详见根目录
   `HANDOFF-2026-10-25-FIRST-PERSON-HANDS.md` §2.1）；
3. **抑制原版光栅手**（否则双重手影），这要改 `renderItemInHand` 的注入语义 ——
   正是当年"手看不见"那个 bug 的修复点，回归风险最高；
4. 透视校正：视空间 XY 统一缩放 `k = tan(fovMain/2) / tan(fovViewModel/2)`（针孔相机下
   等价于换焦距）。这是逐顶点变换，`EntityRtCapture` 本来就在逐顶点动手，不需要新
   原生接口。

验收：手持物品与手臂在视野边缘不变形；手在手电/火把下会投影；进水面不出现"手浮在
水面上"；与原版光栅手逐帧比对无重影。

## 4. 明确不做的事

- **不**给 `beginHandPhase` 接调用者。它现在会把手的贴图换成方块图集，开起来是视觉回归，
  不是修复。
- **不**在 `beginCustomSubmit` 里凭猜测拒掉 `lines` / `crumbling`。二者今天到底是被
  捕获了还是丢掉了，需要一局带 `-Drtx.debug=true` 的实测日志才能确认；在此之前动过滤
  条件属于盲改。
