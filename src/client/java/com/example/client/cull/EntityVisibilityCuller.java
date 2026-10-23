package com.example.client.cull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.client.cull.util.Vec3d;
import com.example.client.mixin.EntityRendererCullAccess;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实体**遮挡剔除**（Orion 版），2026-10-20 按 .3 重写编排层。
 *
 * <p>来源与授权：判定算法来自
 * <a href="https://github.com/LogisticsCraft/OcclusionCulling">LogisticsCraft/OcclusionCulling</a>，
 * 编排策略来自 <a href="https://github.com/tr7zw/">tr7zw/</a>
 * {@code CullTask} / {@code EntityCullingModBase.extractEntityData} / {@code NMSCullingHelper}
 * / {@code CullableMixin}：
 *
 * <ul>
 *   <li><b>每轮 resetCache()</b>：{@code ArrayOcclusionCache} 的索引是**相机相对**的
 *       （{@code x - cameraPos + reach}）。旧版本从不调用 {@code resetCache()}，相机一走
 *   <li><b>只在"世界 tick 或相机位移"时重算</b>（CullTask:60-61），不是定频空转。</li>
 *   <li><b>1 秒强制可见窗口</b>（CullableMixin:33-48 {@code setTimeout()}）：刚被判为可见的
 *       实体在 1s 内不再复测。既防异步结果的闪烁，又把可见实体的复测频率压到 1Hz。</li>
 *   <li><b>剔除盒来自 renderer</b>（NMSCullingHelper:34-55）：{@code EntityRenderer
 *       .getBoundingBoxForCulling}，不是 {@code entity.getBoundingBox()}。鱼线、末影龙部件、
 *       展示框的可见部分都超出碰撞箱；NaN/零尺寸（marker 盔甲架、display 实体）用原版
 *       {@code EntityRenderer.shouldRender} 同款的 2 格兜底盒。</li>
 *   <li><b>排除项</b>（ClientWorldMixin:46-52）：玩家、相机实体、乘客/载具、矿车、
 *       发光实体、{@code !affectedByCulling} 的 renderer、无 renderer 的实体一律不剔。</li>
 *   <li><b>超大箱子放行</b>（hitboxLimit=50，CullTask:111-116）。</li>
 *   <li><b>超出追踪距离放行</b>（tracingDistance=128，CullTask:105-110）。</li>
 *   <li><b>相机高速移动闸门</b>：逐帧取三轴最大位移，≥32 格释放全部结果、≤20 格恢复
 *       （照抄 Sodium {@code AsyncCameraTimingControl} 的 32/20 迟滞）。</li>
 * </ul>
 *
 * <p><b>消费点</b>：判定结果写在 {@code EntityRenderState} 上的标记里
 * （见 {@link CullableRenderState}），由
 * {@code ModelFeatureRendererMixin#rtx$beginModelCapture} 读取并跳过该实体的模型几何。
 * 这条链路是生产版（{@code run\} + 默认 shader set）的实体几何唯一入口。
 *
 * <p><b>⚠ 光追特有取舍</b>：遮挡算法对**相机背后/视锥外**的箱子也会
 * 返回"不可见"（所有朝相机的面都被挡）。主光线无影响，但实体的阴影/GI 贡献会一并消失 ——
 *
 * <p><b>自创：视锥外不剔（2026-10-20，用户要求）</b>。依据是上面的取舍：光追下"视锥外"不等于
 * "没有贡献"（会投影、会参与 GI），只有"在视锥内且被不透明几何挡住"才两个条件同时成立。
 * 所以 {@code capture()} 里用原版 {@code Camera.getCullFrustum()} 做一次
 * {@code Frustum.isVisible(box)}（与 {@code EntityRenderer.shouldRender} 同款判据），
 * 视锥外的实体直接记成"可见"，不进入遮挡射线。代价是每 5 帧多一遍视锥测试（O(实体数)，
 * 相当于主线程已有的 AABB×6 平面裁剪），收益是背后实体的阴影/GI 保住。
 *
 * <p>线程模型：主线程每 {@link #CAPTURE_FRAMES} 帧采一次候选（实体 + 剔除盒 + 相机），
 * 交给守护线程；守护线程每 {@link #SLEEP_MS}ms 醒来一次，仅在"有请求或相机位移"时重算，
 * 结果是一份**整轮替换**的不可变 id 集合 —— 渲染线程只做一次 volatile 读 + O(1) 查表，
 * 无锁、无陈旧条目（旧版的 map 只在候选里出现才 remove，实体卸载后 id 复用会误剔）。
 */
public final class EntityVisibilityCuller {

    private static final int REACH_BLOCKS = 128;
    private static final double REACH_SQ = (double)REACH_BLOCKS * (double)REACH_BLOCKS;
    private static final int SLEEP_MS = 10;
    private static final int CAPTURE_FRAMES = 5;
    private static final double HITBOX_LIMIT = 50.0D;
    private static final long VISIBLE_HOLD_MS = 1000L;
    /**
     * 是否允许剔除"视锥外"的实体。{@code false} = 自创的保守档（光追保阴影/GI）：
     */
    private static final boolean CULL_OUTSIDE_FRUSTUM = false;
    /**
     * 相机高速移动闸门，**照抄 Sodium**：
     * {@code sodium/client/render/chunk/occlusion/AsyncCameraTimingControl.java:8-10}
     * （{@code ENTER_SYNC_STEP_THRESHOLD = 32}, {@code EXIT_SYNC_STEP_THRESHOLD = 20}）。
     *
     * <p>Sodium 用它决定"区块可见性图这一帧走同步还是异步"；我们用同一判据决定
     * "这份异步剔除结果还可不可信"：单帧位移 ≥32 格（传送、旁观加速、鞘翅俯冲）时，
     * 采集快照与真实相机已经对不上，继续用只会错剔 ⇒ 释放全部结果；位移回落到 ≤20 格
     * 才恢复。两个阈值成迟滞带，避免在阈值附近来回切。</p>
     */
    private static final double CAMERA_SYNC_ENTER_BLOCKS = 32.0D;
    private static final double CAMERA_SYNC_EXIT_BLOCKS = 20.0D;

    private static final AtomicBoolean enabled = new AtomicBoolean(resolveEnabled());
    private static final AtomicBoolean available = new AtomicBoolean(false);
    /** 主线程写、守护线程读：本轮的候选快照。 */
    private static final AtomicReference<Batch> pending = new AtomicReference<>(Batch.EMPTY);
    /** 守护线程写、渲染线程读：本轮被判为不可见的实体 id。整轮替换，永不残留。 */
    private static final AtomicReference<IntOpenHashSet> culled =
            new AtomicReference<>(new IntOpenHashSet());
    private static final Set<Integer> forcedVisibleIds = ConcurrentHashMap.newKeySet();

    private static OcclusionCullingInstance culling;
    private static OrionVoxelDataProvider provider;
    private static Thread worker;

    /** 主线程：帧序号（采集节奏）。 */
    private static int frameCounter;
    /** 主线程写、守护线程读：世界实例身份变化的代数，防止旧世界的判定结果落到新世界。 */
    private static volatile int levelEpoch;
    private static ClientLevel lastLevel;

    /** 守护线程消费请求（世界 tick / 新采集）。 */
    private static volatile boolean requestCull = true;
    private static volatile double cameraX;
    private static volatile double cameraY;
    private static volatile double cameraZ;
    private static volatile boolean cameraValid;
    /** 调试读数。 */
    private static volatile int lastConsidered;
    private static volatile int lastCulled;

    // 主线程私有：相机位移闸门状态（每帧更新，见 updateCameraMoveGate）。
    private static double gateLastX = Double.NaN;
    private static double gateLastY = Double.NaN;
    private static double gateLastZ = Double.NaN;
    private static boolean gateValid;
    /** true = 相机正在高速移动，本帧不做剔除（全部可见）。 */
    private static boolean fastCameraMove;

    private EntityVisibilityCuller() {}

    /** 一轮候选快照。{@code epoch} 见 {@link #levelEpoch}。 */
    private static final class Batch {
        static final Batch EMPTY = new Batch(Collections.emptyList(), 0);
        final List<Candidate> candidates;
        final int epoch;

        Batch(List<Candidate> candidates, int epoch) {
            this.candidates = candidates;
            this.epoch = epoch;
        }
    }

    /** 单个候选的**不可变**快照（主线程采集后不再触碰实体）。 */
    private static final class Candidate {
        final int id;
        final double minX, minY, minZ, maxX, maxY, maxZ;
        final double posX, posY, posZ;
        /** 采集时这一帧是否落在相机视锥内（见 {@link #CULL_OUTSIDE_FRUSTUM}）。 */
        final boolean inFrustum;

        Candidate(int id, AABB box, Vec3 position, boolean inFrustum) {
            this.id = id;
            this.minX = box.minX;
            this.minY = box.minY;
            this.minZ = box.minZ;
            this.maxX = box.maxX;
            this.maxY = box.maxY;
            this.maxZ = box.maxZ;
            this.posX = position.x;
            this.posY = position.y;
            this.posZ = position.z;
            this.inFrustum = inFrustum;
        }

        double sizeX() { return maxX - minX; }
        double sizeY() { return maxY - minY; }
        double sizeZ() { return maxZ - minZ; }
    }

    private static boolean resolveEnabled() {
 // 应急总闸：-Drtx.=0（与本工程其它调试开关同风格）。
        return !"0".equals(System.getProperty("rtx.entityCulling", "1"));
    }

    /**
     * 启动（幂等；任何失败都只记日志并保持"不可用"=不剔除）。
     *
     * <p>2026-10-20：删掉了旧的 {@code WIRED_TO_RENDERER} 硬门 —— 判定结果现在真的被
     * 渲染消费，且 {@code resetCache()} 已按轮调用，链路不再是纯 CPU 损耗。</p>
     */
    public static synchronized void install() {
        if(worker != null) {
            return;
        }
        try {
            provider = new OrionVoxelDataProvider();
            culling = new OcclusionCullingInstance(REACH_BLOCKS, provider);
            worker = new Thread(EntityVisibilityCuller::runLoop, "Orion-CullThread");
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((thread, error) -> {
                System.err.println("[ORION CULL] worker crashed, culling disabled: " + error);
                available.set(false);
                culled.set(new IntOpenHashSet());
            });
            worker.start();
            available.set(true);
            System.out.println("[ORION CULL] entity visibility culler started (reach="
                    + REACH_BLOCKS + " blocks, sleep=" + SLEEP_MS + "ms, capture="
                    + CAPTURE_FRAMES + " frames, enabled=" + enabled.get() + ")");
        } catch(Throwable failure) {
            available.set(false);
            System.err.println("[ORION CULL] install failed, culling disabled: " + failure);
        }
    }

    /** A/B 开关（默认开；关掉即"全部提交"，不会有任何实体被剔）。 */
    public static void setEnabled(boolean value) {
        enabled.set(value);
    }

    /** 链路是否真的在剔除（消费点用它做一次早退）。 */
    public static boolean isCullingActive() {
        return enabled.get() && available.get();
    }

    public static boolean isEnabled() {
        return isCullingActive();
    }

    /** 调试读数：最近一轮的候选数与被剔数。 */
    public static int lastConsideredCount() {
        return lastConsidered;
    }

    public static int lastCulledCount() {
        return lastCulled;
    }

    /** 该实体本帧是否应跳过模型几何（缺省 = 不该，即保持可见）。 */
    public static boolean isCulled(int entityId) {
        if(!isCullingActive()) {
            return false;
        }
        return culled.get().contains(entityId);
    }

    public static boolean isCulled(Entity entity) {
        return entity != null && isCulled(entity.getId());
    }

    /** 临时强制某个实体可见（1 秒后自动失效以外的调用者需自行重复调用）。 */
    public static void forceVisible(int entityId) {
        forcedVisibleIds.add(entityId);
    }

    /** 主线程：每帧调用一次（内部按 {@link #CAPTURE_FRAMES} 自节流）。 */
    public static void tick() {
        if(!isCullingActive()) {
            return;
        }
        // 位移闸门逐帧更新（与 Sodium 一致），采集本身仍按 CAPTURE_FRAMES 节流。
        final Minecraft minecraft = Minecraft.getInstance();
        final Camera camera = minecraft.gameRenderer == null
                ? null
                : minecraft.gameRenderer.mainCamera();
        updateCameraMoveGate(camera);
        if(++frameCounter % CAPTURE_FRAMES != 0) {
            return;
        }
        try {
            captureRound(minecraft, camera);
        } catch(Throwable failure) {
            // 策略 3：采集失败一律退回"全部可见"，绝不让剔除链路把渲染线程带走。
            if(!captureFailureLogged) {
                captureFailureLogged = true;
                System.err.println("[ORION CULL] capture failed, releasing all: " + failure);
            }
            pending.set(Batch.EMPTY);
            culled.set(new IntOpenHashSet());
        }
    }

    private static boolean captureFailureLogged;
    private static boolean accessorFailureLogged;

    /**
     * 相机位移闸门（主线程，逐帧）。判据与阈值**照抄 Sodium**
     * {@code AsyncCameraTimingControl}：取三轴最大位移，≥32 格进"高速档"（放弃异步结果），
     * ≤20 格才退出；两个阈值成迟滞带，避免在阈值附近来回切换。
     */
    private static void updateCameraMoveGate(Camera camera) {
        if(camera == null) {
            gateValid = false;
            return;
        }
        final Vec3 position = camera.position();
        if(!gateValid) {
            gateLastX = position.x;
            gateLastY = position.y;
            gateLastZ = position.z;
            gateValid = true;
            fastCameraMove = false;
            return;
        }
        final double step = Math.max(Math.abs(position.x - gateLastX),
                Math.max(Math.abs(position.y - gateLastY), Math.abs(position.z - gateLastZ)));
        if(fastCameraMove && step <= CAMERA_SYNC_EXIT_BLOCKS) {
            fastCameraMove = false;
            System.out.println("[ORION CULL] camera settled, resuming culling (step="
                    + Math.round(step * 100.0) / 100.0 + ")");
        } else if(!fastCameraMove && step >= CAMERA_SYNC_ENTER_BLOCKS) {
            fastCameraMove = true;
            System.out.println("[ORION CULL] camera fast-move, releasing all (step="
                    + Math.round(step * 100.0) / 100.0 + ")");
        }
        gateLastX = position.x;
        gateLastY = position.y;
        gateLastZ = position.z;
    }

    private static void captureRound(Minecraft minecraft, Camera camera) {
        final ClientLevel level = minecraft.level;
        if(level == null || minecraft.player == null || minecraft.player.tickCount <= 10) {
            // 不在世界里：清空结果，避免残留判定把新世界的实体误剔。
            lastLevel = null;
            pending.set(Batch.EMPTY);
            culled.set(new IntOpenHashSet());
            cameraValid = false;
            return;
        }
        if(level != lastLevel) {
            // 换维/换世界：id 命名空间可能复用，作废本轮所有结果。
            lastLevel = level;
            ++levelEpoch;
            culled.set(new IntOpenHashSet());
        }

        if(camera == null) {
            cameraValid = false;
            culled.set(new IntOpenHashSet());
            return;
        }
        final Vec3 cameraPos = camera.position();
        cameraX = cameraPos.x;
        cameraY = cameraPos.y;
        cameraZ = cameraPos.z;
        cameraValid = true;

        // 相机高速移动闸门（Sodium AsyncCameraTimingControl 同款 32/20 迟滞）：
        // 位移过大的帧，异步判定与真实相机已经对不上 ⇒ 作废整轮结果、全部可见。
        // 相机快照仍然更新、requestCull 仍然置位，让剔除线程用空批次自然收尾，
        // 不会把上一轮的集合继续沿用下去。
        if(fastCameraMove) {
            pending.set(new Batch(Collections.emptyList(), levelEpoch));
            requestCull = true;
            culled.set(new IntOpenHashSet());
            lastConsidered = 0;
            lastCulled = 0;
            return;
        }

        // 自创（光追保阴影）：视锥判定必须在主线程做 —— Frustum 是相机持有的可变对象，
        // 跨线程读会和下一帧的 prepareCullFrustum()/offset() 撞车。这里只把结果布尔量带走。
        final Frustum frustum = camera.getCullFrustum();

        final List<Candidate> candidates = new ArrayList<>();
        for(Entity entity : level.entitiesForRendering()) {
            final Candidate candidate = capture(entity, minecraft, camera, frustum);
            if(candidate != null) {
                candidates.add(candidate);
            }
        }
        pending.set(new Batch(candidates, levelEpoch));
        requestCull = true;

        if(frameCounter % 600 == 0 && lastCulled > 0) {
            System.out.println("[ORION CULL] considered=" + lastConsidered
                    + " culled=" + lastCulled);
        }
        for(Integer id : new ArrayList<>(forcedVisibleIds)) {
            forcedVisibleIds.remove(id);
        }
    }

    /**
     * 主线程：把实体归一成候选，返回 null 表示"这个实体不参与剔除"。
     *
     * {@code ClientWorldMixin} 里挡掉永不剔的对象，再取剔除盒。</p>
     */
    private static Candidate capture(Entity entity, Minecraft minecraft, Camera camera, Frustum frustum) {
        if(entity == null) {
            return null;
        }
        // 策略 2：玩家 / 相机实体 / 载具与乘客 / 矿车永不剔。
        // 我们还有本地玩家身体代理（LevelRendererMixin 合成提交），误剔就是"自己不见了"。
        if(entity == minecraft.player
                || entity == camera.entity()
                || entity.isPassenger()
                || entity.isVehicle()
                || entity instanceof AbstractMinecart
                || forcedVisibleIds.contains(entity.getId())) {
            return null;
        }
        // 发光（队伍/被敌人标记）实体不剔。
        if(minecraft.shouldEntityAppearGlowing(entity)) {
            return null;
        }
        // 完全隐形的实体本来就不提交模型几何，没什么可剔的。
        if(entity.isInvisible()) {
            return null;
        }
        final EntityRenderer<? super Entity, ?> renderer =
                minecraft.getEntityRenderDispatcher().getRenderer(entity);
        // instanceof 而不是强转：万一 mixin 没被应用，这里只是"不剔"，不会把渲染线程打崩。
        if(!(renderer instanceof EntityRendererCullAccess access)) {
            if(!accessorFailureLogged) {
                accessorFailureLogged = true;
                System.err.println("[ORION CULL] EntityRendererCullAccess missing, culling disabled");
            }
            return null;
        }
        if(!access.orion$affectedByCulling(entity)) {
            return null;
        }

        AABB box = access.orion$getBoundingBoxForCulling(entity, 0.0F);
        if(box == null) {
            return null;
        }
        if(box.hasNaN() || box.getSize() == 0.0D) {
            // 原版 EntityRenderer.shouldRender 的同款兜底：marker 盔甲架、display 实体的
            // 剔除盒是 0 尺寸，给一个 2 格盒。
            final double x = entity.getX();
            final double y = entity.getY();
            final double z = entity.getZ();
            box = new AABB(x - 2.0D, y - 2.0D, z - 2.0D, x + 2.0D, y + 2.0D, z + 2.0D);
        }
        // 自创：视锥外不剔（见类注释与 CULL_OUTSIDE_FRUSTUM）。判据与
        // EntityRenderer.shouldRender 一致：剔除盒（同上兜底）对相机视锥求交。
        final boolean inFrustum = CULL_OUTSIDE_FRUSTUM || frustum == null || frustum.isVisible(box);
        return new Candidate(entity.getId(), box, entity.position(), inFrustum);
    }

    private static void runLoop() {
        final Vec3d camera = new Vec3d(0, 0, 0);
        final Vec3d aabbMin = new Vec3d(0, 0, 0);
        final Vec3d aabbMax = new Vec3d(0, 0, 0);
        // 守护线程私有：最近一次被判为可见的时间（1s 迟滞）。其余线程不读。
        final Int2LongOpenHashMap lastVisibleAt = new Int2LongOpenHashMap();
        // 缺省 0 = "很久以前"：未记录过的实体第一轮就会被正常判定（不能用 Long.MIN_VALUE，
        // 否则 now - MIN_VALUE 会溢出成负数，所有实体都会被误当成"刚判过可见"）。
        lastVisibleAt.defaultReturnValue(0L);

        double lastCameraX = Double.NaN;
        double lastCameraY = Double.NaN;
        double lastCameraZ = Double.NaN;

        while(true) {
            try {
                Thread.sleep(SLEEP_MS);
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if(!enabled.get()) {
                    culled.set(new IntOpenHashSet());
                    continue;
                }
                final double camX = cameraX;
                final double camY = cameraY;
                final double camZ = cameraZ;
                final boolean cameraMoved = camX != lastCameraX || camY != lastCameraY
                        || camZ != lastCameraZ;
                if(!requestCull && !cameraMoved) {
                    continue;
                }
                requestCull = false;
                if(!cameraValid) {
                    culled.set(new IntOpenHashSet());
                    continue;
                }
                lastCameraX = camX;
                lastCameraY = camY;
                lastCameraZ = camZ;

                final Batch batch = pending.get();
                if(batch.epoch != levelEpoch) {
                    // 采集后换过世界：这份快照作废（主线程已清空结果）。
                    continue;
                }
                final List<Candidate> candidates = batch.candidates;
                if(candidates.isEmpty()) {
                    culled.set(new IntOpenHashSet());
                    lastConsidered = 0;
                    lastCulled = 0;
                    continue;
                }

                culling.resetCache();
                camera.set(camX, camY, camZ);

                final long now = System.currentTimeMillis();
                final IntOpenHashSet hidden = new IntOpenHashSet(Math.max(16, candidates.size() / 4));
                for(Candidate candidate : candidates) {
                    // 策略 2 的运行时版本：1s 强制可见窗口（CullableMixin.setTimeout）。
                    if(now - lastVisibleAt.get(candidate.id) < VISIBLE_HOLD_MS) {
                        continue;
                    }
                    // 策略 1：超出追踪距离不剔（CullTask:105-110），并刷新可见窗口。
                    final double dx = candidate.posX - camX;
                    final double dy = candidate.posY - camY;
                    final double dz = candidate.posZ - camZ;
                    if(dx * dx + dy * dy + dz * dz > REACH_SQ) {
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
                    // 自创：视锥外不剔（光追下仍会投影/参与 GI）。采集时已算好，这里只读布尔。
                    if(!candidate.inFrustum) {
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
                    // 超大箱子放行（CullTask:111-116），同样刷新可见窗口。
                    if(candidate.sizeX() > HITBOX_LIMIT || candidate.sizeY() > HITBOX_LIMIT
                            || candidate.sizeZ() > HITBOX_LIMIT) {
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
                    aabbMin.set(candidate.minX, candidate.minY, candidate.minZ);
                    aabbMax.set(candidate.maxX, candidate.maxY, candidate.maxZ);
                    final boolean visible;
                    try {
                        visible = culling.isAABBVisible(aabbMin, aabbMax, camera);
                    } catch(Throwable failure) {
                        // 策略 3：异常按可见处理。
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
                    if(visible) {
                        lastVisibleAt.put(candidate.id, now);
                    } else {
                        hidden.add(candidate.id);
                    }
                }
                // 整轮替换：不在本轮集合里的实体一律可见（含已卸载的实体）。
                culled.set(hidden);
                lastConsidered = candidates.size();
                lastCulled = hidden.size();
                prune(lastVisibleAt, now);
            } catch(Throwable failure) {
                // 策略 3：任何异常都退回"全部可见"，绝不凭空消失。
                System.err.println("[ORION CULL] round failed, releasing all: " + failure);
                culled.set(new IntOpenHashSet());
            }
        }
    }

    /** 迟滞表定期瘦身，避免实体长期离开视野后表无限增长。 */
    private static void prune(Int2LongOpenHashMap lastVisibleAt, long now) {
        if(lastVisibleAt.size() < 512) {
            return;
        }
        lastVisibleAt.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() >= VISIBLE_HOLD_MS);
    }
}
