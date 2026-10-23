package com.example.client;

import com.example.client.mixin.RenderSetupAccessor;
import com.example.client.mixin.RenderTypeAccessor;
import com.example.client.mixin.TextureBindingAccessor;
import com.example.vulkan.NativeVulkan;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.example.client.config.RTXSettingsConfig;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** Captures 26.2 entity-model batches and the explicit experience-orb quad. */
public final class EntityRtCapture {
    // 2026-10-06：16 → 32。必须与 VulkanCore.cpp 的 ENTITY_RT_TEXTURE_SLOT_COUNT
    // 以及 rt_test.rchit / rt_test.rahit 里的 `sampler2D entityAtlases[N]` 一致。
    //
    // 2026-10-13：32 → 64。城市地图（Broville v11.4.4）下 31 个常规槽被打满，一帧
    // 内 evictInUse=2736 次 —— 被抢占的槽号已被本帧早先上传的顶点写进 pad2，于是
    // 那些几何改采到新贴图（"文字错乱 / 旗帜图案闪烁"），同时每次 evict 都让
    // entityPbrSettled[victim]=false ⇒ 下一次命中重跑 publishEntityPbrTexture() 的
    // JNI 重推（约 130ms/帧的渲染线程 CPU）。扩容后文字/实体贴图同时驻留，slotFor
    // 走命中路径，两个症状一起消失。native 侧同名的常量与描述符池已同步。
    private static final int MAX_TEXTURE_SLOTS = 64;
    // 最后一格留给本地玩家身体代理（它用 MAX_TEXTURE_SLOTS 当自己的槽号，
    // 见 publishProxyTexture），普通实体纹理只用 1..31 —— 否则第 32 个纹理
    // 会把代理的皮肤顶掉，主光线看到的自己就成了别人。
    private static final int MAX_REGULAR_TEXTURE_SLOTS = MAX_TEXTURE_SLOTS - 1;
    // Match the pre-existing global RT vertex ABI: 12 floats / 48 bytes.
    // pad2 carries the entity texture slot; translucent shader modules retain
    // their original compatible layout.
    private static final int BYTES_PER_VERTEX = 12 * Float.BYTES;
    private static final Identifier EXPERIENCE_ORB_TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/experience/experience_orb.png");
    // 2026-10-21 闪电：RenderTypes.lightning() 是纯色管线（没有 Sampler0），
    // 而且原版几何走 addVertex(pose,…)+setColor 链，不经过
    // BufferBuilder.addVertex(FFFIFFIIFFF) 的全格式钩子 ⇒ 通用捕获完全收不到。
    // 这里由 Java 侧合成几何 + 一张 8 列闪电精灵图（每列 64px，512×512）。
    // 贴图来源：OpenGameArt "lightnings" by hdst，CC0 / 无需署名：
    //   https://opengameart.org/content/lightnings
    //   https://opengameart.org/sites/default/files/lighting_0.png
    // 透明像素依赖 rt_test.rahit 的 combinedAlpha < 0.1 裁切，否则会画成整块卡片。
    private static final Identifier LIGHTNING_TEXTURE =
            Identifier.fromNamespaceAndPath("orion", "textures/rt_lightning.png");
    // Entity batches support at most 16 texture slots. A fixed table is both
    // faster at this size and avoids per-model Identifier.toString()/HashMap
    // churn on the render thread.
    //
    // Slots are deliberately persistent across frames. Native code bakes each
    // vertex's slot index (pad2) into the entity BLAS, which is rebuilt on a
    // 2-frame cadence, while the slot -> texture descriptors are published as
    // soon as they change. Per-frame capture-order allocation let slot indices
    // churn faster than the BLAS rebuild, so a moving entity population kept
    // sampling another entity's texture for up to the rebuild interval -- the
    // classic "texture flashes while entities move" artifact. A stable
    // Identifier -> slot table keeps pad2 and the descriptor set in agreement,
    // lets sameVertexPayload dedup go quiet, and turns the per-frame
    // FGDeviceWaitIdle descriptor stall into a rare event.
    private static final Identifier[] frameTextureSlots = new Identifier[MAX_TEXTURE_SLOTS];
    private static final String[] frameTextureLabels = new String[MAX_TEXTURE_SLOTS];
    private static int frameTextureSlotCount;
    private static final int[] slotLastSeenFrame = new int[MAX_TEXTURE_SLOTS];
    // Material class per slot, so armour can carry a real F0/roughness instead of
    // the flat entity response. It rides in the fractional part of the pad2
    // texture-slot value (slot + class / 32) because the 48-byte vertex ABI has
    // no spare field: materialId (index 9) belongs to the translucent glass
    // profile logic and pad1 (index 10) is the proxy/emissive marker. Every
    // existing reader recovers the slot with round() and only positive entity
    // slots receive a fraction, so slot 0 still means "no entity texture".
    private static final float MATERIAL_CLASS_SCALE = 32.0f;
    private static final int MATERIAL_CLASS_DEFAULT = 0;
    private static final int MATERIAL_CLASS_IRON = 1;
    private static final int MATERIAL_CLASS_GOLD = 2;
    private static final int MATERIAL_CLASS_DIAMOND = 3;
    private static final int MATERIAL_CLASS_NETHERITE = 4;
    private static final int MATERIAL_CLASS_COPPER = 5;
    private static final int MATERIAL_CLASS_SOFT = 6;
    /**
     * 2026-10-06 类 7 = <b>不裁切</b>。给"大片透明但必须整体可见"的贴图用 ——
     * 目前只有末地传送门的星幕（{@code end_portal.png}）。
     *
     * <p>为什么需要它：entity/自定义几何这条路走的是 opaque + alpha cutout，而星幕
     * 绝大部分纹素是透明的 ⇒ 全被判透明丢掉 ⇒ 观感就是"门没渲染"。而实测日志证明
     * 捕获链路全对（geometry captured / 贴图正确 / 槽位 28 / 无丢弃）⇒ 只剩裁切这一环。
     * 消费端见 {@code rt_test.rahit}：类 7 直接放行，不做 alpha 测试。</p>
     *
     * <p>⚠ 这一档必须<b>绕开</b> {@code entityArmorMetal} 开关（{@link #classifyEntityMaterial}
     * 在关闭时直接返回 DEFAULT）—— 否则"末地门能不能看见"会随盔甲设置变化。</p>
     */
    private static final int MATERIAL_CLASS_UNCUTOUT = 7;
    private static final int[] frameTextureClasses = new int[MAX_TEXTURE_SLOTS];
    private static int captureFrameSerial;
    // An evicted slot can still be referenced by the previous BLAS for up to
    // the native rebuild cadence. Wait longer than that before reusing a slot
    // so stale geometry never samples a repurposed texture.
    private static final int SLOT_EVICTION_GRACE_FRAMES = 3;
    // =====================================================================
    // 2026-10-11 旗帜图案闪烁：实体层共面重合 quad 的稳定层序
    //
    // 原版把"底色 quad + 图案 overlay quad"画在**同一平面**上，靠画序 + 深度
    // 测试(LEQUAL) 让后画的图案盖住底色；而 RT 的 TLAS 实例全部双面
    // (FACING_CULL_DISABLE)，两份共面 quad 变成 t 完全相等的命中，谁被提交由
    // 遍历顺序决定 ⇒ 逐像素/逐帧随机显示底色或图案 —— 用户报告的"旗帜上图案
    // 会闪"。这与块网格 cutout 层沿法线外扩 2mm 是同一类竞态（见
    // VulkanCore.cpp 的 CUTOUT_NORMAL_OFFSET）。
    //
    // 修法：同一批次内凡是**质心重合**（量化到 1/256 格内）的 quad，按出现
    // 顺序沿自身几何法线递增外移 dup × STEP —— 底色(先画)不动，图案(后画)
    // 依次外移，平局变成确定性结果（图案恒在底色之前）。非重合 quad 一个
    // 字节都不动，普通实体模型完全不受影响。量化键只覆盖本批次，因此必须
    // 底色与图案落在同一 submit（26.3 的 banner_patterns 图集把两者并到一个
    // RenderType，正是为此）。
    //
    // 2026-10-12：这条只作用于**模型**批次（ActiveBatch.coplanarLayering，由
    // acquireBatch 置位）。文字批次共用同一个 capture()，而字形 quad 只有
    // 0.05~0.1 格，STEP/MAX 这套按方块尺寸定的外移量对它是几何尺寸的几分之一，
    // 会把字形推离自己的阴影面；文字也不存在"底色 + overlay"这类共面重合。
    // =====================================================================
    private static final float ENTITY_COPLANAR_LAYER_STEP = 0.002f;
    private static final float ENTITY_COPLANAR_LAYER_MAX = 0.02f;
    private static final long[] publishedEntityViews = new long[MAX_TEXTURE_SLOTS];
    private static final long[] publishedEntitySamplers = new long[MAX_TEXTURE_SLOTS];
    // Per-slot latch: set once a slot has a real (non-zero) Vulkan view+sampler
    // pair published.  slotFor no longer re-resolves the texture through the
    // TextureManager every frame for latched slots; the retry-until-view-exists
    // semantics are preserved because the latch only turns on after a
    // successful publish, and a resource reload clears it wholesale.
    private static final boolean[] slotTexturePublished = new boolean[MAX_TEXTURE_SLOTS];
    // =====================================================================
    // 2026-10-09 实体 LabPBR（`_n`/`_s`）
    //
    // 与上面的 albedo 槽一一对应：Java 按"同一 Identifier 加 `_n`/`_s`"在
    // ResourceManager 里查表（缓存，不逐帧扫资源包），命中就取该贴图的 MC Vulkan
    // 句柄推给 binding 63/64；没命中就推 0，native 侧绑回中性 1×1 兜底。
    //
    // 0 = "这一槽没有该贴图"（也等于"尚未推送"）——与 native 的初值一致，所以
    // 逐槽去重用的就是这四个 long 数组本身。
    // =====================================================================
    private static final java.util.HashMap<Identifier, Identifier[]> ENTITY_PBR_SIBLINGS =
            new java.util.HashMap<>();
    private static final long[] publishedEntityNormalViews = new long[MAX_TEXTURE_SLOTS];
    private static final long[] publishedEntityNormalSamplers = new long[MAX_TEXTURE_SLOTS];
    private static final long[] publishedEntitySpecularViews = new long[MAX_TEXTURE_SLOTS];
    private static final long[] publishedEntitySpecularSamplers = new long[MAX_TEXTURE_SLOTS];
    // 解析 + 推送是否已完成（含"确认没有 `_n`/`_s`"这一结论）。false 时 slotFor
    // 每帧重试一次；贴图视图是异步加载的，所以"有文件但视图还是 0"必须能重来。
    private static final boolean[] entityPbrSettled = new boolean[MAX_TEXTURE_SLOTS];
    // 老 DLL 没有这个导出：一旦 UnsatisfiedLinkError 就永久关掉这条路，避免每帧抛。
    private static boolean entityPbrNativeAvailable = true;
    private static final ThreadLocal<ActiveBatch> activeBatch = new ThreadLocal<>();
    private static final ThreadLocal<ActiveBatch> reusableBatch =
            ThreadLocal.withInitial(ActiveBatch::new);
    // Feature preparation is deferred in 26.2.  Keep a small identity window
    // of synthetic states instead of relying on a frame-local boolean or the
    // AvatarRenderState id field, either of which can be stale by the time
    // ModelFeatureRenderer receives the submit.
    private static final Set<Object> localPlayerProxyStates =
            Collections.newSetFromMap(new IdentityHashMap<>());
    // Fallback for render-state copies made by the deferred feature pipeline.
    // In first person the local player is absent from vanilla's entity list,
    // so the only Avatar submit armed here is the synthetic RT proxy.
    private static boolean localPlayerProxyAvatarPending;
    private static Identifier localPlayerProxyBodyTexture;
    // 26.3 把告示牌/名牌文字交给 SubmitNodeCollector.submitText → TextFeatureRenderer，
    // 不是 ModelFeatureRenderer 的模型提交，所以模型捕获路径收不到它；而原版光栅
    // 世界输出整帧被原生交换链替换掉，文字就彻底消失。这里在字形真正发射顶点时
    // 把同一批顶点录进实体批次，字体图集按普通实体纹理槽发布，复用现有的
    // alpha cutout 与逐顶点着色，不需要任何原生/着色器改动。
    //
    // 文本走**独立的** ThreadLocal 批次，不碰 activeBatch：字形镜像期间原版仍会
    // 往它自己的 BufferBuilder 里画同一批字，那些顶点经由 BufferBuilderMixin 会撞见
    // 一个打开的 activeBatch，被重复录进来；而且 captureExperienceOrb 把
    // "activeBatch 非空" 当嵌套错误抛异常。分开之后 captureVertex 看到的仍是
    // activeBatch==null，与改动前完全一致。
    private static final ThreadLocal<ActiveBatch> textBatch = ThreadLocal.withInitial(ActiveBatch::new);
    private static int textBatchSlot;
    private static boolean captureFrameActive;

    // =====================================================
    // 2026-10-03 文字捕获成本读数（先量再改）
    //
    // 告示牌/名牌文字是**每帧**从零重抓的：每个字形走一次
    // TextRenderable.render（PlainTextRenderable 是"阴影 sprite + 主体 sprite"
    // 两个 quad），顶点经 CapturingVertexConsumer 逐顶点写进批次，再整批经
    // uploadEntityBatch 送进 native。原版侧的这次渲染对画面是纯浪费（世界输出已
    // 被原生交换链替换），所以这里量的就是"镜像这份文字要付多少"。
    //
    // 取样策略：只在 isDiagnosticFrame()（每 64 帧一帧）做 nanoTime 计时，其余帧
    // 只累加整数计数 —— 每字形两次 nanoTime × 上千字形会自己变成被测对象。
    // 打印在 endFrame()，与 [RTX-PROXY] 那套诊断同频。
    // =====================================================
    // 2026-10-03 逐字形查表去重。
    //
    // 同一段文字（一个告示牌界面 / 一条名牌）的连续字形共用同一个 RenderType，
    // 而 slotFor() 对每个字形都要重做三件与字形无关的事：
    //   ① resolveSampler0：RenderSetup 的 textures Map 里取 "Sampler0"；
    //   ② classifyEntityMaterial（经 assignTextureClass）：对纹理**路径**做若干次
    //      String.contains（gold / copper / iron / chainmail …）；
    //   ③ publishTexture：TextureManager.getTexture（Identifier 哈希 + 查表）+ 取
    //      view/sampler。
    // 一帧上千字形时这就是上千次重复。这里缓存"上一个 RenderType → slot"。
    //
    // ⚠ 必须**每帧清空**：slotFor 里的 slotLastSeenFrame 刷新与 publishTexture 的
    //   "每帧重试一次（Vulkan view 可能上一帧还是 0）"语义都要求每帧至少真走一次。
    //   只比 RenderType 身份，命中失败只是退回原路径，不会错。
    private static RenderType lastGlyphRenderType;
    private static int lastGlyphSlot;

    private static boolean textPerfFrame;
    private static long textCaptureNanos;
    private static long textUploadNanos;
    // 2026-10-13：查表 + 贴图发布（slotFor/publishTexture）的耗时。原先这段没有
    // 计时，正是它让 130ms 在 [TEXT PERF] 里看不见（见 captureTextGlyph 的说明）。
    private static long textLookupNanos;
    private static int textGlyphs;
    private static int textVertices;
    private static int textBytes;
    private static int textUploads;
    // 2026-10-13：**原版文字路径**的墙钟。做法是累加"相邻两次逐字形回调之间的间隔"——
    // 我们的钩子是被原版每个字形各调一次的，所以这些间隔之和≈原版 Text 这一遍的总耗时
    // （含它自己的顶点发射与两次回调之间的任何工作）。
    //
    // 为什么必须要它：原版 profiler 只给一个不可再分的叶子 `Text - 91.37%/74.44%`
    // （见 debug\profiling\2026-10-13_21_44_54-...zip 的 client/profiling.txt），
    // 而这一遍里**包含我们自己的钩子**（captureUs 只有 ~0.9ms），所以需要把
    // "摊在字形之间"与"在进入字形循环之前"分开：
    //   · textPathUs ≈ 原版 Text 总耗时 ⇒ 成本摊在每个字形上（逐字形发射/绘制）；
    //   · textPathUs ≪ 原版 Text 总耗时 ⇒ 成本在循环之外（文本布局/测量），
    //     那么"取消原版逐字形发射"这类做法救不了它。
    private static long textEmitNanos;
    private static long textGapNanos;
    private static long lastGlyphHeadNanos;
    private static long lastGlyphTailNanos;

