# NRD 本地光去噪：调参过程与定责记录

> 从 `cpp/VulkanCore.cpp` 里搬出的四段过程叙事（原文，未改写）。
> 代码里只保留结论、生效值与回退方式。

---

<a id="antilag"></a>

## antilag 对齐（第二次应用 D）

原位置：`cpp/VulkanCore.cpp:31287-31370`（84 行）

★ 2026-09-27 第二次应用 D：antilag 对齐。

触发证据：把累积窗口从 0.35s(23 帧) 提到 1.0s(63 帧) 之后，用户报告噪声
"几乎不变"。2.7 倍的累积窗口若不产生任何可见改善，只说明一件事——
**历史实际上没有在累积，一直被重置**。NRD 里唯一会持续重置历史的机制
就是 antilag：它按亮度变化量决定是否收紧/丢弃历史，而我们的输入是 1spp
的二值可见性，逐帧在「0」与「被照亮」之间摆动，会被它当成光照突变。

4.0（NRD 默认 2.0，合法范围 1.0-5.0），sensitivity 维持默认 3.0。
语义（NRDSettings.h）：luminanceSigmaScale 越大，"亮度梯度被局部方差
削减"的力度越大 -> antilag 对变化越不敏感 -> 越倾向于保住历史。
这正是我们需要的方向。

代价：antilag 更不敏感意味着真正的光照突变（开关灯）反应会变慢一点。
★★★ 2026-09-28 诊断：把 antilag **近似关闭**（生产值是 4.0 / 3.0，本行起为诊断态）。

依据（全部来自 NRD 源码，不是猜的）：
  REBLUR_ANTILAG_MODE = 2（REBLUR_Config.hlsli:111）时
    hc = Color::Clamp( a, s, h );                 // s = sigma * luminanceSigmaScale
    d  = abs( h - hc ) / ( max( h, hc ) + eps );   // 超出方差带的相对亮度差
    antilag = 1.0 / ( 1.0 + d * accumSpeed / magic );
    magic   = luminanceSensitivity * ( 2 * frameRateScale )^2
  （REBLUR_Common.hlsli:237-265）
两个旋钮都朝"更宽容"方向：
  * luminanceSigmaScale 越大 -> 方差带 s 越宽 -> hc 越常等于 h -> d 越接近 0
  * luminanceSensitivity 越大 -> magic 越大 -> d*accum/magic 越小 -> antilag 越接近 1
取 sigmaScale=8、sensitivity=1e4：magic 约 4e4，而 accumSpeed 最大 63，
于是 d*accum/magic ≤ 63/4e4 ≈ 1.6e-3，antilag ≈ 0.998，等于关掉。

为什么这是决定性的：因为 REBLUR_USE_ANTILAG_NOT_INVOKING_HISTORY_FIX = 1，
`data1.x = lerp( min(data1.x, 3), data1.x, antilag )` 里的 min() 只会封顶、
不会加分。antilag 一旦落到 0，累积计数就永远停在重置后的低值上 —— 既产生
"全屏橙"，也产生棋盘。所以：
  关掉 antilag 后视口 8 在移动时变绿/青 => antilag 就是根因，确诊
  仍然全橙                            => 累积计数不是被"压住"的，而是从来没
                                         长起来，矛头转向写回路径
代价：若确诊，画面会开始出现拖影（历史真的活了）。这是预期，不是新 bug。
回滚：恢复 4.0f / 3.0f。
2026-09-28 实测结论：**antilag 不是根因，已改回生产值 4.0 / 3.0。**

做过的事：按下面这段推导把 antilag 近似关闭（sigmaScale=8、sensitivity=1e4，
使 magic≈4e4 而 accumSpeed≤63，于是 d*accum/magic ≤1.6e-3、antilag≈0.998），
用户报告"还是橙色棋盘格" —— 累积计数依然长不起来。

这一步的意义比它本身大：到目前为止被排除的机制有
  * 平面距离闸门（阈值放到 saturate 天花板都无效）
  * 法线闸门（cos(89°) 根本不会触发）
  * antilag（近似关闭也无效）
也就是说累积计数**不是被任何"拒绝机制"压住的**，它压根没有被加上去。
结论：继续拧 NRD 的参数没有意义，方向应转向"每帧 +1 的写回/混合路径"
（smbAccumSpeed 的加权混合、gPrev_InternalData 的读回）而不是闸门。
★ 2026-09-29：antilag 回归 NRD 默认。
依据：官方 README 对新集成的要求是「Antilag parameters need to be carefully tuned.
Initial integration should be done with disabled antilag.」——而我们现在的
1spp 二值可见性信号对 antilag 的"亮度突变"判定本就不友好；history confidence
（第二步）才是官方指定的"哪里变了就重置哪里"的机制，它接上后再谈 antilag 调参。
回退：取消下面两行注释即恢复 4.0 / 3.0。
settings.antilagSettings.luminanceSigmaScale = 4.0f;
settings.antilagSettings.luminanceSensitivity = 3.0f;

★★★ 2026-09-29 14:5x 复测（用户："噪点还在"，要求按官方文档深挖）：
  上一轮"antilag 近似关闭无效"的结论**是在 IN_MV 仍混着相机抖动（NRD validation
  视口 3 整帧洋红）的状态下得到的**——那时复投影落点系统性偏 0.25~0.5px，历史被
  `smbFootprintQuality` 压住，antilag 是不是根因根本测不出来。现在 MV 已修，
  必须复测这一格。
  **本轮证据**：validation 视口 8 用其自带图例标定后实测，历史只停
  **6~16 帧 / 满量程 63**（官方口径要求"停在紫区"），且短的地方正是被本地光
  照亮、1spp 二值可见性让逐帧亮度大幅摆动的墙面（用户截图的噪点位置一致）；
  而太阳照亮、信号平滑的区域能达到 46~54 帧 ⇒ 恰好是 antilag 的触发特征
  （它与"局部方差带"比较，方差小的平滑区域不触发）。
  取值沿用 09-28 的诊断态（sigmaScale=8、sensitivity=1e4 ⇒ antilag≈0.998，等于关掉），
  与 confidence 修复一起上，避免连续两次重启。
  判读（重启后看顶部色带 + 画面）：色带转青/白 ⇒ 历史活了、噪点应明显下降；
  色带仍黄/红 ⇒ 累积计数不是被 antilag 压住的，矛头转向 smbFootprintQuality /
  写回路径（此时 antilag 这一格已被彻底排除）。
★ 2026-09-29 第 2 项：回到 4.0 / 3.0，撤销"近似关闭"的诊断态。

Radiance 的 REBLUR 实例显式设
  antilagSettings.luminanceSigmaScale = 4.0f;  antilagSettings.luminanceSensitivity = 3.0f;
（其 RELAX 实例用的是另一组旋钮 accelerationAmount / resetAmount，与此无关）。
我们自 09-28 起留的是诊断态 8.0 / 1e4（magic≈4e4、accumSpeed≤63 ⇒
d*accum/magic ≤ 1.6e-3 ⇒ antilag≈0.998，等价关闭），而 09-28 的实测结论已经写明
"antilag 不是根因"。既然不是根因，就没有理由继续留在非生产值上。
回退：改回 8.0f / 10000.0f 即恢复诊断态。

<a id="lag-tuning"></a>

## lag tuning 第二遍（三个旋钮）

原位置：`cpp/VulkanCore.cpp:31159-31233`（75 行）

NRD-1 lag tuning (2026-09-24, second pass).  The first integration removed the
local-light noise but left a visible after-image under motion.  The cause is
temporal lag, and the two dials below are the ones NRD provides for it.  Both
push the same way, so this build is a single yes/no test: if the after-image
drops, lag was the cause and these get fine-tuned; if it does not move at all,
the cause is reprojection instead and no amount of window tuning will help.

  maxAccumulatedFrameNum - the slow "main" history.  NRD's default period is
    0.5 s, which at 60 FPS is ~30 frames.  The pass this replaced used ~50
    frames when static but only ~8 when the camera moved, so 30 is already
    shorter statically; the problem is that nothing shortened it under motion.
    0.35 s (~21 frames at 60 FPS) trims the worst of it while staying
    FPS-independent, which is the whole point of expressing it in seconds.

  fastHistoryClampingSigmaScale - the actual lag control, and the CHEAP one.
    REBLUR clamps the spatially processed slow history to a colour box built
    from the responsive 6-frame fast history; a smaller sigma is a tighter
    box, so the result tracks the fast history (less lag) instead of the slow
    one.  NRD's default is 2.0, valid range [1; 3].

    Why this knob and not the window: the two are NOT symmetric in cost.
    Lowering maxAccumulatedFrameNum removes lag and averaging together, so it
    buys lag with noise.  Lowering the sigma only limits how far the output
    may deviate from the fast history; when the scene is static the slow
    history is still free to accumulate, so the averaging is kept.  It is the
    free lever, so the second tuning step pushes ONLY this one.