    /**
     * 2026-10-13：是否**跳过原版世界文字的顶点发射**（默认关，用
     * {@code ORION_SKIP_VANILLA_WORLD_TEXT=1} 打开）。
     *
     * <p>依据：原版 {@code acceptRenderable} 造出来的那份顶点，在 RT 模式下是
     * 整帧被替换掉的（见 TextGlyphRendererMixin 的说明），而我们已经用
     * {@link #captureTextGlyph} 把同一批字形镜像进实体批次。</p>
     *
     * <p><b>实测依据（2026-10-13 21:51，城市图招牌区）</b>：把这一遍切成"原版函数体内"
     * 与"两次字形之间的间隙"两个分项后 ——</p>
     * <pre>
     *   glyphs=6027  textEmitUs=89623  textGapUs=3363  textPathUs=92987
     *   glyphs=5034  textEmitUs=63906  textGapUs=2558  textPathUs=66464
     *   glyphs=2301  textEmitUs=15126  textGapUs=1541  textPathUs=16668
     * </pre>
     * <p>原版函数体占 96%，间隙只有 1.5~3.4ms；同期原版 profiler 报 {@code Text} = 整帧
     * 76.24%，而我们自己的分项合计只有 2.3ms ⇒ 这一遍几乎全是"造一份马上被整帧替换掉的
     * 顶点"。所以默认**打开**：镜像成功就取消原版那一遍；镜像失败的路径不取消，原版照旧画。</p>
     *
     * <p>只对世界文字生效：GUI/HUD 文字不在本帧捕获窗口内（{@code captureFrameActive}
     * 为假），一律不取消。{@code ORION_SKIP_VANILLA_WORLD_TEXT=0} 可一键回退。</p>
     */
    public static final boolean SKIP_VANILLA_WORLD_TEXT = resolveSkipVanillaWorldText();

    private static boolean resolveSkipVanillaWorldText() {
        String raw = System.getProperty("orion.skipVanillaWorldText");
        if (raw == null) {
            raw = System.getenv("ORION_SKIP_VANILLA_WORLD_TEXT");
        }
        // 默认打开；显式 =0 才关。
        return raw == null || !"0".equalsIgnoreCase(raw.trim());
    }

    /** 相邻两次逐字形回调之间的墙钟累计（见 captureTextGlyph 顶部的说明）。 */
    private static long textPathNanos;
    private static long lastGlyphEntryNanos;

    // 2026-10-13：串图（用户报的"物品展示框里的物品贴图错乱"）做成**累计**计数。
    // 原有的 evictionsInUse 每帧清零、且只在被 64 帧门控的诊断帧才打印 ⇒ 只看得到
    // 1/64 的帧，正好可能漏掉串图发生的那一刻。这个总数永不清零：只要它涨过 0，
    // 就证明"slotFor 抢占了本帧已经用过的槽"这条路径真的被走到过。
    private static long evictionsInUseTotal;

    // 2026-10-12 判"文字翻槽"是哪一种：每字形真的对应不同纹理，还是 31 槽被打满后
    // slotFor 的 LRU 抢占把本帧已用的槽换了人（后者会让本帧早先上传的几何串到别的
    // 贴图上 = 用户报的旗帜/文字"贴图错乱"）。两者修法相反，必须先分开。
    //   · textDistinctTextures = 本帧文字实际解析出多少种 Sampler0；
    //   · textSlotChanges      = 本帧文字批次因 textureSlot 变化而 flush 的次数；
    //   · evictionsInUse       = 本帧抢占"本帧已经用过的槽"的次数（唯一会串图的那条）。
    private static int textSlotChanges;
    private static int evictionsInUse;
    private static final java.util.HashSet<Identifier> textFrameTextures =
            new java.util.HashSet<>();

    private EntityRtCapture() {}

    /** 本帧的实体捕获窗口是否已经打开（{@link #beginEarlyFrame()} 的幂等依据）。 */
    private static boolean entityFrameOpen;

    /**
     * 每帧复位开窗标记，由 {@code GameRendererMixin} 在 {@code GameRenderer.render}
     * 的 HEAD 调用。它只清 Java 侧标记，不碰原生状态：窗口现在可能由手部提前打开、
     * 由 {@code LevelRenderer} 收帧，中间若因为关卡未渲染而缺了收帧那一步，标记会一直
     * 挂着 —— 那会导致之后手部再也不开窗（手又消失）。这里保证每帧重新开始。
     */
    public static void resetEntityFrameOpen() {
        entityFrameOpen = false;
    }

    /**
     * 2026-10-06：把实体帧窗口提前到"第一人称手部/手持物提交之前"，并且**幂等**。
     *
     * <p>26.3 的 {@code GameRenderer.render} 里，手部是
     * {@code FirstPersonHandsAndItemsRenderer.submitHandsWithItems(...)} 提交后**立刻**
     * 由 {@code renderAllFeatures} 落进顶点缓冲的，位置在世界渲染（{@code renderLevel}）
     * <b>之前</b>。而 mod 的窗口一直开在 {@code LevelRenderer.render} 的 HEAD 里
     * ⇒ 手的顶点全部落在窗口之外，被 {@code captureVertex} 静默丢弃 —— 这就是
     * "第一人称手部一直不渲染"。</p>
     *
     * <p>这里只是把开口提前；`LevelRenderer` 那一处改为同样调用本方法，于是两条
     * 路径都安全：手部存在时由手先开，第三人称/无手时由世界渲染开。原生的
     * {@code beginEntityFrame()}（清空顶点数组）必须**每帧恰好一次**，所以由这里的
     * 布尔量把关。</p>
     */
    public static void beginEarlyFrame() {
        if (entityFrameOpen) {
            return;
        }
        // 2026-10-09 运动矢量：帧序号推进（配对表按它判"上一帧"），并定期清理
        // 太久没出现的记录（见 entityMotionTrackers / pruneEntityMotionTrackers）。
        // 这里每帧**恰好一次**（上面的 entityFrameOpen 把着关），正是帧序号该在的位置。
        ++entityMotionFrame;
        if (--entityMotionLogCountdown <= 0) {
            entityMotionLogCountdown = 600;
            pruneEntityMotionTrackers();
        }
        beginFrame();
        NativeVulkan.beginEntityFrame();
    }

    /** 第一人称手部/手持物的提交阶段（见 {@link #beginEarlyFrame()}）。 */
    private static boolean handPhase;

    /**
     * 进入手部阶段：开窗 + 标记"这一段提交的是手"。
     *
     * <p>标记的用途只有一个：让 {@link #isLocalPlayerProxyState} 不要把手判成
     * 本地玩家身体代理（那会落到主相机看不见的 LOCAL_PLAYER 层）。</p>
     */
    public static void beginHandPhase() {
        if (!handPhaseLogged) {
            handPhaseLogged = true;
            System.out.println("[RTX-HAND] first-person hand capture window opened");
        }
        handPhase = true;
        beginEarlyFrame();
    }

    private static boolean handPhaseLogged;
    private static boolean endPortalLogged;
    /** 末地门诊断：上传出口只打一次（证明顶点真的进了批次）。 */
    private static boolean endPortalBatchLogged;
    /** 末地门自合成路线的探针（一次性）。 */
    private static boolean endPortalSyntheticLogged;
    private static boolean endPortalSyntheticSlotLogged;
    // 2026-10-21 闪电/信标是新增的合成路径，本机无法起游戏 ⇒ 各留一条一次性探针，
    // 用户实机日志里能直接区分"没走到 / 贴图槽没拿到 / 已上传"。
    private static boolean lightningSyntheticLogged;
    private static boolean lightningSyntheticSlotLogged;
    private static boolean beaconSyntheticLogged;
    private static boolean beaconSyntheticSlotLogged;

    /** 手部阶段结束（世界渲染开始，由 {@code LevelRendererMixin} 调用）。 */
    public static void endHandPhase() {
        handPhase = false;
    }

    public static void beginFrame() {
        // 2026-10-03 实体可见性剔除：每帧驱动一次（内部按 tick 自节流）。
        com.example.client.cull.EntityVisibilityCuller.tick();
        if (activeBatch.get() != null) throw new IllegalStateException("Entity model capture leaked across frames");
        ++captureFrameSerial;
        entityFrameOpen = true;
        // 文字是纯外观：万一上一帧在异常里中断，静默丢弃比抛异常好。
        textBatchSlot = 0;
        captureFrameActive = true;

        // 文字读数（见 textPerfFrame 的注释）：本帧是否采样 + 清计数。
        textPerfFrame = isDiagnosticFrame();
        // 逐字形查表缓存必须每帧失效（见 lastGlyphRenderType 的注释）。
        lastGlyphRenderType = null;
        lastGlyphSlot = 0;
        textCaptureNanos = 0L;
        textUploadNanos = 0L;
        textLookupNanos = 0L;
        textGlyphs = 0;
        textVertices = 0;
        textBytes = 0;
        textUploads = 0;
        textSlotChanges = 0;
        evictionsInUse = 0;
        textPathNanos = 0L;
        lastGlyphEntryNanos = 0L;
        textFrameTextures.clear();
    }

    /**
     * Closes the frame. The text run is the one batch that is not bounded by a
     * single model submit, so it is flushed here rather than in endModel's
     * caller. Must run before {@code NativeVulkan.endEntityFrame()}.
     */
    public static void endFrame() {
        captureFrameActive = false;
        entityFrameOpen = false;
        flushTextBatch();

        // =====================================================
        // 2026-10-03 文字捕获读数（每 64 帧一行，见 textPerfFrame）。
        //
        // 读法：
        //   glyphs/verts/bytes = 本帧镜像了多少文字几何（bytes 就是要过 JNI 的量）；
        //   captureUs          = 逐字形重抓（render + 逐顶点写入）占用的渲染线程时间；
        //   uploadUs/uploads   = 整批 JNI 上传（uploadEntityBatch，含 native memcmp）。
        // 若 captureUs 可观，下一步才值得做批次复用；若只有 uploadUs 大，那说明
        // 应该合并/降频上传而不是改抓取。
        // =====================================================
        if (textPerfFrame && (textGlyphs != 0 || textUploads != 0)) {
            System.out.println(
                    "[TEXT PERF]"
                            + " glyphs=" + textGlyphs
                            + " verts=" + textVertices
                            + " bytes=" + textBytes
                            + " uploads=" + textUploads
                            + " lookupUs=" + (textLookupNanos / 1000L)
                            + " captureUs=" + (textCaptureNanos / 1000L)
                            + " uploadUs=" + (textUploadNanos / 1000L)
                            // 原版 Text 这一遍的墙钟（相邻字形回调之间的间隔之和）。
                            // 与同期的原版 profiler Text 节点对账：两者接近 ⇒ 这一遍
                            // 就是热点。跳过原版发射（SKIP_VANILLA_WORLD_TEXT=1）后
                            // 这个数应当塌到几毫秒。
                            + " textPathUs=" + (textPathNanos / 1000L)
                            // 判翻槽成因（见 textSlotChanges 的声明）：
                            //   tex 小(2~3) 而 changes 大 ⇒ 真的在两张表之间交替，
                            //     属于分批问题，抢占无关；
                            //   tex 大 / tableSize 顶到 MAX_REGULAR_TEXTURE_SLOTS 且
                            //     evictInUse>0 ⇒ 槽被打满后本帧抢占，串图就是它。
                            // evictInUseTotal 是永不清零的累计值：>0 即"串图那条路
                            // 真的被走到过"（用户报的物品展示框贴图错乱就是它）。
                            + " tex=" + textFrameTextures.size()
                            + " changes=" + textSlotChanges
                            + " evictInUse=" + evictionsInUse
                            + " evictInUseTotal=" + evictionsInUseTotal
                            + " tableSize=" + frameTextureSlotCount
                            // 2026-10-13：关档时 glyphs 仍有数、其余分项恒 0 —— 这是
                            // "开关生效且此刻确实在招牌区"的判据（见 TEXT_CAPTURE_ENABLED）。
                            + " disabled=" + (TEXT_CAPTURE_ENABLED ? 0 : 1));
        }
    }

    /**
     * 2026-10-13 归因开关：{@code ORION_TEXT_CAPTURE=0}（或
     * {@code -Dorion.textCapture=0}）直接跳过文字捕获。
     *
     * <p>为什么需要：城市图（Broville v11.4.4）里走进招牌密集区时实测帧时间从
     * 24ms 涨到 92ms（[PERF L10 CPU] avg，41fps → 10.8fps，单帧最大 503ms），而
     * 同一窗口内 [TEXT PERF] 的 glyphs 从 256 涨到 5066/帧、changes 从 152 涨到
     * 3357/帧，但我们自己的分项 lookup+capture+upload 只有 2.5ms、C++ work 只有
     * 12ms —— 那 ~55ms 落在我们**所有仪器之外**。这个开关给出唯一干净的二分：</p>
     * <ul>
     *   <li>关掉后帧时间掉回 ~24ms ⇒ 成本是**我们的捕获**（那就优化捕获：分批/降频）；</li>
     *   <li>关掉后仍是 ~90ms ⇒ 成本是**原版自己的 Text 渲染**（那我们只能减少镜像量
     *       或不镜像文字，优化捕获无用）。</li>
     * </ul>
     * <p>只在排查时用：关掉后招牌文字不再进光追（画面里看不到招牌字）。</p>
     */
    private static final boolean TEXT_CAPTURE_ENABLED = resolveTextCaptureEnabled();

    private static boolean resolveTextCaptureEnabled() {
        String raw = System.getProperty("orion.textCapture");
        if (raw == null) {
            raw = System.getenv("ORION_TEXT_CAPTURE");
        }
        return raw == null || !"0".equalsIgnoreCase(raw.trim());
    }