Second step (2026-09-24, after the user confirmed the after-image was
"clearly reduced" at 1.5/0.35 s and that the noise had NOT come back): sigma
1.5 -> 1.1 with the window left alone.  1.1 is the bottom of NRD's documented
useful range ("1.5 works well even for dirty signals, 1.1 is a safe value for
occlusion denoising"), so this step deliberately aims at the noise wall: if
noise returns here, the answer is a sigma between 1.1 and 1.5 and it is found
by bisection; if it does not, the after-image is gone for free.
NRD-1 lag tuning (carried over from the REBLUR bring-up).  RELAX exposes the
same two families of dials, under diffuse*/specular* names (RELAX_DIFFUSE
only reads the diffuse ones):

  diffuseMaxAccumulatedFrameNum - the slow "main" history.  NRD's default is
    30.  The pass this replaces used ~50 frames when static but only ~8 under
    motion, so the FPS-invariant seconds-based formula is kept:
    maxAccumulatedFrameNum = accumulationPeriodInSeconds * FPS, clamped to
    RELAX_MAX_HISTORY_FRAME_NUM (255 -- five times REBLUR's 63, so the same
    0.35 s period is now never the binding constraint).

  fastHistoryClampingSigmaScale - the lag control.  RELAX clamps the slow
    history to a colour box built from the responsive fast history; a smaller
    sigma is a tighter box, so the output tracks the fast history (less lag).
    NRD's default is 2.0, and the REBLUR-era bisection landed on 1.1.
=====================================================
D：对齐 NRD 参数（2026-09-27，在 NRD 旁路实验证明"NRD 确实在做实事、
只是强度不够"之后执行）。

事实核对（避免误称"我开了什么"）：
  * enableAntiFirefly —— REBLUR 的结构体默认值就是 **true**（NRDSettings.h:339，
    RELAX 才是 false）。我们 `settings = {}` 已经吃到该默认值，所以这次**没有**、
    也无法"打开"它：它本来就是开的。RELAX 阶段才短暂变成关闭状态。
  * diffusePrepassBlurRadius / minHitDistanceWeight —— REBLUR 默认 30.0 / 0.1，
nrd.yaml 也是 30.0 / 0.1，本来就一致。
所以真正有差异、也是本次要改的只有下面三项。

1) 累积窗口 0.35 s (~23 帧) -> 1.0 s (~63 帧，被 REBLUR_MAX_HISTORY_FRAME_NUM 钳住)。
nrd.yaml 是 max_accumulated_frame_num 60。这是本次的主要杠杆：
   降噪强度直接由累积帧数决定，噪声随 1/sqrt(N) 下降。

2) fastHistoryClampingSigmaScale 1.1 -> 1.5。
   ★ 这一项暴露了我自己的问题：1.1 是我为了压残影特意调紧的（低滞后取向），
   但紧钳制会**削弱累积效果** —— 输出被拉向 6 帧的快历史，慢历史的平均量吃不到。
   也就是说"残留噪声"里有相当一部分是我当初压残影时付出的代价。
   ★ 代价要说清：1.5 会让残影回到你此前验收过的"明显减轻"那一档，而不是
   1.1 那一档更好的残影表现。这次选择用一点残影换降噪。

3) hitDistanceReconstructionMode OFF -> AREA_5X5。
   注意 NRD README 说它是"主命中做漫反/镜面概率拆分时"的必需项；我们没有做拆分，
=====================================================

<a id="motionvectorbias"></a>

## motionVectorBias 曾被误判为根因

原位置：`cpp/VulkanCore.cpp:30864-30926`（63 行）

★★★ 2026-09-29 一度判为根因：motionVectorBias 必须补偿相机抖动，不能是 0。

⚠️ 该判断**已被推翻**，不要据它重开开关。同段末尾（"2026-09-29 实测结论"起）
   与 HANDOFF-2026-09-29-RT-DI-NRD.md §3 第 5 条都记着结论：raw mv 本身已经
   是"非抖动"的（README:435 的要求就是 "must be non-jittered after scaling and
   bias"），再加 bias 等于把抖动量重新加回去；实测加与不加画面无变化。
   下面这一大段推导保留为历史记录。

此前写的是「两个 UV 都取自不含 jitter 的投影矩阵，MV 本身已经非抖动，所以 bias = 0」。
那句话对 **mv 的差值**成立，但对 **smbPixelUv 的落点**不成立，二者是两件事：

  rgen 侧：mv = prevUv(P) − curUv(P)，其中 curUv(P) 是**抖动后**像素中心 P 的投影
           （P 由抖动后的主射线命中点反解，见 rt_restir_final.rgen 的
            nrdGeometryViewPos / primaryPixelCenter）。
  NRD 侧：smbPixelUv = pixelUv + mv + bias，pixelUv 是**未抖动**像素中心。

于是（把上面两式相减）：
  smbPixelUv = prevUv(P) − jitter_cur + bias
而「上一帧存在像素 u 处的 IN_VIEWZ」的物理含义是：
  非抖动 uv 为 u + jitter_prev 的那个点的深度。
要让 NRD 采到 **P 自己** 的上一帧深度，必须内层对齐：
  smbPixelUv + jitter_prev = pixelUv + jitter_cur
  ⇒ bias = jitter_cur − jitter_prev        （像素换算成 UV：/ renderSize）

为什么这个 0.25~0.5px 的残差是致命的：
  NRD 的拒史深度门是 |prevViewZ_tap − Xvprev.z| ≤ 0.01 * frustumSize
  （REBLUR_TemporalAccumulation.cs.hlsl:259-267 / Common.hlsli:585），
  3 格处约 0.04 格。而方块世界里每个方块边界都是一次深度跳变，复投影落点偏
  半个像素就足以让它落到另一个方块面上 ⇒ 四个 tap 全判遮挡 ⇒
  smbFootprintQuality = 0 ⇒ c = min(smbFootprintQuality, IN_DIFF_CONFIDENCE) = 0
  ⇒ 历史上据此推得"累积停在 (1+√5)/2 ≈ 1.618 帧不动点"。
     ⚠️ **该模型已被证伪**：四 tap 全拒时 A 不是"小"而是**恰好 0**
     （ApplyBilinearCustomWeights 的 dot(w,1)<1e-4 硬分支返回 0），
     对应的是 **0 帧**。见 HANDOFF-2026-09-29-RT-DI-NRD.md §11.3 / §11.9。

静止时为什么看不出来：主机位不动 ⇒ mv = 0，且 jitter 逐帧仍在变，但
  prevViewZ_tap 与 viewZ 在同一像素上自洽 ⇒ 门恒过 ⇒ 累积到 63 帧（白）。
  而一旦移动，smbPixelUv 才真正离开本位，残差才暴露。

为什么以前每一刀都「有反应、没治好」：
  * confidence 钉成 1（FORCE_ONE=1）无效 —— min() 的另一支 smbFootprintQuality 才是 0
  * disocclusionThreshold 放到 0.5 无效 —— 那次是在 mv.z 符号仍错的构建上做的，
    误差是「整格量级」，任何阈值都吸不掉，所以那一轮证据无效
  * 几何口径（IN_VIEWZ/法线/MV 的来源）改动无效 —— 残差在落点上，不在来源上

单位口径（已核对 NRD 源码）：mv 与 gMvBias 是 **UV**；cameraJitter 是 **像素**
  （REBLUR_Validation.cs.hlsl:151 先 * gRectSize 再与 gJitter 比较）。
  宿主传入的 currentJitterX/Y 正是像素单位、且与主射线/本 pass 用的是同一个
  GetDLSSFrameJitter(cam.frameCount)，所以这里只需 / renderSize。

★★ 2026-09-29 实测结论：这个 bias **是错的，已置 0**。