    /**
     * Mirrors one glyph quad emitted by TextFeatureRenderer into the entity batch.
     *
     * @return true = 本字形**已经**镜像进实体批次，原版那一份可以被跳过（见
     *         {@link #SKIP_VANILLA_WORLD_TEXT}）；false = 没有镜像成功，原版必须照旧画。
     */
    public static boolean captureTextGlyph(
            TextRenderable glyph,
            Matrix4f pose,
            int lightCoords,
            Font.DisplayMode displayMode) {
        if (!captureFrameActive) return false;

        // 2026-10-13：原版 Text 这一遍的墙钟（相邻两次逐字形回调之间的间隔之和）。
        // 放在**关档早退之前**，所以两个臂都能量到。只在诊断帧做（每 64 帧一次），
        // 稳态零开销。见 Log 里 textPathUs 的读法。
        if (textPerfFrame) {
            final long glyphEntryNanos = System.nanoTime();
            if (lastGlyphEntryNanos != 0L) {
                textPathNanos += glyphEntryNanos - lastGlyphEntryNanos;
            }
            lastGlyphEntryNanos = glyphEntryNanos;
        }

        if (!TEXT_CAPTURE_ENABLED) {
            // 关档时**仍然数字形**：第一次 textcap0 A/B（2026-10-13 21:33 那局）就是
            // 因为连"人在不在招牌区、招牌多密"都读不出来而作废的 —— 只有帧时间没有
            // 场景密度，读不出任何结论。计数是 O(1)，不碰槽表、不碰批次、不碰 JNI，
            // 不会污染这一臂的帧时间。返回 false：关档时原版必须照旧画。
            textGlyphs += 1;
            return false;
        }

        RenderType renderType = glyph.renderType(displayMode);
        if (renderType == null) return false;

        int textureSlot;

        if (renderType == lastGlyphRenderType) {
            // 同一段文字里第 2..N 个字形：查表结果与发布状态本帧已经算过（见
            // lastGlyphRenderType 的注释），直接复用。
            textureSlot = lastGlyphSlot;
        } else {
            // 2026-10-13：这一段**必须**计时。原注释把它记成"廉价路径，不单列"，
            // 结果就是 2026-10-13 那次城市地图排查里，[TEXT PERF] 只报
            // captureUs 1.5ms 而原版 profiler 报 Text 占整帧 85.6% —— 差额全在
            // 这里：slotFor() 的 evict 分支会调 publishTexture() ⇒
            // publishEntityPbrTexture() ⇒ NativeVulkan.updateEntityPbrTexture()
            // （JNI + native 重推 binding 63/64）。实测 evictInUse=2736/帧。
            final long textLookupStartNanos = textPerfFrame ? System.nanoTime() : 0L;

            Identifier texture = resolveSampler0(renderType);
            if (texture == null) return false;

            textureSlot = slotFor(texture);
            if (textureSlot == 0) return false;

            if (textPerfFrame) {
                textLookupNanos += System.nanoTime() - textLookupStartNanos;
                textFrameTextures.add(texture);
            }

            lastGlyphRenderType = renderType;
            lastGlyphSlot = textureSlot;
        }

        // A font's glyphs can span several sheets (256 glyphs each), so the
        // atlas changes mid-run; flush and reopen on a slot change.
        ActiveBatch batch = textBatch.get();
        if (textBatchSlot != 0 && textureSlot != textBatchSlot) {
            if (textPerfFrame) ++textSlotChanges;
            flushTextBatch();
        }
        if (textBatchSlot == 0) {
            batch.reset(textureSlot, false, false);
            textBatchSlot = textureSlot;
        }

        // 发射计时与上面的查表/发布计时分开：captureUs 只含"重抓顶点"，
        // lookupUs 含 resolveSampler0 + slotFor（含 evict 时的 publishTexture）。
        // 两者分开才能一眼看出贵在"查表+发布"还是贵在"重抓顶点"。
        final long textStartNanos = textPerfFrame ? System.nanoTime() : 0L;
        final int textVerticesBefore = batch.vertexCount;

        CapturingVertexConsumer consumer = batch.captureConsumer();
        try {
            // Same call vanilla makes, minus its own (discarded) vertex builder.
            glyph.render(pose, consumer, lightCoords, false);
        } finally {
            consumer.finish();
        }

        textGlyphs += 1;
        textVertices += batch.vertexCount - textVerticesBefore;

        if (textPerfFrame) {
            textCaptureNanos += System.nanoTime() - textStartNanos;
        }

        // 镜像成功 ⇒ 调用方可以跳过原版那一份（内容不变：RT 那份在）。
        return true;
    }

    private static void flushTextBatch() {
        if (textBatchSlot == 0) return;
        textBatchSlot = 0;
        ActiveBatch batch = textBatch.get();
        if (batch.vertexCount != 0) {
            final long textUploadStartNanos = textPerfFrame ? System.nanoTime() : 0L;

            uploadBatch(batch);

            if (textPerfFrame) {
                textUploadNanos += System.nanoTime() - textUploadStartNanos;
            }

            textUploads += 1;
            textBytes += batch.vertexCount * BYTES_PER_VERTEX;
        }
    }

    public static boolean isDiagnosticFrame() {
        return (captureFrameSerial & 63) == 1;
    }

    /**
     * A resource reload destroys Vulkan texture views while this class keeps
     * lightweight handle de-duplication state. The numeric handle may be
     * reused by Vulkan, so comparing it across reloads is not valid.
     */
    public static void invalidateTextureCache() {
        frameTextureSlotCount = 0;
        Arrays.fill(frameTextureSlots, null);
        Arrays.fill(frameTextureLabels, null);
        Arrays.fill(slotLastSeenFrame, -1);
        Arrays.fill(publishedEntityViews, 0L);
        Arrays.fill(publishedEntitySamplers, 0L);
        Arrays.fill(slotTexturePublished, false);
        // 实体 LabPBR：资源包换过 ⇒ `_n`/`_s` 的解析结论可能全变，连同句柄去重
        // 状态一起清掉，下一帧重新解析、重新推送（native 侧槽位保持不变，但旧句柄
        // 可能已被复用 ⇒ 必须重推）。
        ENTITY_PBR_SIBLINGS.clear();
        Arrays.fill(publishedEntityNormalViews, 0L);
        Arrays.fill(publishedEntityNormalSamplers, 0L);
        Arrays.fill(publishedEntitySpecularViews, 0L);
        Arrays.fill(publishedEntitySpecularSamplers, 0L);
        Arrays.fill(entityPbrSettled, false);
    }

    public static void beginModel(ModelFeatureRenderer.Submit<?> submit) {
        beginModel(submit, false);
    }

    public static void markLocalPlayerProxyState(Object state) {
        if (localPlayerProxyStates.size() >= 8) localPlayerProxyStates.clear();
        localPlayerProxyStates.add(state);
        // 不依赖 skin 填充时机:26.3 的皮肤可能走 PlayerSkinRenderCache,
        // extract 阶段 skin 未必就绪;id 回退只需要状态是 Avatar。
        localPlayerProxyAvatarPending = state instanceof AvatarRenderState;
        localPlayerProxyBodyTexture = null;
        if (state instanceof AvatarRenderState avatarState && avatarState.skin != null) {
            localPlayerProxyBodyTexture = avatarState.skin.body().texturePath();
        }
    }

    public static void disarmLocalPlayerProxyFallback() {
        localPlayerProxyAvatarPending = false;
        localPlayerProxyBodyTexture = null;
    }

    public static boolean isLocalPlayerProxyState(ModelFeatureRenderer.Submit<?> submit) {
        // ⚠ 2026-10-06 手部阶段绝不能当成"身体代理"。
        // 第一人称手/手持物在 GameRenderer 里**先于**世界渲染提交，而身体代理的
        // "armed" 标记（localPlayerProxyAvatarPending）是上一帧 submitEntities 留下的
        // ⇒ 手的提交会命中 id 回退，被判成代理、进 LOCAL_PLAYER 层（主相机 mask 0x02
        // 看不到）⇒ 手永远不显示。手部阶段直接放行为普通实体。
        if (handPhase) return false;
        if (localPlayerProxyStates.contains(submit.state())) return true;
        if (!localPlayerProxyAvatarPending) return false;
        // 延迟特性管线会拷贝渲染状态,身份集合失效时退回实体 id 匹配:
        // id 是拷贝后仍保留的稳定字段。旧版曾用 skin 身体贴图匹配,但 26.3
        // 的皮肤走 PlayerSkinRenderCache,Sampler0 的 location 与
        // skin.body().texturePath() 不再相等,回退必然 miss —— 这正是
        // 玩家模型漏进普通实体层、主光线打到脸的原因。
        var player = Minecraft.getInstance().player;
        boolean idHit = player != null
                && submit.state() instanceof AvatarRenderState avatarState
                && avatarState.id == player.getId();
        if (isDiagnosticFrame() && idHit) {
            System.err.println("[RTX-PROXY] proxy recognized via id fallback (state copy)");
        }
        return idHit;
    }

    public static boolean isLocalPlayerProxyState(Object renderState) {
        return localPlayerProxyStates.contains(renderState);
    }