加它的推导是「smbPixelUv 差了一个 (jitter_cur − jitter_prev)」，但按 NRD README:564-567
对视口 3 的定义重新推一遍就发现前提错了：
  视口 3 的期望 MV = 「假定场景静止、对该像素按**未抖动**重建算出的 MV」
  desired = prevUv(Q) − pixelUv        （Q = 该像素中心射线上的点）
  我们写的 raw mv = prevUv(P) − project_cur(P) = prevUv(P) − pixelUv − jitter_cur
  而 prevUv(Q) ≈ prevUv(P) − jitter_cur（一阶：屏幕位移在小量下近似不变）
  ⇒ desired = raw mv                         ← 两者相等
也就是说 **raw mv 本身已经是「非抖动」的**（README:435 的原文要求就是
"must be non-jittered after scaling and bias"），再加 bias 等于把抖动量重新加回去。
实测也印证：加上去之后转视角仍 1-2 帧，没有任何变化。


<a id="local-di-black"></a>

## 本地光全灭修复（NRD 总开关）

原位置：`cpp/VulkanCore.cpp:30570-30631`（62 行）

=====================================================
2026-09-26 本地光全灭修复（总开关，改回 true 即恢复 NRD 实验）。

证据链（rt_nrd_composite.comp 里的三分屏探针，2026-09-26）：
  M >= 16（reservoir 样本在）、RayGen 交下来的原始本地光项非零，
  但 composite 加进画面的项恒等于 0。
反照率打包/传递/解包约定在 rgen -> spatial -> final -> composite 四处
与老的 rt_restir_reconstruct.comp 完全一致（老链路能亮），所以清零
乘数锁定为 NRD REBLUR_DIFFUSE 的输出：NRD-1 这套集成喂给 REBLUR 的
引导输入（viewZ 口径今晚还从径向距离改成 clip.w、motion/reprojection
缺失）没对齐，REBLUR 输出 0，composite 原样信任它。

在 NRD 的输入接口修好之前，整体旁路：g_nrdReady 保持 false，
  * rt_restir_final.rgen 的三个 NRD 输入照常写（无害）；
  * 34304 行的 !g_nrdReady 门控让老的 rt_restir_reconstruct.comp
    5/9/13-tap 时域滤波重新接管本地光降噪（09-25 收尾之十三
    验证过的已知良好状态）；
  * composite pass 不再 dispatch。
=====================================================
2026-09-26 深夜：REBLUR -> RELAX_DIFFUSE 切换（NVIDIA 官方为 RTXDI 直射
光设计的家族），打包约定同步改（原始 hitDist、无 YCoCg、radiance clamp
250），NRD 链重新启用。旁路回退：改回 false 即退回 reconstruct 时域链。
2026-09-27 20:2x 终裁：本地光链
没有降噪器——确定性辐射不进 denoiser。实测 RELAX 把 0.16-0.32 的健康
输入衰减到 ~0.06（本地光不亮的直接根因），shader 侧已停消费/停喂 NRD
（RESTIR_DI_CONSUME_RELAX / RESTIR_DI_FEED_NRD，均可用 -D 逐字节回滚）。
C++ 侧同步旁路：g_nrdReady=false，reconstruct 5/9/13-tap 时域滤波接管，
composite 停发。NRD 集成代码原地保留，改回 true 可整体恢复。
2026-09-27 晚：恢复 NRD（REBLUR_DIFFUSE）。上一版把整个 NRD 从本地光链上
摘掉，理由是"本地光链没有降噪器"。那句只对链条本身成立——降噪器
其实在下游（indirect.rgen.slang:16 "temporal convergence is the
denoiser's job"，guides.slang:277 "consumed by the denoiser/DLSS-RR"），
它的 RIS 之所以是单帧不复用 reservoir，正是因为 DLSS-RR 在最后做时域收敛。
而在本项目里 DLSS-RR 已被实测证明去噪不了这个 1spp 时间不相干信号
（rrMode 四档全试过无变化）——那正是引入 NRD 的原因。摘掉 NRD 的结果就是
1spp 原始项直接上屏，即"很强烈的噪点"。
同时 RELAX -> REBLUR：RELAX 实测把 0.16-0.32 的健康输入衰减到 ~0.06，
且 NRD 官方对它的定位是"极干净的高 RPP 信号 / RTXDI"，不是我们的 1spp。
回滚：改回 false 即整体退回 reconstruct 5/9/13-tap 时域链。
=====================================================================
★★ 实验已结束（2026-09-27 22:11 那次运行）：关掉后画面「明显更噪」，
所以 NRD 确实在做实事，问题不在"降噪器没生效"，而在"降噪强度不够"。
已改回 true，并按实验预注册的分支执行 D，
见下方 nrdBuildReblurSettings。下面这段判读说明保留作为记录。 ★★