    /** Captures the local-player body before it reaches SubmitNodeCollector. */
    public static <S> void captureLocalPlayerModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor) {
        Identifier texture = resolveSampler0(renderType);
        if (texture == null) {
            if (isDiagnosticFrame()) {
                System.err.println("[RTX-PROXY] captureLocalPlayerModel sampler0 resolve FAILED");
            }
            return;
        }

        publishTexture(MAX_TEXTURE_SLOTS, texture);
        ActiveBatch batch = acquireBatch(MAX_TEXTURE_SLOTS, true, false);
        activeBatch.set(batch);
        CapturingVertexConsumer captureConsumer = batch.captureConsumer();
        try {
            model.setupAnim(state);
            model.renderToBuffer(poseStack, captureConsumer, lightCoords, overlayCoords, tintedColor);
        } finally {
            captureConsumer.finish();
            endModel();
        }
    }

    public static void captureLocalPlayerProxy(
            ModelFeatureRenderer.Submit<?> submit, PoseStack poseStack) {
        beginModel(submit, true);
        ActiveBatch batch = activeBatch.get();
        if (batch == null) return;

        CapturingVertexConsumer captureConsumer = batch.captureConsumer();
        try {
            renderModelToCaptureConsumer(submit, poseStack, captureConsumer);
        } finally {
            captureConsumer.finish();
            endModel();
        }
    }

    private static <S> void renderModelToCaptureConsumer(
            ModelFeatureRenderer.Submit<S> submit,
            PoseStack poseStack,
            VertexConsumer captureConsumer) {
        Model<? super S> model = submit.model();
        model.setupAnim(submit.state());
        model.renderToBuffer(
                poseStack,
                captureConsumer,
                submit.lightCoords(),
                submit.overlayCoords(),
                submit.tintedColor()
        );
    }

    // =====================================================
    // 2026-10-09 运动矢量（DLSS-RR）：实体层的"上一帧世界位置"。
    //
    // ★ 用什么做身份：javap 实测 `EntityRenderState`（基类）**没有 id** —— 它的
    //   字段只有 entityType / x / y / z / ageInTicks / …；`id` 只出现在
    //   `AvatarRenderState`（玩家）上。所以身份只能取
    //   **(entityType, 位置就近匹配)**：状态里的 x/y/z 是**插值后的世界位置**，
    //   同一实体相邻两帧必然贴得很近 ⇒ "同类型里离得最近的那条记录"就是它的上一帧。
    //
    // ★ 为什么不读实体的 xo/yo/zo：那个 API 本版本不保证可用（粒子层已经被
    //   `Particle` 没有 getX() 坑过一次），而且口径不同会带来整整一个 tick 的误差
    //   （位移一跳一跳）。直接用渲染状态的位置，与画面**严格同源**。
    //
    // ★ 就近匹配的风险有界：同类型多个实体（一群僵尸）时，万一配错，错的也是
    //   同类型、同一区域、运动相近的另一个 ⇒ 误差不会变成"乱跳的残影"。
    //
    // ★ 内存有界：每条记录 37 个 double；同类型超上限或太久没被匹配到就清理
    //   （见 pruneEntityMotionTrackers）⇒ 不存在"玩久了涨内存"。
    //
    // =====================================================
    // ★★ 2026-10-17：从"整批一个平移常量"升级为"整批一个根位姿增量矩阵" ★★
    //
    // 旧口径的病灶：位移只取 `renderState.x/y/z` 的帧间差 ⇒ **只覆盖根的平移**。
    // 而实体的像素位移有两项来源：
    //   ① 根平移（走路、飞行、掉落）；
    //   ② 根旋转（转身、低头抬头、展示框/船随波摆动）。
    // ② 完全不进 MV ⇒ "原地转身的怪"、"跟着鼠标转的自己身体"拿到 MV=0，
    // 屏幕像素却在动 ⇒ DLSS 按 0 重投影 ⇒ 拖影/抖动。转身时平移恰好为 0，
    // 所以这不是"误差小"，是"整项缺失"。
    //
    // 新口径：存**上一帧的根位姿矩阵**，逐顶点算
    //     p_prev = prevPose · curPose⁻¹ · p_cur
    // 于是 MV 同时含平移与旋转，且对纯平移与旧口径**数值等价**
    // （delta = 纯平移时，逐顶点结果恰好等于那个平移常量）。
    //
    // ⚠ 仍然不建模**骨骼动画**（setupAnim 摆动的四肢、尾巴、披风形变）：
    //   那是逐骨的，需要上一帧的逐顶点位置，本通道拿不到。所以"走路时摆臂"
    //   那一部分依旧没有 MV —— 但它叠在根平移上、且幅度远小于根运动，
    //   比"整项缺失"好得多。详见交付文档「已知限制」。
    // =====================================================
    private static final java.util.HashMap<
            net.minecraft.world.entity.EntityType<?>, java.util.ArrayList<double[]>>
            entityMotionTrackers = new java.util.HashMap<>();

    /** 本帧序号（beginFrame 自增），同时充当记录的"最后匹配帧"。 */
    private static int entityMotionFrame;

    /** 配对容差，平方值。8 格足以覆盖绝大多数帧位移（箭约 3 格/tick），又远小于实体间距。 */
    private static final double ENTITY_MOTION_MATCH_RADIUS_SQ = 64.0;

    /** 超过这么多帧没被匹配到的记录视为"已消失"。 */
    private static final int ENTITY_MOTION_MAX_AGE_FRAMES = 60;

    /** 同一类型保留的记录上限。 */
    private static final int ENTITY_MOTION_MAX_TRACKERS_PER_TYPE = 192;

    private static int entityMotionLogCountdown;

    /**
     * 记录布局（2026-10-17 起，长度 {@link #T_LEN}）：
     * <pre>
     *   0..2   T_X..       本帧位置（世界减相机，与 EntityRenderState.x/y/z 同源）
     *   3      T_FRAME     最后匹配帧
     *   4..6   T_MOTION    该帧的**平移**分量（prev − cur，相机相对）；仅供诊断与匹配
     *   7..18  T_DELTA     该帧的 3×4 位姿增量（**行主序**：r0x r0y r0z tx / r1… / r2…）
     *   19..34 T_POSE      上一帧的根位姿（4×4，JOML 列主序）
     *   35     T_DELTA_VALID  delta 是否可用（0 = 恒等 / 首帧 / 没配上）
     *   36     T_POSE_VALID   T_POSE 是否可用
     * </pre>
     * 为什么增量存"行主序 3×4"而不是直接存矩阵：消费端（{@code uploadBatch}）要按
     * 顶点做一次仿射变换，行主序的 12 个 float 正好就是
     * {@code x' = d0*x + d1*y + d2*z + d3}，没有转置歧义。
     */
    private static final int T_X = 0;
    private static final int T_Y = 1;
    private static final int T_Z = 2;
    private static final int T_FRAME = 3;
    private static final int T_MOTION = 4;
    private static final int T_DELTA = 7;
    private static final int T_POSE = 19;
    private static final int T_DELTA_VALID = 35;
    private static final int T_POSE_VALID = 36;
    private static final int T_LEN = 37;

    /** 复用的 16 float 暂存（JOML {@code get}/{@code set} 的列主序中转）。 */
    private static final ThreadLocal<float[]> poseScratch =
            ThreadLocal.withInitial(() -> new float[16]);

    private static double[] entityMotionTracker(double x, double y, double z) {
        final double[] record = new double[T_LEN];
        record[T_X] = x;
        record[T_Y] = y;
        record[T_Z] = z;
        record[T_FRAME] = entityMotionFrame;
        setIdentityDelta(record);
        return record;
    }

    /** 把增量置成恒等，并标记"不可用"（消费端会退化成"这一批不动"）。 */
    private static void setIdentityDelta(double[] record) {
        record[T_DELTA + 0] = 1.0;
        record[T_DELTA + 1] = 0.0;
        record[T_DELTA + 2] = 0.0;
        record[T_DELTA + 3] = 0.0;
        record[T_DELTA + 4] = 0.0;
        record[T_DELTA + 5] = 1.0;
        record[T_DELTA + 6] = 0.0;
        record[T_DELTA + 7] = 0.0;
        record[T_DELTA + 8] = 0.0;
        record[T_DELTA + 9] = 0.0;
        record[T_DELTA + 10] = 1.0;
        record[T_DELTA + 11] = 0.0;
        record[T_DELTA_VALID] = 0.0;
    }

    /** 记录本帧根位姿，供下一帧算增量。 */
    private static void writePose(double[] record, Matrix4f pose) {
        if (pose == null) {
            record[T_POSE_VALID] = 0.0;
            return;
        }
        final float[] scratch = poseScratch.get();
        pose.get(scratch);
        for (int index = 0; index < 16; ++index) {
            record[T_POSE + index] = scratch[index];
        }
        record[T_POSE_VALID] = 1.0;
    }

    /**
     * delta = prevPose · curPose⁻¹，并写进 {@link #T_DELTA}。
     *
     * <p>为什么乘在左边的是 prevPose：顶点 {@code p} 现在是 {@code curPose · m}
     * （{@code m} = 模型局部坐标），它上一帧在 {@code prevPose · m}。
     * 所以 {@code p_prev = prevPose · curPose⁻¹ · p}，右乘 curPose 的逆把
     * "当前世界位置"先拉回模型局部，再用上一帧的位姿放回上一帧的世界。</p>
     *
     * <p>纯平移时这个式子退化成 {@code p + (t_prev − t_cur)}，与旧口径逐位同量。</p>
     */
    private static void writePoseDelta(double[] record, Matrix4f currentPose) {
        if (currentPose == null || record[T_POSE_VALID] == 0.0) {
            setIdentityDelta(record);
            return;
        }

        final float[] scratch = poseScratch.get();
        for (int index = 0; index < 16; ++index) {
            scratch[index] = (float) record[T_POSE + index];
        }

        final Matrix4f delta = new Matrix4f().set(scratch);
        delta.mul(new Matrix4f(currentPose).invert());

        // JOML 是列主序（mXY = 第 X 列、第 Y 行）⇒ 一行 r0 的三个线性分量是
        // m00 / m10 / m20，平移列是 m30 / m31 / m32。实测见交付文档「自证」。
        record[T_DELTA + 0] = delta.m00();
        record[T_DELTA + 1] = delta.m10();
        record[T_DELTA + 2] = delta.m20();
        record[T_DELTA + 3] = delta.m30();
        record[T_DELTA + 4] = delta.m01();
        record[T_DELTA + 5] = delta.m11();
        record[T_DELTA + 6] = delta.m21();
        record[T_DELTA + 7] = delta.m31();
        record[T_DELTA + 8] = delta.m02();
        record[T_DELTA + 9] = delta.m12();
        record[T_DELTA + 10] = delta.m22();
        record[T_DELTA + 11] = delta.m32();

        record[T_DELTA_VALID] = deltaIsIdentity(record) ? 0.0 : 1.0;
    }

    /**
     * 增量是否等价于恒等。判据刻意留 1e-6 的余量而不是严格相等：位姿矩阵每帧
     * 由 float 运算重建，静止实体也会剩下 1e-7 量级的抖动；不挡掉的话，
     * 每个静止实体每帧都会走"逐顶点算 MV"那条路（白花 CPU，且给 DLSS 喂噪声）。
     */
    private static boolean deltaIsIdentity(double[] record) {
        final double[] identity = {
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0
        };
        for (int index = 0; index < 12; ++index) {
            if (Math.abs(record[T_DELTA + index] - identity[index]) > 1.0e-6) {
                return false;
            }
        }
        return true;
    }

    /** 把记录里的增量搬到批次上（同一实体的多个部件共用第一次算出的结果）。 */
    private static void applyRecordedMotion(ActiveBatch batch, double[] record) {
        for (int index = 0; index < 12; ++index) {
            batch.poseDelta[index] = (float) record[T_DELTA + index];
        }
        batch.hasPoseDelta = record[T_DELTA_VALID] != 0.0;
        batch.motionX = (float) record[T_MOTION];
        batch.motionY = (float) record[T_MOTION + 1];
        batch.motionZ = (float) record[T_MOTION + 2];
    }

    private static double entityMotionDistanceSq(double[] record, double x, double y, double z) {
        final double dx = record[T_X] - x;
        final double dy = record[T_Y] - y;
        final double dz = record[T_Z] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 算出这一批的**根位姿增量**并挂到批次上。
     *
     * <p>世界空间是对的：rgen 里两次投影都用**上一帧**相机，所以"世界位移"进去之后
     * 得到的正是 DLSS 需要的那一项（总位移里属于相机的那一份由 DLSS 自己加）。
     * 相机跟随的物体（本地玩家自己的身体）自动得到"屏幕上不动"的正确结果。</p>
     *
     * <p>2026-10-17：返回值从"一个平移三元组"换成"一个 3×4 仿射增量"，逐顶点在
     * {@code uploadBatch} 里应用 ⇒ 旋转（转身 / 俯仰 / 展示框摆动）终于有 MV 了。
     * 平移口径不变，所以纯平移实体的结果与旧实现**数值等价**。</p>
     */
    private static void resolveBatchMotion(
            ActiveBatch batch, ModelFeatureRenderer.Submit<?> submit) {
        final Object stateObject = submit.state();

        if (!(stateObject
                instanceof net.minecraft.client.renderer.entity.state.EntityRenderState renderState)) {
            return;
        }

        final double x = renderState.x;
        final double y = renderState.y;
        final double z = renderState.z;

        // 本帧根位姿：与顶点同一空间（世界减相机），含平移 + 整体旋转，
        // 不含 setupAnim 的逐骨动画（见类内 tracker 说明与交付文档的已知限制）。
        final com.mojang.blaze3d.vertex.PoseStack.Pose rootPose = submit.pose();
        final Matrix4f currentPose = rootPose != null ? rootPose.pose() : null;

        final java.util.ArrayList<double[]> trackers =
                entityMotionTrackers.computeIfAbsent(
                        renderState.entityType, key -> new java.util.ArrayList<>());

        // ① 先只在"本帧已认领"的记录里找：同一实体一帧内会被提交多次（身体 / 披风 /
        //    盔甲是不同批次），沿用第一次算出的增量，否则第二批判出来是恒等。
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int index = 0; index < trackers.size(); ++index) {
            final double[] record = trackers.get(index);
            if ((int)record[T_FRAME] != entityMotionFrame) {
                continue;
            }
            final double distance = entityMotionDistanceSq(record, x, y, z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }

        if (best != null && bestDistance <= ENTITY_MOTION_MATCH_RADIUS_SQ) {
            applyRecordedMotion(batch, best);
            return;
        }

        // ② 再在"上一帧留下的"记录里找最近的（跳过本帧已认领的）。
        double[] candidate = null;
        double candidateDistance = Double.MAX_VALUE;

        for (int index = 0; index < trackers.size(); ++index) {
            final double[] record = trackers.get(index);
            if ((int)record[T_FRAME] == entityMotionFrame) {
                continue;
            }
            final double distance = entityMotionDistanceSq(record, x, y, z);
            if (distance < candidateDistance) {
                candidateDistance = distance;
                candidate = record;
            }
        }

        if (candidate == null) {
            // 这个类型第一次出现：没有上一帧可配对，增量恒等（首帧本来也没有历史）。
            final double[] record = entityMotionTracker(x, y, z);
            writePose(record, currentPose);
            trackers.add(record);
            return;
        }

        final int age = entityMotionFrame - (int)candidate[T_FRAME];
        final boolean paired =
                age <= ENTITY_MOTION_MAX_AGE_FRAMES
                        && candidateDistance <= ENTITY_MOTION_MATCH_RADIUS_SQ;

        // 平移三元组继续记着（诊断用；匹配仍按位置，不按它）。
        // ⚠ 必须在覆盖 candidate 的位置**之前**算。
        final float motionX = paired ? (float)(candidate[T_X] - x) : 0.0F;
        final float motionY = paired ? (float)(candidate[T_Y] - y) : 0.0F;
        final float motionZ = paired ? (float)(candidate[T_Z] - z) : 0.0F;

        // 增量必须用"上一帧的位姿"和"本帧的位姿"算 —— 而 candidate[T_POSE] 此刻
        // 还正是上一帧那份，所以先算增量、再 writePose 覆盖。
        if (paired) {
            writePoseDelta(candidate, currentPose);
        } else {
            setIdentityDelta(candidate);
        }

        candidate[T_X] = x;
        candidate[T_Y] = y;
        candidate[T_Z] = z;
        candidate[T_FRAME] = entityMotionFrame;
        candidate[T_MOTION + 0] = motionX;
        candidate[T_MOTION + 1] = motionY;
        candidate[T_MOTION + 2] = motionZ;
        writePose(candidate, currentPose);

        applyRecordedMotion(batch, candidate);

        if (trackers.size() > ENTITY_MOTION_MAX_TRACKERS_PER_TYPE) {
            pruneEntityMotionTrackers();
        }
    }

    /** 清掉太久没被匹配到的记录（以及随之变空的类型）。 */
    private static void pruneEntityMotionTrackers() {
        final java.util.Iterator<
                java.util.Map.Entry<
                        net.minecraft.world.entity.EntityType<?>,
                        java.util.ArrayList<double[]>>> types =
                entityMotionTrackers.entrySet().iterator();

        while (types.hasNext()) {
            final java.util.ArrayList<double[]> trackers = types.next().getValue();

            for (int index = trackers.size() - 1; index >= 0; --index) {
                final double[] record = trackers.get(index);
                if (entityMotionFrame - (int)record[T_FRAME] > ENTITY_MOTION_MAX_AGE_FRAMES) {
                    trackers.remove(index);
                }
            }

            if (trackers.isEmpty()) {
                types.remove();
            }
        }
    }

    private static void beginModel(
            ModelFeatureRenderer.Submit<?> submit,
            boolean localPlayerProxy) {
        if (activeBatch.get() != null) throw new IllegalStateException("Nested entity model capture");
        Identifier texture = resolveSampler0(submit.renderType());
        if (handPhase) {
            // 2026-10-06 诊断：第一人称手的贴图是玩家皮肤。本仓库注释里记过
            // "26.3 的皮肤走 PlayerSkinRenderCache，Sampler0 的 location 与
            // skin.body().texturePath() 不再相等" ⇒ 很可能 publishTexture 拿不到
            // view/sampler（静默返回），该槽描述符保持兜底、几何就成了黑块。
            // 这里**临时**把手的贴图换成方块图集：若手因此出现（贴图很怪），
            // 就证明是贴图解析问题；若仍不出现，则是位置/变换问题。仅诊断用。
            if (!handTextureSubstituted) {
                handTextureSubstituted = true;
                System.out.println("[RTX-HAND] texture probe: original="
                        + texture + " -> substituted with blocks atlas");
            }
            texture = BLOCKS_ATLAS_TEXTURE;
        }
        // The synthetic first-person proxy is submitted after the normal
        // entity list.  Publishing its body texture directly into the final
        // existing slot leaves the ordinary entity slot allocation unchanged.
        if (localPlayerProxy && isLocalPlayerBodyTexture(submit, texture)) {
            int textureSlot = MAX_TEXTURE_SLOTS;
            publishTexture(textureSlot, texture);
            ActiveBatch proxyBatch = acquireBatch(textureSlot, true, false);
            activeBatch.set(proxyBatch);
            // 2026-10-09 运动矢量：本地玩家**身体代理**同样要有位移（玩家在走/跑/
            // 飞行时，第一人称下自己的身体仍可能入镜——例如低头或第三人称）。
            resolveBatchMotion(proxyBatch, submit);
            return;
        }
        int textureSlot = slotFor(texture);
        if (isDiagnosticFrame()
                && submit.state() instanceof AvatarRenderState avatarState) {
            var player = Minecraft.getInstance().player;
            if (player != null && avatarState.id == player.getId()) {
                System.err.println("[RTX-PROXY] ordinary path saw local player, firstPerson="
                        + Minecraft.getInstance().options.getCameraType().isFirstPerson()
                        + ", textureNull=" + (texture == null) + ", slot=" + textureSlot);
            }
        }
        if (textureSlot != 0) {
            ActiveBatch batch = acquireBatch(textureSlot, localPlayerProxy, false);
            activeBatch.set(batch);
            // 2026-10-09 运动矢量：**这是全工程唯一能拿到"顶点属于哪个实体"的地方**
            // （captureVertex 的调用栈上没有实体上下文），位移在这里算好挂到批次上。
            resolveBatchMotion(batch, submit);
        }
    }

    private static boolean isLocalPlayerBodyTexture(
            ModelFeatureRenderer.Submit<?> submit, Identifier texture) {
        if (texture == null || !(submit.state() instanceof AvatarRenderState avatarState)) return false;
        var player = Minecraft.getInstance().player;
        return player != null
                && avatarState.id == player.getId()
                && avatarState.skin != null
                && texture.equals(avatarState.skin.body().texturePath());
    }

    public static void endModel() {
        ActiveBatch batch = activeBatch.get();
        activeBatch.remove();
        if (batch == null || batch.vertexCount == 0) {
            if (isDiagnosticFrame() && batch != null && batch.localPlayerProxy) {
                System.err.println("[RTX-PROXY] proxy batch EMPTY, slot=" + batch.textureSlot);
            }
            return;
        }
        uploadBatch(batch);
        if (batch.localPlayerProxy && isDiagnosticFrame()) {
            System.err.println("[RTX-PROXY] proxy upload slot=" + batch.textureSlot
                    + " vertices=" + batch.vertexCount);
        }
        // 2026-10-06 手部诊断（整个会话只打一次）。把这一批的槽位 / 顶点数 / 包围盒
        // 与玩家坐标放在一起：如果顶点数 > 0 但包围盒远离玩家坐标，说明"捕获到了但
        // 位置不对"（第一人称的手是相机相对空间，与实体层同空间，理论应贴近玩家）；
        // 如果压根没有这一行，说明手根本没被捕获。
        if (handPhase && !handBatchLogged) {
            handBatchLogged = true;
            Minecraft minecraft = Minecraft.getInstance();
            // 顶点是"相机相对 + 世界朝向"，所以和玩家的视线方向直接可比：
            // offAxisDeg 小 = 落在视野里（垂直半角约 FOV/2 ≈ 35°），
            // 接近 90°/更大 = 变换方向错了（手被放到侧面或背后）。
            final float cx = (batch.minX + batch.maxX) * 0.5f;
            final float cy = (batch.minY + batch.maxY) * 0.5f;
            final float cz = (batch.minZ + batch.maxZ) * 0.5f;
            double yaw = 0.0;
            double pitch = 0.0;
            if (minecraft.player != null) {
                yaw = Math.toRadians(minecraft.player.getYRot());
                pitch = Math.toRadians(minecraft.player.getXRot());
            }
            final double lx = -Math.sin(yaw) * Math.cos(pitch);
            final double ly = -Math.sin(pitch);
            final double lz = Math.cos(yaw) * Math.cos(pitch);
            final double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
            final double along = cx * lx + cy * ly + cz * lz;
            final double offAxis = dist > 1.0e-4
                    ? Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, along / dist))))
                    : -1.0;
            // 一次性判定：旋转前 / 旋转后，哪个更朝向视线前方。当前这批是"原始"
            // 上传的（判定之前不旋转），所以只影响一帧。
            final float rx = handRotation[0] * cx + handRotation[4] * cy + handRotation[8] * cz;
            final float ry = handRotation[1] * cx + handRotation[5] * cy + handRotation[9] * cz;
            final float rz = handRotation[2] * cx + handRotation[6] * cy + handRotation[10] * cz;
            final double alongM = rx * lx + ry * ly + rz * lz;
            final float sx = handRotation[0] * cx + handRotation[1] * cy + handRotation[2] * cz;
            final float sy = handRotation[4] * cx + handRotation[5] * cy + handRotation[6] * cz;
            final float sz = handRotation[8] * cx + handRotation[9] * cy + handRotation[10] * cz;
            final double alongT = sx * lx + sy * ly + sz * lz;
            // 只有明显更朝前（0.05 余量）才换模式，避免噪声里来回跳。
            handTransformMode = (alongM > along + 0.05 && alongM >= alongT)
                    ? 1
                    : (alongT > along + 0.05 ? 2 : 0);
            System.out.println("[RTX-HAND] batch slot=" + batch.textureSlot
                    + " verts=" + batch.vertexCount
                    + " center=[" + cx + "," + cy + "," + cz + "]"
                    + " dist=" + dist
                    + " alongRaw=" + along
                    + " alongM=" + alongM
                    + " alongMt=" + alongT
                    + " offAxisDeg=" + offAxis
                    + " transformMode=" + handTransformMode);
        }
    }

    private static boolean handBatchLogged;
    private static boolean vertexColorLogged;
    private static boolean handTextureSubstituted;

    /** 取小数部分并夹到 [0,1)：末地门合成 UV 用（顶点在整数角点上，见 captureVertex）。 */
    private static float fract01(float value) {
        final float f = value - (float) Math.floor(value);
        return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
    }

    /** 方块图集（无条件可解析）。诊断期用来替代手的皮肤贴图，见 beginModel。 */
    private static final Identifier BLOCKS_ATLAS_TEXTURE =
            Identifier.withDefaultNamespace("textures/atlas/blocks.png");

    /**
     * 手部顶点的变换模式：-1 = 未判定，0 = 不转（原始）、1 = M·v、2 = Mᵀ·v。
     *
     * <p>缺的这一步只能靠实测分辨（MC 视图空间的手性 / pose 里是否已含相机旋转）：
     * 三种候选的中心分别与玩家视线做点积，取最朝前的那个。整个会话只判一次，
     * 用当前这批判定，从下一批开始生效。</p>
     */
    private static int handTransformMode = -1;

    /**
     * 相机视图旋转的逆（view → world），由 {@code VulkanCameraMixin} 每帧写入。
     * 只用于把第一人称手的视图空间顶点旋到世界朝向（见 captureVertex 的说明）。
     */
    private static final float[] handRotation = new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
    };

    public static void setHandRotation(float[] columnMajor) {
        if (columnMajor == null || columnMajor.length < 16) return;
        System.arraycopy(columnMajor, 0, handRotation, 0, 16);
    }

    /**
     * 2026-10-18 末地传送门贴图随相机滑动：本帧相机世界原点，由
     * {@code VulkanCameraMixin} 每帧写入（与 native 用来把实体顶点从"世界减相机"
     * 还原到世界的 {@code viewInverse[12..14]} 同源同帧）。
     *
     * <p>投影 UV 必须用**世界**坐标合成才有"贴图钉在世界里"的观感；此前直接用了
     * 顶点里那份"世界减相机"坐标 ⇒ 贴图整体随相机平移，就是用户报的"随相机移动
     * 而变化"。这里把相机加回去，公式与缩放不变，相邻方块依旧连续。</p>
     */
    private static float cameraWorldX;
    private static float cameraWorldY;
    private static float cameraWorldZ;

    public static void setCameraOrigin(float x, float y, float z) {
        cameraWorldX = x;
        cameraWorldY = y;
        cameraWorldZ = z;
    }

    private static void uploadBatch(ActiveBatch batch) {
        // ModelFeatureRenderer may leave 1..3 non-face vertices at the end of
        // a submission (for example an auxiliary feature).  They cannot form
        // a triangle pair in this quad-to-triangle capture path, so discard
        // only that incomplete tail.  All completed quads remain intact.
        batch.pendingQuadVertexCount = 0;
        batch.buffer.flip();
        // 2026-10-06 末地门诊断（整个会话一次）：这是 "captured" 那行的**下半句**。
        // "captured" 只证明批次被打开；这里证明顶点**真的**写进了批次并要上传。
        //   · 出现且 vertices>0 ⇒ 几何确实在 TLAS 里，问题在下游（路由 / 裁切 / 层）；
        //   · vertices=0 或压根没有这一行 ⇒ RenderTypeFeatureRendererMixin 的顶点钩子
        //     没有覆盖这条提交链（captureVertex 没被调用），问题在**捕获**这一层。
        // 上一次只改了裁切与类判定却仍看不见，就是缺这句才在盲试。
        if (!endPortalBatchLogged && batch.textureSlot >= 1
                && batch.textureSlot <= MAX_TEXTURE_SLOTS) {
            final String endPortalLabel = frameTextureLabels[batch.textureSlot - 1];
            if (endPortalLabel != null && endPortalLabel.contains("end_portal")) {
                endPortalBatchLogged = true;
                System.out.println("[RTX-ENTITY] end portal batch upload slot="
                        + batch.textureSlot + " vertices=" + batch.vertexCount
                        + " label=" + endPortalLabel);
            }
        }
        // =====================================================
        // 2026-10-17 运动矢量：位移通道（逐顶点）。
        //
        // 旧口径是"整批一个平移常量"，因为位移只来自根位置差。现在位移是
        // `p_prev = delta · p_cur` 的仿射增量 ⇒ 必须逐顶点算，否则旋转不起作用。
        //
        // 位置直接从**已经写好的顶点缓冲**里读（绝对下标 getFloat，不改 position），
        // 所以与上传给 native 的几何**逐顶点同源**：不可能出现"位移按另一份坐标算"
        // 这种错位（这正是本通道历史上最容易踩的坑）。
        //
        // 成本：每顶点 9 乘 6 加 + 3 次绝对读；实体层顶点数在千级 ⇒ 每帧万级浮点运算，
        // 与模型捕获本身（每顶点 12 个 float 的拷贝 + 变换）同量级，可忽略。
        //
        // hasPoseDelta == false（文字/末地门/通用几何/静止实体/首帧）时**传 0**：
        // native 会把该段补 0，比在这里填一份全零缓冲更省，语义也完全相同。
        // =====================================================
        long motionPointer = 0L;
        if (batch.hasPoseDelta && batch.vertexCount > 0) {
            final int need = batch.vertexCount * 3 * Float.BYTES;
            if (batch.motionScratch == null || batch.motionScratch.capacity() < need) {
                batch.motionScratch = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder());
            } else {
                batch.motionScratch.clear();
            }
            final ByteBuffer positions = batch.buffer;
            final float[] delta = batch.poseDelta;
            for (int vertex = 0; vertex < batch.vertexCount; ++vertex) {
                final int offset = vertex * BYTES_PER_VERTEX;
                final float px = positions.getFloat(offset);
                final float py = positions.getFloat(offset + 4);
                final float pz = positions.getFloat(offset + 8);
                final float nx = delta[0] * px + delta[1] * py + delta[2] * pz + delta[3];
                final float ny = delta[4] * px + delta[5] * py + delta[6] * pz + delta[7];
                final float nz = delta[8] * px + delta[9] * py + delta[10] * pz + delta[11];
                batch.motionScratch.putFloat(nx - px).putFloat(ny - py).putFloat(nz - pz);
            }
            batch.motionScratch.flip();
            motionPointer = MemoryUtil.memAddress(batch.motionScratch);
        }

        NativeVulkan.uploadEntityBatch(batch.textureSlot, MemoryUtil.memAddress(batch.buffer), batch.vertexCount, BYTES_PER_VERTEX, motionPointer);
    }

    /**
     * 短格式 {@code addVertex(FFF)} 的顶点入口（由 {@code BufferBuilderMixin} 的第二个
     * 钩子调用）。**只在"投影 UV 批次"里捕获** —— 那一档（末地传送门）的 UV 本来就在
     * {@link #captureVertex} 里按顶点位置合成，颜色用不透明白即可；其余几何走短格式时
     * 不带 UV/颜色，通用捕获会让它们带着错值进来，所以刻意放行条件收得很紧。
     */
    public static void capturePositionOnlyVertex(float x, float y, float z) {
        final ActiveBatch batch = activeBatch.get();
        if (batch == null || !batch.projectiveUv) {
            return;
        }
        captureVertex(x, y, z, 0xFFFFFFFF, 0.0f, 0.0f);
    }

    public static void captureVertex(float x, float y, float z, int color, float u, float v) {
        // Section compilation can invoke BufferBuilder on worker threads while
        // the render thread ends a model.  Capture only the current thread's
        // model batch and retain one local reference for the whole call.
        ActiveBatch batch = activeBatch.get();
        if (batch == null) return;
        if (handPhase && handTransformMode != 0) {
            // 2026-10-06 第一人称手：顶点是**视图空间**的（手臂相对眼睛的偏移，没有
            // 相机旋转），而实体层用的是"世界朝向 + 相机相对"。缺的这一步只能是
            // M·v 或 Mᵀ·v（差一个转置 = 旋转方向相反），静态推不出来 ⇒ 三种候选
            // （不转 / M·v / Mᵀ·v）由 endModel 里按"谁更朝视线前方"选一次。
            final float rx;
            final float ry;
            final float rz;
            if (handTransformMode == 1) {
                rx = handRotation[0] * x + handRotation[4] * y + handRotation[8] * z;
                ry = handRotation[1] * x + handRotation[5] * y + handRotation[9] * z;
                rz = handRotation[2] * x + handRotation[6] * y + handRotation[10] * z;
            } else {
                rx = handRotation[0] * x + handRotation[1] * y + handRotation[2] * z;
                ry = handRotation[4] * x + handRotation[5] * y + handRotation[6] * z;
                rz = handRotation[8] * x + handRotation[9] * y + handRotation[10] * z;
            }
            x = rx;
            y = ry;
            z = rz;
        }
        if (batch.projectiveUv) {
            // 末地门没有顶点 UV（见 beginCustomSubmit 的说明）：用几何自身坐标合成。
            // ⚠ 必须**先缩放再取小数**：方块立方体的顶点都在整数角点上，直接
            // fract(坐标) 恒为 0 ⇒ 整个面只采纹理的 (0,0) 一个像素，而
            // end_portal.png 的 (0,0) 是黑的 ⇒ 整片黑、在暗房间里就是"看不见"
            //（实测症状）。缩放 0.25 后每个方块面跨 1/4 张贴图（256px 贴图约 64
            // 像素），且始终落在 [0,1) 内，不依赖采样器的 wrap 模式。
            // ⚠ 顶点是"世界减相机"（native 上传前才加回相机），所以这里必须先加回
            // 本帧相机原点再合成 —— 否则贴图整体随相机平移（见 setCameraOrigin）。
            final float wx = x + cameraWorldX;
            final float wy = y + cameraWorldY;
            final float wz = z + cameraWorldZ;
            u = fract01(wx * 0.25f);
            v = fract01(wz * 0.25f + wy * 0.125f);
        }
        final int incomingColor = color;
        if (batch.projectiveUv || handPhase) {
            // （末地门：buffer.addVertex(pose, vec3) 只有位置；第一人称手：颜色位是
            // 缓冲默认值，不是我们这套 VertexConsumer 的 0xFFFFFFFF），拿到 0 之后
            // RT 的 albedo = texture × color = 0 ⇒ 几何"黑掉"，在暗环境里就等于
            // 看不见（手和门实测都如此）。贴图本身已经带了明暗，这里不需要顶点色。
            color = 0xFFFFFFFF;
        }
        if ((batch.projectiveUv || handPhase) && !vertexColorLogged) {
            vertexColorLogged = true;
            System.out.println("[RTX-VCOLOR] incoming color=0x"
                    + Integer.toHexString(incomingColor)
                    + " -> forced white; hand=" + handPhase
                    + " portal=" + batch.projectiveUv
                    + " slot=" + batch.textureSlot);
        }
        batch.capture(x, y, z, color, u, v);
    }

    /**
     * Mirrors ExperienceOrbRenderer's submitCustomGeometry quad into the
     * existing dynamic-entity RT batch. The original raster submission is
     * untouched.
     */
    public static void captureExperienceOrb(
            PoseStack.Pose pose,
            float u0, float u1, float v0, float v1,
            int red, int green, int blue) {
        if (activeBatch.get() != null) {
            throw new IllegalStateException("Nested experience-orb RT capture");
        }
        int textureSlot = slotFor(EXPERIENCE_ORB_TEXTURE);
        if (textureSlot == 0) return;

        ActiveBatch batch = acquireBatch(textureSlot, false, true);
        activeBatch.set(batch);
        CapturingVertexConsumer consumer = batch.captureConsumer();
        try {
            consumer.addVertex(pose, -0.5F, -0.25F, 0.0F).setColor(red, green, blue, 128).setUv(u0, v1);
            consumer.addVertex(pose, 0.5F, -0.25F, 0.0F).setColor(red, green, blue, 128).setUv(u1, v1);
            consumer.addVertex(pose, 0.5F, 0.75F, 0.0F).setColor(red, green, blue, 128).setUv(u1, v0);
            consumer.addVertex(pose, -0.5F, 0.75F, 0.0F).setColor(red, green, blue, 128).setUv(u0, v0);
        } finally {
            consumer.finish();
            endModel();
        }
    }

    /**
     * 2026-10-06 末地传送门：**自合成几何**（不再拦截 BufferBuilder）。
     *
     * <p>为什么换路线：末地门走的是短格式 {@code addVertex(FFF)}（字节码已核对），原来的
     * 钩子只覆盖全格式 ⇒ 顶点一个都进不来。直接补钩短格式会**崩驱动**
     *（{@code hs_err_pid45160.log}：nvoglv64.dll 里 EXCEPTION_ACCESS_VIOLATION，
     * Render thread / in_native），因为它按"方向 × 面 × 方块"提交、**顶点数没有上界**，
     * 超出实体批次缓冲的容量假设 ⇒ 上传越界。所以这里照 {@link #captureExperienceOrb}
     * 的先例，由 Java 侧自己画 —— 顶点数与内容全部可控、有硬上界。</p>
     *
     * <p>几何（2026-10-18 修）：原版末地门是**压扁的立方体**（{@code TheEndPortalRenderer}
     * 的 TRANSFORMATION：平移 y=0.375、y 缩放 0.375 ⇒ 盒体 y∈[0.375,0.75]，x,z∈[0,1]），
     * 可见面是**水平面**。此前手搓的是两片竖直交叉四边形（本意"任意侧可见"），观感就是
     * 用户报的"十字交叉竖着"。现改为还原水平面：上（y=0.75）与下（y=0.375）两块水平
     * 四边形，各正反两遍（8 顶点）。pose 原点是**方块角点**（LevelRenderer
     * {@code submitBlockEntities} 只平移到 {@code blockPos − camPos}，无 0.5 偏移）
     * ⇒ 覆盖 [0,1]²。UV 按顶点世界坐标合成（projectiveUv 路径）。</p>
     */
    public static void captureEndPortal(PoseStack.Pose pose) {
        // 2026-10-06 一次性探针（整个会话一次）。上一版改了路线却仍看不见、日志里既没有
        // 上传行也没有异常 ⇒ 必须先把"走到哪一步"分清楚，否则又是盲试：
        //   reached ⇒ 混入命中了 submitSpecial；
        //   nested / slot=0 ⇒ 命中了但在这里早退（分别指向批次嵌套、贴图标签没登记）。
        if (!endPortalSyntheticLogged) {
            endPortalSyntheticLogged = true;
            System.out.println("[RTX-ENTITY] end portal synthetic reached: activeBatch="
                    + (activeBatch.get() != null));
        }
        if (activeBatch.get() != null) {
            return;
        }
        final int textureSlot = slotForLabel("end_portal");
        if (textureSlot == 0) {
            if (!endPortalSyntheticSlotLogged) {
                endPortalSyntheticSlotLogged = true;
                System.out.println("[RTX-ENTITY] end portal synthetic: slot lookup FAILED"
                        + " (label not registered this frame)");
            }
            return;
        }
        ActiveBatch batch = acquireBatch(textureSlot, false, true);
        // 与原版几何同源：UV 由顶点世界坐标合成（见 CapturingVertexConsumer.flush），
        // 相邻方块因此连续，而不是每方块重置一次平铺。
        batch.projectiveUv = true;
        activeBatch.set(batch);
        CapturingVertexConsumer consumer = batch.captureConsumer();
        try {
            // 原版盒体的上/下水平面（见函数上的说明）。各自正反两遍。
            emitPortalHorizontalQuad(consumer, pose, 0.75F, true);
            emitPortalHorizontalQuad(consumer, pose, 0.375F, true);
            emitPortalHorizontalQuad(consumer, pose, 0.75F, false);
            emitPortalHorizontalQuad(consumer, pose, 0.375F, false);
        } finally {
            consumer.finish();
            endModel();
        }
    }

    /**
     * 末地门的**水平**四边形（XZ 平面，y 恒定），覆盖整个方块 [0,1]²。顶点顺序按原版
     * CCW（从上往下看）；forward=false 时反向，供从下方看。UV 由批次的 {@code projectiveUv}
     * 路径按顶点世界坐标合成（这里的 setUv 占位，会被覆盖）。
     */
    private static void emitPortalHorizontalQuad(
            CapturingVertexConsumer consumer,
            PoseStack.Pose pose,
            float y,
            boolean forward) {
        final float ax = forward ? 0.0F : 1.0F;
        final float bx = forward ? 1.0F : 0.0F;
        consumer.addVertex(pose, ax, y, 0.0F).setColor(255, 255, 255, 255).setUv(0.0F, 1.0F);
        consumer.addVertex(pose, bx, y, 0.0F).setColor(255, 255, 255, 255).setUv(1.0F, 1.0F);
        consumer.addVertex(pose, bx, y, 1.0F).setColor(255, 255, 255, 255).setUv(1.0F, 0.0F);
        consumer.addVertex(pose, ax, y, 1.0F).setColor(255, 255, 255, 255).setUv(0.0F, 0.0F);
    }

    /**
     * 2026-10-21 闪电：原版 {@code LightningBoltRenderer.submit} 用
     * {@code addVertex(pose,…)+setColor} 链写顶点，不经过
     * {@code BufferBuilder.addVertex(FFFIFFIIFFF)} 的全格式钩子；同时
     * {@code RenderTypes.lightning()} 没有 Sampler0，通用捕获会在贴图解析处
     * 直接 return。两条路都收不到 ⇒ 闪电完全不可见。
     *
     * <p>这里照原版算法合成 3 分支 quad（种子/偏移/半径逐项一致；只发最内层壳，
     * 见下方 radiusScale 的说明），贴图取 8 列闪电精灵图（CC0，见
     * {@code rt_lightning.png} 的下载来源注释）里的一列 + 顶点色，并把整批标成
     * 自发光（pad1 = -1）。透明像素由 any-hit 的 alpha 裁切（{@code rt_test.rahit}
     * 的 combinedAlpha &lt; 0.1）剔掉，所以闪电是贴图里的细亮线而不是整块卡片。
     * 顶点数有硬上界，不会重演末地门短格式钩子的驱动越界。</p>
     */
    public static void captureLightning(long seed, PoseStack poseStack) {
        if (activeBatch.get() != null) {
            return;
        }
        final int textureSlot = slotFor(LIGHTNING_TEXTURE);
        if (textureSlot == 0) {
            if (!lightningSyntheticSlotLogged) {
                lightningSyntheticSlotLogged = true;
                System.out.println("[RTX-ENTITY] lightning synthetic: slot lookup FAILED");
            }
            return;
        }
        if (!lightningSyntheticLogged) {
            lightningSyntheticLogged = true;
            System.out.println("[RTX-ENTITY] lightning synthetic captured, slot=" + textureSlot);
        }
        final ActiveBatch batch = acquireBatch(textureSlot, false, true);
        activeBatch.set(batch);
        final CapturingVertexConsumer consumer = batch.captureConsumer();
        final PoseStack.Pose pose = poseStack.last();
        try {
            final float[] xOffsets = new float[8];
            final float[] zOffsets = new float[8];
            float xOffset = 0.0F;
            float zOffset = 0.0F;
            final RandomSource random = RandomSource.createThreadLocalInstance(seed);
            for (int i = 7; i >= 0; --i) {
                xOffsets[i] = xOffset;
                zOffsets[i] = zOffset;
                xOffset += random.nextInt(11) - 5;
                zOffset += random.nextInt(11) - 5;
            }
            final float finalXOffset = xOffset;
            final float finalZOffset = zOffset;
            // RT 实体层没有加色混合：原版 4 层是"由细到粗的同心壳 + alpha 0.3"
            // 叠加出来的辉光，照搬会只剩最外层 ⇒ 变成一根 2.4 格粗的实心绳。
            // 只发最内层壳（layer 0），并把半径略放大补偿辉光缺失。
            final float radiusScale = 1.8F;
            for (int layer = 0; layer < 1; ++layer) {
                final RandomSource layerRandom = RandomSource.createThreadLocalInstance(seed);
                for (int branch = 0; branch < 3; ++branch) {
                    final boolean trunk = branch == 0;
                    final int branchStart = 7 - branch;
                    final int branchEnd = trunk ? 0 : branchStart - 3 + 1;
                    float startX = xOffsets[branchStart] - finalXOffset;
                    float startZ = zOffsets[branchStart] - finalZOffset;
                    for (int segment = branchStart; segment >= branchEnd; --segment) {
                        final float endX = startX;
                        final float endZ = startZ;
                        if (trunk) {
                            startX += layerRandom.nextInt(11) - 5;
                            startZ += layerRandom.nextInt(11) - 5;
                        } else {
                            startX += layerRandom.nextInt(31) - 15;
                            startZ += layerRandom.nextInt(31) - 15;
                        }
                        float topRadius = 0.1F + layer * 0.2F;
                        float bottomRadius = topRadius;
                        if (trunk) {
                            topRadius *= segment * 0.1F + 1.0F;
                            bottomRadius *= (segment - 1) * 0.1F + 1.0F;
                        }
                        topRadius *= radiusScale;
                        bottomRadius *= radiusScale;
                        emitLightningQuad(consumer, pose, startX, startZ, endX, endZ,
                                segment, topRadius, bottomRadius, false, false, true, false,
                                (segment + branch) & 7);
                        emitLightningQuad(consumer, pose, startX, startZ, endX, endZ,
                                segment, topRadius, bottomRadius, true, false, true, true,
                                (segment + branch + 2) & 7);
                        emitLightningQuad(consumer, pose, startX, startZ, endX, endZ,
                                segment, topRadius, bottomRadius, true, true, false, true,
                                (segment + branch + 4) & 7);
                        emitLightningQuad(consumer, pose, startX, startZ, endX, endZ,
                                segment, topRadius, bottomRadius, false, true, false, false,
                                (segment + branch + 6) & 7);
                    }
                }
            }
        } finally {
            consumer.finish();
            endModel();
        }
    }

    private static void emitLightningQuad(
            CapturingVertexConsumer consumer,
            PoseStack.Pose pose,
            float segmentStartX,
            float segmentStartZ,
            float segmentEndX,
            float segmentEndZ,
            int segment,
            float topRadius,
            float bottomRadius,
            boolean rightXPositive,
            boolean rightZPositive,
            boolean leftXPositive,
            boolean leftZPositive,
            int column) {
        final int color = 0xFFB9C8FF;
        // 8 列精灵图：每列 64px。取列内 2px 内缩，避开线性过滤在列边界的串色。
        final float u0 = (column + 0.03F) * 0.125F;
        final float u1 = (column + 0.97F) * 0.125F;
        consumer.addVertex(pose,
                        segmentStartX + (rightXPositive ? bottomRadius : -bottomRadius),
                        segment * 16,
                        segmentStartZ + (rightZPositive ? bottomRadius : -bottomRadius))
                .setColor(color).setUv(u0, 0.0F);
        consumer.addVertex(pose,
                        segmentEndX + (rightXPositive ? topRadius : -topRadius),
                        (segment + 1) * 16,
                        segmentEndZ + (rightZPositive ? topRadius : -topRadius))
                .setColor(color).setUv(u1, 0.0F);
        consumer.addVertex(pose,
                        segmentEndX + (leftXPositive ? topRadius : -topRadius),
                        (segment + 1) * 16,
                        segmentEndZ + (leftZPositive ? topRadius : -topRadius))
                .setColor(color).setUv(u1, 1.0F);
        consumer.addVertex(pose,
                        segmentStartX + (leftXPositive ? bottomRadius : -bottomRadius),
                        segment * 16,
                        segmentStartZ + (leftZPositive ? bottomRadius : -bottomRadius))
                .setColor(color).setUv(u0, 1.0F);
    }

    /**
     * 2026-10-21 信标光柱：{@code BeaconRenderer.submit} 同样走
     * {@code addVertex(pose,…)+setColor/setUv} 链，全格式钩子收不到顶点，
     * 通用捕获只开了一个空批次 ⇒ 光柱不可见。
     *
     * <p>按原版 {@code submitBeaconBeam} 的几何、UV 滚动与转速重建实心柱
     *（外辉那层是加色混合，RT 里没有对应通路，省略；实心柱本身已带贴图渐变）。
     * 每个 section 4 个 quad、16 个顶点，上界固定。</p>
     */
    public static void captureBeaconBeam(BeaconRenderState state, PoseStack poseStack) {
        if (state.sections.isEmpty() || activeBatch.get() != null) {
            return;
        }
        final int textureSlot = slotFor(BeaconRenderer.BEAM_LOCATION);
        if (textureSlot == 0) {
            if (!beaconSyntheticSlotLogged) {
                beaconSyntheticSlotLogged = true;
                System.out.println("[RTX-ENTITY] beacon synthetic: slot lookup FAILED");
            }
            return;
        }
        if (!beaconSyntheticLogged) {
            beaconSyntheticLogged = true;
            System.out.println("[RTX-ENTITY] beacon synthetic captured, slot=" + textureSlot);
        }
        final ActiveBatch batch = acquireBatch(textureSlot, false, true);
        activeBatch.set(batch);
        final CapturingVertexConsumer consumer = batch.captureConsumer();
        try {
            int beamStart = 0;
            final int sectionCount = state.sections.size();
            for (int i = 0; i < sectionCount; ++i) {
                final BeaconRenderState.Section section = state.sections.get(i);
                final int height = i == sectionCount - 1 ? 2048 : section.height();
                emitBeaconBeam(consumer, poseStack, state.animationTime,
                        beamStart, height, section.color(), 0.2F * state.beamRadiusScale);
                beamStart += section.height();
            }
        } finally {
            consumer.finish();
            endModel();
        }
    }

    private static void emitBeaconBeam(
            CapturingVertexConsumer consumer,
            PoseStack poseStack,
            float animationTime,
            int beamStart,
            int height,
            int color,
            float radius) {
        if (radius <= 1.0E-4F) {
            return;
        }
        final int beamEnd = beamStart + height;
        final float scroll = height < 0 ? animationTime : -animationTime;
        final float rawV = scroll * 0.2F - (float) Math.floor(scroll * 0.1F);
        final float texVOff = rawV - (float) Math.floor(rawV);
        final float vv2 = -1.0F + texVOff;
        final float vv1 = height * (0.5F / radius) + vv2;

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.rotateDegrees(Axis.YP, animationTime * 2.25F - 45.0F);
        final PoseStack.Pose pose = poseStack.last();
        // 原版实心柱的四个面：(0,+r)→(+r,0)、(-r,0)→(0,-r)、
        // (+r,0)→(0,-r)、(0,-r)→(0,+r)。
        emitBeaconQuad(consumer, pose, color, beamStart, beamEnd,
                0.0F, radius, radius, 0.0F, 0.0F, 1.0F, vv1, vv2);
        emitBeaconQuad(consumer, pose, color, beamStart, beamEnd,
                -radius, 0.0F, 0.0F, -radius, 0.0F, 1.0F, vv1, vv2);
        emitBeaconQuad(consumer, pose, color, beamStart, beamEnd,
                radius, 0.0F, 0.0F, -radius, 0.0F, 1.0F, vv1, vv2);
        emitBeaconQuad(consumer, pose, color, beamStart, beamEnd,
                0.0F, -radius, 0.0F, radius, 0.0F, 1.0F, vv1, vv2);
        poseStack.popPose();
    }

    private static void emitBeaconQuad(
            CapturingVertexConsumer consumer,
            PoseStack.Pose pose,
            int color,
            int beamStart,
            int beamEnd,
            float westX,
            float westZ,
            float eastX,
            float eastZ,
            float uu1,
            float uu2,
            float vv1,
            float vv2) {
        consumer.addVertex(pose, westX, beamEnd, westZ).setColor(color).setUv(uu2, vv1);
        consumer.addVertex(pose, westX, beamStart, westZ).setColor(color).setUv(uu2, vv2);
        consumer.addVertex(pose, eastX, beamStart, eastZ).setColor(color).setUv(uu1, vv2);
        consumer.addVertex(pose, eastX, beamEnd, eastZ).setColor(color).setUv(uu1, vv1);
    }

    /**
     * 按**贴图标签子串**取槽位（末地门用）。刻意不自己构造 {@code Identifier}：
     * 标签来自 {frameTextureLabels}，也就是渲染管线自己解析出的那个，
     * 避免路径/命名空间约定与版本不一致时静默失配。
     */
    private static int slotForLabel(String needle) {
        for (int index = 0; index < frameTextureSlotCount; ++index) {
            final String label = frameTextureLabels[index];
            if (label != null && label.contains(needle)) {
                slotLastSeenFrame[index] = captureFrameSerial;
                return index + 1;
            }
        }
        return 0;
    }

    public static void onTextureBound(String textureLabel, long imageView, long sampler) {
        for (int index = 0; index < frameTextureSlotCount; ++index) {
            if (frameTextureLabels[index].equals(textureLabel)) {
                publishTextureHandle(index + 1, imageView, sampler);
                return;
            }
        }
    }

    private static int slotFor(Identifier texture) {
        if (texture == null) return 0;
        for (int index = 0; index < frameTextureSlotCount; ++index) {
            if (texture.equals(frameTextureSlots[index])) {
                slotLastSeenFrame[index] = captureFrameSerial;
                assignTextureClass(index, texture);
                // Retry the publish until it succeeds: a texture captured while
                // its Vulkan view was still 0 stays unpublished until the view
                // exists.  The per-slot latch skips the TextureManager lookup
                // in the steady state; invalidateTextureCache clears it on
                // resource reloads, so handle churn across a reload is still
                // picked up.
                if (!slotTexturePublished[index]) {
                    publishTexture(index + 1, texture);
                }
                // 2026-10-09 LabPBR：`_n`/`_s` 的视图是异步加载的，可能比 albedo 晚
                // 一帧才就绪，所以它有自己的 settle 位与重试（albedo 一旦 latched
                // 就不再走上面那条路，不能顺带把它也钉死）。
                if (!entityPbrSettled[index]) {
                    publishEntityPbrTexture(index + 1, texture);
                }
                return index + 1;
            }
        }
        int victim;
        if (frameTextureSlotCount < MAX_REGULAR_TEXTURE_SLOTS) {
            victim = frameTextureSlotCount;
        } else {
            // 2026-10-06：满了不再"丢弃几何"，改成 LRU 抢占。
            // 旧逻辑只肯回收"连续 SLOT_EVICTION_GRACE_FRAMES 帧没出现"的槽（本意是
            // 给延迟特性管线留宽限），代价是一帧内新出现的纹理**必然**拿不到槽 ——
            // 第一人称的手（帧首提交）与末地传送门就是这样整片消失的（实测日志：
            // `slot table full, dropping texture .../netherite.png`）。
            // 现在取"最久未被看见"的槽，优先本帧还没用过的；实在都在用才动本帧
            // 用过的（那会短暂串一下纹理，也好过让几何直接不见）。
            victim = -1;
            int bestAge = -1;
            for (int index = 0; index < MAX_REGULAR_TEXTURE_SLOTS; ++index) {
                final int age = captureFrameSerial - slotLastSeenFrame[index];
                if (age >= SLOT_EVICTION_GRACE_FRAMES) {
                    victim = index;
                    break;
                }
                if (age > bestAge) {
                    bestAge = age;
                    victim = index;
                }
            }
            if (victim < 0) {
                if (isDiagnosticFrame()) {
                    System.err.println("[RTX-ENTITY] slot table full, dropping texture " + texture);
                }
                return 0;
            }
            // 抢占"本帧已经用过"的槽 = 本帧早先上传、顶点里仍写着这个槽号的几何，会
            // 改采样到新贴图。这是实体层"串图"的唯一来源，所以单独计数（永远打，
            // 不受 isDiagnosticFrame 门控的原来那句在这里抓不到）。
            if (slotLastSeenFrame[victim] == captureFrameSerial) {
                ++evictionsInUse;
                // 永不清零的累计值：>0 即"串图那条路真的被走到过"（用户报的物品展示框
                // 贴图错乱就是它）。见 evictionsInUseTotal 的声明。
                ++evictionsInUseTotal;
            }
        }
        frameTextureSlots[victim] = texture;
        frameTextureLabels[victim] = texture.toString();
        assignTextureClass(victim, texture);
        slotLastSeenFrame[victim] = captureFrameSerial;
        if (victim == frameTextureSlotCount) ++frameTextureSlotCount;
        // 槽被换人 ⇒ 上一张的 `_n`/`_s` 结论作废（新贴图可能没有，必须显式把
        // native 那一槽推回中性兜底，否则会沿用旧句柄 = 串图）。
        entityPbrSettled[victim] = false;
        publishTexture(victim + 1, texture);
        return victim + 1;
    }

    // Records the slot's material class and reports the ones that are not the
    // default. The print exists because the classifier keys off texture paths:
    // if 26.2's equipment textures are named differently the class silently stays
    // default (armour looks unchanged) rather than breaking anything, and this
    // line is the only way to tell which of the two happened.
    private static void assignTextureClass(int index, Identifier texture) {
        // 2026-10-06：不裁切档优先，且**不经过** classifyEntityMaterial（那条路在
        // entityArmorMetal 关闭时直接返回 DEFAULT ⇒ 会让末地门的可见性跟着盔甲设置变）。
        final int materialClass = isUncutoutTexture(texture)
                ? MATERIAL_CLASS_UNCUTOUT
                : classifyEntityMaterial(texture);


        if (frameTextureClasses[index] == materialClass) {
            return;
        }


        frameTextureClasses[index] = materialClass;


        if (materialClass != MATERIAL_CLASS_DEFAULT) {
            System.out.println(
                    "[RTX-ENTITY] material class " + materialClass
                            + " slot " + (index + 1)
                            + " for " + texture
            );
        }
    }

    // Armour and other equipment get a metal or soft-dielectric response; plain
    // mob skins deliberately stay MATERIAL_CLASS_DEFAULT so their appearance does
    // not change. The metals follow vanilla's equipment materials, and a metal
    // takes its F0 from the albedo (the same LabPBR convention the block path
    // already uses), so iron reads grey-white, gold yellow, copper orange.
    // Material classification is a pure function of the texture Identifier, so
    // memoize it per Identifier.  The opt-in switch below is part of the
    // effective input; flipping it invalidates the whole cache, preserving the
    // "setting takes effect on the next captured batch" behaviour.
    private static final java.util.HashMap<Identifier, Integer> MATERIAL_CLASS_CACHE =
            new java.util.HashMap<>();
    private static boolean materialClassCacheMetalEnabled;

    private static int classifyEntityMaterial(Identifier texture) {
        if (texture == null) {
            return MATERIAL_CLASS_DEFAULT;
        }


        // A glossy entity hit costs an extra full-resolution reflection ray per
        // pixel, and armour that fills the screen pays it on almost every pixel,
        // so the metal response is opt-in and switched off by default. Read from
        // the capture thread each time, so flipping the setting takes effect on
        // the next captured batch without a restart.
        boolean metalEnabled = RTXSettingsConfig.isEntityArmorMetalEnabled();
        if (!metalEnabled) {
            if (materialClassCacheMetalEnabled) {
                materialClassCacheMetalEnabled = false;
                MATERIAL_CLASS_CACHE.clear();
            }
            return MATERIAL_CLASS_DEFAULT;
        }
        if (!materialClassCacheMetalEnabled) {
            materialClassCacheMetalEnabled = true;
            MATERIAL_CLASS_CACHE.clear();
        }

        Integer cached = MATERIAL_CLASS_CACHE.get(texture);
        if (cached != null) {
            return cached;
        }

        int materialClass = classifyEntityMaterialUncached(texture);
        MATERIAL_CLASS_CACHE.put(texture, materialClass);
        return materialClass;
    }

    private static int classifyEntityMaterialUncached(Identifier texture) {
        final String path =
                texture.toString().toLowerCase(Locale.ROOT);


        // Only equipment textures carry a class. This keeps the change strictly
        // to armour/equipment: mob skins, particles and weather keep the flat
        // response they have today.
        if (!path.contains("/equipment/")
                && !path.contains("armor")
                && !path.contains("elytra")) {
            return MATERIAL_CLASS_DEFAULT;
        }


        if (path.contains("netherite")) {
            return MATERIAL_CLASS_NETHERITE;
        }

        if (path.contains("diamond")) {
            return MATERIAL_CLASS_DIAMOND;
        }

        if (path.contains("gold")) {
            return MATERIAL_CLASS_GOLD;
        }

        if (path.contains("copper")) {
            return MATERIAL_CLASS_COPPER;
        }

        if (path.contains("iron") || path.contains("chainmail")) {
            return MATERIAL_CLASS_IRON;
        }


        // Leather, turtle shell and elytra: a plain dielectric, not a metal.
        return MATERIAL_CLASS_SOFT;
        }

        /**
        * 大片透明但必须**整体可见**的贴图（目前只有末地传送门的星幕
        * {@code end_portal.png}）。走"不裁切"档，且刻意**不经过**
        * {@link #classifyEntityMaterial}（那条路在 {@code entityArmorMetal} 关闭时返回
        * DEFAULT，会让末地门的可见性跟着盔甲设置变化）。消费端见 {@code rt_test.rahit}。
        */
        private static boolean isUncutoutTexture(Identifier texture) {
        return texture != null && texture.toString().contains("end_portal");
        }

    // prepareModel() runs before LevelRenderer executes its frame graph.  Bind
    // the exact resource texture here instead of waiting for a later RenderPass
    // bind, which can happen after the native RT pass has already been recorded.
    private static void publishTexture(int slot, Identifier textureId) {
        AbstractTexture texture = Minecraft.getInstance()
                .getTextureManager()
                .getTexture(textureId);
        GpuTextureView view = texture.getTextureView();
        GpuSampler sampler = texture.getSampler();
        if (view instanceof VulkanGpuTextureView vulkanView
                && sampler instanceof VulkanGpuSampler vulkanSampler) {
            publishTextureHandle(slot, vulkanView.vkImageView(), vulkanSampler.vkSampler());
        }
        // 2026-10-09 同一槽的 LabPBR（`_n`/`_s`）一起解、一起推。
        publishEntityPbrTexture(slot, textureId);
    }

    // =====================================================================
    // 2026-10-09 实体 LabPBR（`_n`/`_s`）解析与上传
    //
    // 与方块图集的路子（SpriteLoaderMixin 在 stitch 阶段把 `_n`/`_s` 读成
    // NativeImage、TextureAtlasPBRMixin 拼进全局 PBR 图集）不同：实体贴图**不在任何
    // 图集里**，RenderType 的 Sampler0 直接指向 TextureManager 里的独立贴图，所以只能
    // 单独查表 + 单独上传，用与 albedo 同构的一组 sampler2D 数组（binding 63/64）。
    // =====================================================================

    /** 一次解析并推送一个槽的 `_n`/`_s`；没有贴图/视图未就绪时会保持 unsettled 以便重试。 */
    private static void publishEntityPbrTexture(int slot, Identifier albedoTexture) {
        if (slot < 1 || slot > MAX_TEXTURE_SLOTS || albedoTexture == null) return;
        int index = slot - 1;

        Identifier[] siblings = resolveEntityPbrSiblings(albedoTexture);
        Identifier normalId = siblings[0];
        Identifier specularId = siblings[1];

        long normalView = 0L, normalSampler = 0L, specularView = 0L, specularSampler = 0L;
        boolean pending = false;
        if (normalId != null) {
            long[] handle = entityTextureHandle(normalId);
            if (handle == null) pending = true;
            else { normalView = handle[0]; normalSampler = handle[1]; }
        }
        if (specularId != null) {
            long[] handle = entityTextureHandle(specularId);
            if (handle == null) pending = true;
            else { specularView = handle[0]; specularSampler = handle[1]; }
        }
        // 有文件但 Vulkan 视图还没建好：这一帧别推 0（那会被 native 当成"没有贴图"
        // 而绑回兜底，且句柄一旦记下就不再重试），等下一帧。
        if (pending) return;

        entityPbrSettled[index] = true;

        if (normalView == publishedEntityNormalViews[index]
                && normalSampler == publishedEntityNormalSamplers[index]
                && specularView == publishedEntitySpecularViews[index]
                && specularSampler == publishedEntitySpecularSamplers[index]) {
            return;
        }

        publishedEntityNormalViews[index] = normalView;
        publishedEntityNormalSamplers[index] = normalSampler;
        publishedEntitySpecularViews[index] = specularView;
        publishedEntitySpecularSamplers[index] = specularSampler;

        if (!entityPbrNativeAvailable) return;
        try {
            NativeVulkan.updateEntityPbrTexture(
                    slot, normalView, normalSampler, specularView, specularSampler);
        } catch (UnsatisfiedLinkError staleNative) {
            // 先落的 spv、后落的 DLL：老 DLL 没有这个导出。永久关掉这条路，
            // 着色器侧因为整槽都是中性兜底 ⇒ 自动回落类别值，不会乱。
            entityPbrNativeAvailable = false;
        }
    }

    /**
     * 解析 albedo Identifier 对应的 `_n`/`_s`（存在的返回 Identifier，不存在返回
     * null）。结果按 albedo Identifier 缓存 —— ResourceManager 查表要扫资源包，
     * 绝不能逐帧做；资源包重载时随 {@link #invalidateTextureCache()} 清空。
     */
    private static Identifier[] resolveEntityPbrSiblings(Identifier albedoTexture) {
        Identifier[] cached = ENTITY_PBR_SIBLINGS.get(albedoTexture);
        if (cached != null) return cached;

        Identifier normalId = siblingTextureId(albedoTexture, "_n");
        Identifier specularId = siblingTextureId(albedoTexture, "_s");
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        if (normalId != null && manager.getResource(normalId).isEmpty()) normalId = null;
        if (specularId != null && manager.getResource(specularId).isEmpty()) specularId = null;

        Identifier[] result = new Identifier[] { normalId, specularId };
        ENTITY_PBR_SIBLINGS.put(albedoTexture, result);
        return result;
    }

    /** {@code .../foo.png} → {@code .../foo_n.png}；非 .png 的贴图路径直接放弃。 */
    private static Identifier siblingTextureId(Identifier albedo, String suffix) {
        String path = albedo.getPath();
        if (!path.endsWith(".png")) return null;
        return Identifier.fromNamespaceAndPath(
                albedo.getNamespace(),
                path.substring(0, path.length() - 4) + suffix + ".png");
    }

    /** 取一张已注册贴图的 Vulkan 句柄；视图尚未就绪时返回 null（调用方据此重试）。 */
    private static long[] entityTextureHandle(Identifier textureId) {
        try {
            AbstractTexture texture = Minecraft.getInstance()
                    .getTextureManager()
                    .getTexture(textureId);
            if (texture == null) return null;
            GpuTextureView view = texture.getTextureView();
            GpuSampler sampler = texture.getSampler();
            if (view instanceof VulkanGpuTextureView vulkanView
                    && sampler instanceof VulkanGpuSampler vulkanSampler) {
                long imageView = vulkanView.vkImageView();
                long vkSampler = vulkanSampler.vkSampler();
                if (imageView != 0L && vkSampler != 0L) {
                    return new long[] { imageView, vkSampler };
                }
            }
        } catch (RuntimeException ignored) {
            // 视图未就绪时 Blaze3D 可能抛异常；当作"还没有"，下一帧再来。
        }
        return null;
    }

    private static void publishTextureHandle(int slot, long imageView, long sampler) {
        if (slot < 1 || slot > MAX_TEXTURE_SLOTS || imageView == 0L || sampler == 0L) return;
        int index = slot - 1;
        if (publishedEntityViews[index] == imageView && publishedEntitySamplers[index] == sampler) {
            // Already current; the latch may still be unset (first frame this
            // handle pair was seen), so turn it on here.
            slotTexturePublished[index] = true;
            return;
        }
        publishedEntityViews[index] = imageView;
        publishedEntitySamplers[index] = sampler;
        slotTexturePublished[index] = true;
        NativeVulkan.updateEntityTexture(slot, imageView, sampler);
    }

    // =====================================================================
    // 2026-10-06 通用 RenderType 几何捕获（Custom / Item / BlockModel / MovingBlock）
    //
    // 26.3 的几何有好几条提交链，mod 原来只钩了 ModelFeatureRenderer（实体模型）
    // 与 TextFeatureRenderer（告示牌/名牌字形）：
    //   CustomFeatureRenderer      → 画、地图、**末地传送门**、末地折跃门、
    //                                信标光柱、物品展示实体、守卫者光束、龙……
    //   ItemFeatureRenderer        → **物品展示框里的物品**、掉落物、手持物品
    //   BlockModelFeatureRenderer  → **物品展示框的框体**、移动方块模型……
    //   MovingBlockFeatureRenderer → 活塞推动中的方块
    // 这些几何原来一条都没进 RT，而原版光栅世界输出又被整帧替换 ⇒ 直接消失
    // （用户报的"画、物品展示框这些实体没渲染，末地传送门也没渲染"）。
    //
    // 四条链共用基类 RenderTypeFeatureRenderer，都通过 getVertexBuilder(RenderType)
    // 取得写入目标 ⇒ 在这一个点上开批次即可全覆盖。模型路径不受影响：
    // ModelFeatureRendererMixin 已在 prepareModel HEAD 打开批次，这里见到
    // activeBatch 非空就让路，原逻辑一字未动。
    // =====================================================================
    private static RenderType customRenderType;
    private static boolean customBatchOpen;

    /** 由 {@code RenderTypeFeatureRendererMixin} 在 getVertexBuilder 返回时调用。 */
    public static void beginCustomSubmit(RenderType renderType) {
        if (activeBatch.get() != null) {
            return;
        }
        if (customBatchOpen && customRenderType == renderType) {
            return;
        }
        flushCustomBatch();
        if (renderType == null) {
            return;
        }
        final String typeName = renderTypeName(renderType);
        // ⚠ 2026-10-06 实体影子必须拒绝：ShadowFeatureRenderer **也**继承
        //   RenderTypeFeatureRenderer，它的几何是贴地的一整块黑色四边形（光追里
        //   alpha cutout 之后就是硬边黑盘）⇒ 玩家脚下会冒出黑色圆斑（实测）。
        //   影子本来就该由光追自己算，绝不能当世界几何收进来。
        if (typeName != null && typeName.contains("shadow")) {
            return;
        }
        if (isScreenOverlayRenderType(typeName)) {
            return;
        }
        // ⚠ 2026-10-06 末地传送门：它的 Sampler0 是 end_sky（近乎全黑的星幕），
        //   原版观感来自 Sampler1 的紫色漩涡 + 专用管线。按 Sampler0 取只会得到
        //   一片黑，看起来正好像"门没渲染"。所以这一档改取 Sampler1。
        final boolean endPortal = typeName != null && typeName.contains("end_portal");
        Identifier texture = endPortal ? resolveSampler(renderType, "Sampler1") : null;
        if (texture == null) {
            texture = resolveSampler(renderType, "Sampler0");
        }
        if (texture == null) {
            return;
        }
        int textureSlot = slotFor(texture);
        if (textureSlot == 0) {
            return;
        }
        ActiveBatch customBatch = acquireBatch(textureSlot, false,
                endPortal || isEmissiveGeometry(texture));
        // 2026-10-06 末地门 / 末地折跃门：原版几何是 submitCube 的
        // `buffer.addVertex(pose, faceVertex)` —— **只有位置、没有 UV**，贴图在原版
        // 着色器里走 textureProj(texProj0) 的**屏幕空间投影**（见
        // rendertype_end_portal.fsh）。RT 里没有那个投影，照抄顶点 UV 只会恒采纹理
        // 的 (0,0) 一角 ⇒ 渲染成一片黑 ⇒"末地传送门看不见"。这里改成用顶点自身坐标
        // 合成 UV（下面 captureVertex 里做），观感是"门内一片紫色星海"。
        customBatch.projectiveUv = endPortal;
        if (endPortal && !endPortalLogged) {
            endPortalLogged = true;
            System.out.println("[RTX-ENTITY] end portal geometry captured, slot="
                    + textureSlot + " texture=" + texture);
        }
        activeBatch.set(customBatch);
        customBatchOpen = true;
        customRenderType = renderType;
    }

    /** 由 {@code RenderTypeFeatureRendererMixin} 在 prepareGroup 收尾时调用。 */
    public static void endCustomGroup() {
        flushCustomBatch();
    }

    private static void flushCustomBatch() {
        if (!customBatchOpen) {
            return;
        }
        customBatchOpen = false;
        customRenderType = null;
        endModel();
    }

    /**
     * 屏幕特效（水下 / 火焰 / 方块遮罩）也是 submitCustomGeometry，但它是**贴着
     * 相机**的全屏四边形 —— 一旦当世界几何收进 TLAS，就会变成糊在镜头上的一层。
     * 只能按 RenderType 名字拒：火焰那一档的 Sampler0 是方块图集，按贴图路径
     * 会连正常方块一起拒掉。
     */
    private static boolean isScreenOverlayRenderType(String typeName) {
        return typeName != null && typeName.contains("screen_effect");
    }

    /** RenderType 的构造名（26.3 字段 {@code name}，经 {@code RenderTypeAccessor} 读）。 */
    private static String renderTypeName(RenderType renderType) {
        return ((RenderTypeAccessor) (Object) renderType).rtx$getName();
    }

    /**
     * 自带发光的几何。判据是 Sampler0 的贴图路径（这些渲染类型都把发光贴图
     * 挂在 Sampler0 上），命名与 26.3 资源路径一致。
     */
    private static boolean isEmissiveGeometry(Identifier texture) {
        final String path = texture.toString();
        // 2026-10-06 实测修正：末地门的 Sampler1 是
        // minecraft:textures/**entity**/end_portal/end_portal.png（不是
        // environment/end_portal）⇒ 原来那条判据永远不命中，门被当成普通漫反射面，
        // 不发光。改成按子串 end_portal / end_sky 匹配，两个命名变体都覆盖。
        return path.contains("end_sky")
                || path.contains("end_portal")
                || path.contains("end_gateway")
                || path.contains("beacon_beam")
                || path.contains("dragon_beam")
                || path.contains("dragon_fireball")
                || path.contains("guardian_beam");
    }

    private static Identifier resolveSampler0(RenderType renderType) {
        return resolveSampler(renderType, "Sampler0");
    }

    private static Identifier resolveSampler(RenderType renderType, String samplerName) {
        RenderSetup setup = ((RenderTypeAccessor) (Object) renderType).rtx$getState();
        Object binding = ((RenderSetupAccessor) (Object) setup).rtx$getTextures().get(samplerName);
        return binding == null ? null : ((TextureBindingAccessor) binding).rtx$getLocation();
    }

    private static ActiveBatch acquireBatch(
            int textureSlot, boolean localPlayerProxy, boolean emissive) {
        ActiveBatch batch = reusableBatch.get().reset(textureSlot, localPlayerProxy, emissive);
        // 只有模型批次需要共面层序。文字没有"底色 + overlay"这类共面重合，
        // 而 STEP/MAX 是按方块尺寸定的，加到字形上就是错的（见
        // ENTITY_COPLANAR_LAYER_STEP 的说明）。
        batch.coplanarLayering = true;
        return batch;
    }

    private static final class ActiveBatch {
        private int textureSlot;
        private boolean localPlayerProxy;
        private boolean emissive;
        /**
         * 2026-10-06 末地门 / 折跃门：原版几何没有顶点 UV，贴图靠屏幕空间投影
         * （rendertype_end_portal.fsh 的 textureProj）。本批次改为用几何坐标合成 UV。
         */
        private boolean projectiveUv;
        // 诊断用包围盒：只写不读，成本是每个顶点 6 次比较。
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;
        private ByteBuffer buffer = ByteBuffer.allocateDirect(768 * BYTES_PER_VERTEX).order(ByteOrder.nativeOrder());

        // =====================================================
        // 2026-10-17 运动矢量：这一批的**根位姿增量**（3×4，行主序）。
        //
        // 语义：对顶点 p（世界减相机空间），`p_prev = delta · p`。
        // 一个批次 = 一个模型的一次 submit ⇒ 整批共用同一个增量（同一个刚体）。
        // 含平移与整体旋转；**不含** setupAnim 的逐骨动画（见 tracker 说明）。
        //
        // 为什么增量在 beginModel 处算、却在这里逐顶点应用：
        //   · 只有 beginModel 的 submit 里有实体上下文（身份 + 根位姿）；
        //   · 而顶点坐标要到这一批全部捕获完才齐（captureVertex 的栈上没有实体）。
        // 于是"算增量"与"应用增量"必然分在两处，中间靠批次字段传。
        // =====================================================
        private final float[] poseDelta = new float[12];
        private boolean hasPoseDelta;
        /** 平移三元组（诊断/日志用；真正的 MV 由 {@link #poseDelta} 逐顶点算）。 */
        private float motionX, motionY, motionZ;
        /** 位移通道暂存（每顶点 3 个 float）。只在 hasPoseDelta 时才填、才上传。 */
        private ByteBuffer motionScratch;

        private static final int PENDING_VERTEX_FLOATS = 5;
        private final float[] pendingQuadVertices = new float[4 * PENDING_VERTEX_FLOATS];
        private final int[] pendingQuadColors = new int[4];
        private final CapturingVertexConsumer consumer = new CapturingVertexConsumer(this);
        private int pendingQuadVertexCount;
        private int vertexCount;
        /** 本批次内"质心重合"的 quad 重数（量化质心 key → 已出现次数）。见 applyCoplanarLayerOffset。 */
        private final java.util.HashMap<Long, Integer> coplanarQuadCounts = new java.util.HashMap<>();
        /** 本批次是否做共面层序。只有模型批次要（{@link #acquireBatch} 置位），文字批次不做。 */
        private boolean coplanarLayering;

        private ActiveBatch reset(int textureSlot, boolean localPlayerProxy, boolean emissive) {
            this.textureSlot = textureSlot;
            this.localPlayerProxy = localPlayerProxy;
            this.emissive = emissive;
            this.projectiveUv = false;
            this.coplanarLayering = false;
            this.minX = this.minY = this.minZ = Float.MAX_VALUE;
            this.maxX = this.maxY = this.maxZ = -Float.MAX_VALUE;
            buffer.clear();
            pendingQuadVertexCount = 0;
            vertexCount = 0;
            coplanarQuadCounts.clear();
            // 2026-10-09 运动矢量：批次复用 ⇒ 位移必须一起复位。不复位会让"上一批
            // （比如远处静止的实体）"的位移落到本批上 —— 表现是静止物也拖影。
            // 2026-10-17：位姿增量同理会跨批残留，一起复位（并把增量归零而不是
            // 留一份旧矩阵 —— hasPoseDelta 已经挡掉了，但归零能防止将来有人只读矩阵）。
            hasPoseDelta = false;
            java.util.Arrays.fill(poseDelta, 0.0F);
            motionX = motionY = motionZ = 0.0F;
            return this;
        }

        private CapturingVertexConsumer captureConsumer() {
            consumer.reset();
            return consumer;
        }

        private void capture(float x, float y, float z, int color, float u, float v) {
            int vertex = pendingQuadVertexCount++;
            int offset = vertex * PENDING_VERTEX_FLOATS;
            pendingQuadVertices[offset] = x;
            pendingQuadVertices[offset + 1] = y;
            pendingQuadVertices[offset + 2] = z;
            pendingQuadVertices[offset + 3] = u;
            pendingQuadVertices[offset + 4] = v;
            pendingQuadColors[vertex] = color;
            if (pendingQuadVertexCount == 4) {
                if (coplanarLayering) {
                    applyCoplanarLayerOffset();
                }
                emit(0); emit(1); emit(2);
                emit(0); emit(2); emit(3);
                pendingQuadVertexCount = 0;
            }
        }

        /**
         * 共面重合 quad 的稳定层序（见 {@code ENTITY_COPLANAR_LAYER_STEP} 的说明）。
         * 在 4 个顶点凑齐、准备 emit 前调用：质心与先前 quad 重合的，沿自身几何
         * 法线外移 dup × STEP —— 先画的不动，后画的依次前移，平局变确定性。
         * 顶点顺序按原版 CCW（法线朝外）约定，所以外移方向就是"朝观察者那一侧"。
         */
        private void applyCoplanarLayerOffset() {
            final float ax = pendingQuadVertices[0];
            final float ay = pendingQuadVertices[1];
            final float az = pendingQuadVertices[2];
            final float bx = pendingQuadVertices[PENDING_VERTEX_FLOATS];
            final float by = pendingQuadVertices[PENDING_VERTEX_FLOATS + 1];
            final float bz = pendingQuadVertices[PENDING_VERTEX_FLOATS + 2];
            final float cx = pendingQuadVertices[2 * PENDING_VERTEX_FLOATS];
            final float cy = pendingQuadVertices[2 * PENDING_VERTEX_FLOATS + 1];
            final float cz = pendingQuadVertices[2 * PENDING_VERTEX_FLOATS + 2];
            final float dx = pendingQuadVertices[3 * PENDING_VERTEX_FLOATS];
            final float dy = pendingQuadVertices[3 * PENDING_VERTEX_FLOATS + 1];
            final float dz = pendingQuadVertices[3 * PENDING_VERTEX_FLOATS + 2];

            final float e1x = bx - ax, e1y = by - ay, e1z = bz - az;
            final float e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

            final long key = coplanarKey(
                    (ax + bx + cx + dx) * 0.25f,
                    (ay + by + cy + dy) * 0.25f,
                    (az + bz + cz + dz) * 0.25f);
            final Integer previous = coplanarQuadCounts.get(key);
            final int duplicates = previous == null ? 0 : previous;
            coplanarQuadCounts.put(key, duplicates + 1);
            if (duplicates == 0 || length < 1.0e-6f) {
                return;
            }

            final float offset = Math.min(
                    duplicates * ENTITY_COPLANAR_LAYER_STEP, ENTITY_COPLANAR_LAYER_MAX);
            nx = nx / length * offset;
            ny = ny / length * offset;
            nz = nz / length * offset;
            for (int vertex = 0; vertex < 4; ++vertex) {
                final int slot = vertex * PENDING_VERTEX_FLOATS;
                pendingQuadVertices[slot] += nx;
                pendingQuadVertices[slot + 1] += ny;
                pendingQuadVertices[slot + 2] += nz;
            }
        }

        /** 质心量化到 1/256 格的 64 位散列键（重合 quad 量化后必得同键）。 */
        private static long coplanarKey(float x, float y, float z) {
            final long qx = Math.round(x * 256.0f);
            final long qy = Math.round(y * 256.0f);
            final long qz = Math.round(z * 256.0f);
            long hash = qx * 2654435761L;
            hash = hash * 31L + qy * 2246822519L;
            hash = hash * 31L + qz * 3266489917L;
            return hash;
        }

        private void emit(int vertex) {
            ensureCapacity(BYTES_PER_VERTEX);
            int offset = vertex * PENDING_VERTEX_FLOATS;
            int color = pendingQuadColors[vertex];
            buffer.putFloat(pendingQuadVertices[offset]).putFloat(pendingQuadVertices[offset + 1])
                    .putFloat(pendingQuadVertices[offset + 2]).putFloat(pendingQuadVertices[offset + 3])
                    .putFloat(pendingQuadVertices[offset + 4]);
            buffer.putFloat(((color >>> 16) & 0xFF) / 255.0f).putFloat(((color >>> 8) & 0xFF) / 255.0f);
            buffer.putFloat((color & 0xFF) / 255.0f).putFloat(((color >>> 24) & 0xFF) / 255.0f);
            // 必须对齐原生 Vertex ABI: materialId(9), pad1(10), pad2(11)。
            // 曾把 proxy 标记误写在 index 9(materialId 位),原生端读 pad1 时
            // 拿到的是 emissive 标记,代理批次全部漏进普通实体层导致脸挡屏。
            buffer.putFloat(0.0f)
                    .putFloat(localPlayerProxy ? 1.0f : emissive ? -1.0f : 0.0f)
                    .putFloat(encodedTextureSlot());
            ++vertexCount;
            // 诊断包围盒（对照相机位置就能判断一批几何落在哪，见 endModel 的打印）。
            final float px = pendingQuadVertices[offset];
            final float py = pendingQuadVertices[offset + 1];
            final float pz = pendingQuadVertices[offset + 2];
            if (px < minX) minX = px;
            if (py < minY) minY = py;
            if (pz < minZ) minZ = pz;
            if (px > maxX) maxX = px;
            if (py > maxY) maxY = py;
            if (pz > maxZ) maxZ = pz;
        }

        // Slot plus the material class in the fraction, so the closest-hit
        // shader can pick a real F0/roughness for armour with no ABI change.
        // Slot 0 stays exactly 0: that value is what marks a vertex as "not
        // entity geometry" in every shader that reads pad2.
        private float encodedTextureSlot() {
            if (textureSlot < 1 || textureSlot > MAX_TEXTURE_SLOTS) {
                return 0.0f;
            }


            final int materialClass =
                    frameTextureClasses[textureSlot - 1];


            return materialClass <= 0
                    ? textureSlot
                    : textureSlot + materialClass / MATERIAL_CLASS_SCALE;
        }

        private void ensureCapacity(int extra) {
            if (buffer.remaining() >= extra) return;
            int target = buffer.position() + extra;
            int capacity = buffer.capacity();
            while (capacity < target) capacity = Math.multiplyExact(capacity, 2);
            ByteBuffer replacement = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            buffer.flip(); replacement.put(buffer); buffer = replacement;
        }
    }

    private static final class CapturingVertexConsumer implements VertexConsumer {
        private final ActiveBatch batch;
        private boolean hasVertex;
        private float x, y, z, u, v;
        private int color = 0xFFFFFFFF;

        private CapturingVertexConsumer(ActiveBatch batch) { this.batch = batch; }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            this.x = x; this.y = y; this.z = z;
            this.color = 0xFFFFFFFF; this.u = 0.0f; this.v = 0.0f;
            hasVertex = true;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            color = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
            return this;
        }
        @Override public VertexConsumer setColor(int color) { this.color = color; return this; }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float lineWidth) { return this; }
        private void finish() { flush(); }
        private void reset() { hasVertex = false; }
        private void flush() {
            if (hasVertex) {
                // 投影 UV 批次（末地门）：原版几何没有顶点 UV，改用顶点世界坐标合成，
                // 与 captureVertex 的短格式路径同一公式/缩放，相邻方块因此连续。
                final float cu;
                final float cv;
                if (batch.projectiveUv) {
                    // 顶点是"世界减相机"⇒ 加回相机原点，贴图才会钉在世界里
                    //（否则随相机平移，见 setCameraOrigin）。
                    final float wx = x + cameraWorldX;
                    final float wy = y + cameraWorldY;
                    final float wz = z + cameraWorldZ;
                    cu = fract01(wx * 0.25f);
                    cv = fract01(wz * 0.25f + wy * 0.125f);
                } else {
                    cu = u;
                    cv = v;
                }
                batch.capture(x, y, z, color, cu, cv);
            }
            hasVertex = false;
        }
    }

}