设为 false 的后果（已逐处核对门控）：
  * ensureNRDResources 立刻返回 -> NRD 实例从不创建 -> g_nrdReady 恒为 false
  * 34355 行的 `executeReSTIRDIReconstruct && !g_nrdReady` 让**老的
    rt_restir_reconstruct.comp（5/9/13-tap 时域滤波）**重新接管本地光降噪
  * 34476 行的 `if(g_nrdReady)` 让 NRD composite 不再 dispatch

所以这是一次 NRD 与老手写滤波的对照：两者都降噪，看哪一个真的在起作用。

判读方法（一次启动即可）：
  关掉后明显更噪   -> NRD 确实在做实事 -> 问题在降噪器不足 -> 下一步走 D
                       （开 anti-firefly、开 5x5 hit-distance 重建、
                         把累积窗口从 23 帧提到 ~60）
  关掉前后几乎一样 -> NRD 没起作用 -> 问题在估计量方差本身 ->
                       下一步走 C（候选数 4 -> 32）

回滚：把本行改回 true 即完整恢复（NRD 集成代码原地保留，未删任何东西）。
=====================================================================

<a id="diffuse-prepass"></a>

## 第三次应用 D：关掉 diffuse prepass blur

原位置：`cpp/VulkanCore.cpp:31139-31179`（41 行）

★ 2026-09-27 第三次应用 D：关掉 diffuse prepass blur（这是 11 轮里从未碰过的参数）

依据（来自用户截图 + Advisor 的方向性分析）：
  截图里地面的条纹**基本水平**：沿屏幕 X 拉长、垂直方向只有几个像素，
  而且平行于屏幕坐标轴。这是**屏幕空间的方向性相关**，采样方差不会长这样
  （方差是随机颗粒）。方向性相关最常见的来源就是**可分离滤波**——而 REBLUR
  的 prepass 正是一个大半径空间预滤波。

关键：diffusePrepassBlurRadius 的 REBLUR 默认值是 30.0，我们 `settings = {}`
一直吃到这个默认值，**从本次会话开始到现在从未被修改过**。这就解释了为什么
条纹的"形状"对所有改动都不变——我一直改的都是别的参数（降噪器模型、累积窗口、
sigma、antilag、候选数、种子、donor 信任、渲染分辨率），而真正产生条纹的
这个参数**一次都没被碰过**。

30 像素的半径在 707x376 的渲染分辨率上相当于屏幕宽度的 4%，对 1spp 输入来说
是极重的空间滤波。

回滚：把两行改回 30.0f / 50.0f 即恢复 REBLUR 默认行为。
若本条无效（条纹不变），则说明条纹在滤波上游，下一步改用
RESTIR_FINAL_DEBUG_RAW 直接转储 pre-NRD 原始估计值来定位。
★ 2026-09-29：**恢复官方默认的 pre-pass**（30 / 50），撤销此前置 0 的改动。
依据：NRD README 的命中距离要求里明确写着
  "Pre-pass must be enabled (i.e. diffusePrepassBlurRadius and
   specularPrepassBlurRadius must be non-0)"
且 "Input signal quality can be improved by enabling pre-pass"。
我们的输入是 1spp 二值可见性，属于"脏信号"，正是 pre-pass 的适用场景；
13:46 录屏的满幅高频噪点也需要这道空间预滤波。
旧结论（"prepass 产生水平条纹"）来自三层叠加时期，单降噪器下需重新评估；
若条纹复现，回退两行为 0.0f（并记录）。
★ 2026-09-29 第 2 项：diffuse 置 0，撤销 30 / 50 的"官方默认值"。

依据一（Radiance）：REBLUR 实例（diffuse=0.0f, specular=20.0f）与
  RELAX 实例（diffuse=0.0f, specular=0.0f）的 diffusePrepassBlurRadius **都是 0**。
依据二（NRD README:905-910）："Pre-pass must be enabled" 这条约束只适用于
  "In case of probabilistic diffuse / specular split at the primary hit"——
  我们不做主命中的概率性 lobe 拆分（见本函数上方 19162 行的说明），前提不成立。
依据三（NRD 源码 Reblur.cpp:119）：
  skipPrePass = (diffRadius==0 || !hasDiffuse) && (specRadius==0 || !hasSpecular)
  本实例只有 diffuse ⇒ 置 0 会**整条跳过 pre-pass**。AREA_5X5 的命中距离重建不受影响，仍然在跑
  （Reblur.cpp:135-142 会把 passIndex 切到 +2 变体）。
回退：改回 30.0f / 50.0f。

<a id="diffuse-prepass"></a>

## 第三次应用 D：关掉 diffuse prepass blur

原位置：`cpp/VulkanCore.cpp:31139-31179`（41 行）

★ 2026-09-27 第三次应用 D：关掉 diffuse prepass blur（这是 11 轮里从未碰过的参数）

依据（来自用户截图 + Advisor 的方向性分析）：
  截图里地面的条纹**基本水平**：沿屏幕 X 拉长、垂直方向只有几个像素，
  而且平行于屏幕坐标轴。这是**屏幕空间的方向性相关**，采样方差不会长这样
  （方差是随机颗粒）。方向性相关最常见的来源就是**可分离滤波**——而 REBLUR
  的 prepass 正是一个大半径空间预滤波。

关键：diffusePrepassBlurRadius 的 REBLUR 默认值是 30.0，我们 `settings = {}`
一直吃到这个默认值，**从本次会话开始到现在从未被修改过**。这就解释了为什么
条纹的"形状"对所有改动都不变——我一直改的都是别的参数（降噪器模型、累积窗口、
sigma、antilag、候选数、种子、donor 信任、渲染分辨率），而真正产生条纹的
这个参数**一次都没被碰过**。

30 像素的半径在 707x376 的渲染分辨率上相当于屏幕宽度的 4%，对 1spp 输入来说
是极重的空间滤波。

回滚：把两行改回 30.0f / 50.0f 即恢复 REBLUR 默认行为。
若本条无效（条纹不变），则说明条纹在滤波上游，下一步改用
RESTIR_FINAL_DEBUG_RAW 直接转储 pre-NRD 原始估计值来定位。
★ 2026-09-29：**恢复官方默认的 pre-pass**（30 / 50），撤销此前置 0 的改动。
依据：NRD README 的命中距离要求里明确写着
  "Pre-pass must be enabled (i.e. diffusePrepassBlurRadius and
   specularPrepassBlurRadius must be non-0)"
且 "Input signal quality can be improved by enabling pre-pass"。
我们的输入是 1spp 二值可见性，属于"脏信号"，正是 pre-pass 的适用场景；
13:46 录屏的满幅高频噪点也需要这道空间预滤波。
旧结论（"prepass 产生水平条纹"）来自三层叠加时期，单降噪器下需重新评估；
若条纹复现，回退两行为 0.0f（并记录）。
★ 2026-09-29 第 2 项：diffuse 置 0，撤销 30 / 50 的"官方默认值"。

依据一（Radiance）：REBLUR 实例（diffuse=0.0f, specular=20.0f）与
  RELAX 实例（diffuse=0.0f, specular=0.0f）的 diffusePrepassBlurRadius **都是 0**。
依据二（NRD README:905-910）："Pre-pass must be enabled" 这条约束只适用于
  "In case of probabilistic diffuse / specular split at the primary hit"——
  我们不做主命中的概率性 lobe 拆分（见本函数上方 19162 行的说明），前提不成立。
依据三（NRD 源码 Reblur.cpp:119）：
  skipPrePass = (diffRadius==0 || !hasDiffuse) && (specRadius==0 || !hasSpecular)
  本实例只有 diffuse ⇒ 置 0 会**整条跳过 pre-pass**。AREA_5X5 的命中距离重建不受影响，仍然在跑
  （Reblur.cpp:135-142 会把 passIndex 切到 +2 变体）。
回退：改回 30.0f / 50.0f。
