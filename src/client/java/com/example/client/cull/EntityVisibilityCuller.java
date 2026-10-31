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
    

    private static final boolean CULL_OUTSIDE_FRUSTUM = false;
    

    private static final double CAMERA_SYNC_ENTER_BLOCKS = 32.0D;
    private static final double CAMERA_SYNC_EXIT_BLOCKS = 20.0D;

    private static final AtomicBoolean enabled = new AtomicBoolean(resolveEnabled());
    private static final AtomicBoolean available = new AtomicBoolean(false);
    private static final AtomicReference<Batch> pending = new AtomicReference<>(Batch.EMPTY);
    private static final AtomicReference<IntOpenHashSet> culled =
            new AtomicReference<>(new IntOpenHashSet());
    private static final Set<Integer> forcedVisibleIds = ConcurrentHashMap.newKeySet();

    private static OcclusionCullingInstance culling;
    private static OrionVoxelDataProvider provider;
    private static Thread worker;

    private static int frameCounter;
    private static volatile int levelEpoch;
    private static ClientLevel lastLevel;

    private static volatile boolean requestCull = true;
    private static volatile double cameraX;
    private static volatile double cameraY;
    private static volatile double cameraZ;
    private static volatile boolean cameraValid;
    private static volatile int lastConsidered;
    private static volatile int lastCulled;

    private static double gateLastX = Double.NaN;
    private static double gateLastY = Double.NaN;
    private static double gateLastZ = Double.NaN;
    private static boolean gateValid;
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
            lastLevel = null;
            pending.set(Batch.EMPTY);
            culled.set(new IntOpenHashSet());
            cameraValid = false;
            return;
        }
        if(level != lastLevel) {
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

        if(fastCameraMove) {
            pending.set(new Batch(Collections.emptyList(), levelEpoch));
            requestCull = true;
            culled.set(new IntOpenHashSet());
            lastConsidered = 0;
            lastCulled = 0;
            return;
        }

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
        if(entity == minecraft.player
                || entity == camera.entity()
                || entity.isPassenger()
                || entity.isVehicle()
                || entity instanceof AbstractMinecart
                || forcedVisibleIds.contains(entity.getId())) {
            return null;
        }
        if(minecraft.shouldEntityAppearGlowing(entity)) {
            return null;
        }
        if(entity.isInvisible()) {
            return null;
        }
        final EntityRenderer<? super Entity, ?> renderer =
                minecraft.getEntityRenderDispatcher().getRenderer(entity);
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
            final double x = entity.getX();
            final double y = entity.getY();
            final double z = entity.getZ();
            box = new AABB(x - 2.0D, y - 2.0D, z - 2.0D, x + 2.0D, y + 2.0D, z + 2.0D);
        }
        final boolean inFrustum = CULL_OUTSIDE_FRUSTUM || frustum == null || frustum.isVisible(box);
        return new Candidate(entity.getId(), box, entity.position(), inFrustum);
    }

    private static void runLoop() {
        final Vec3d camera = new Vec3d(0, 0, 0);
        final Vec3d aabbMin = new Vec3d(0, 0, 0);
        final Vec3d aabbMax = new Vec3d(0, 0, 0);
        final Int2LongOpenHashMap lastVisibleAt = new Int2LongOpenHashMap();
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
                    if(now - lastVisibleAt.get(candidate.id) < VISIBLE_HOLD_MS) {
                        continue;
                    }
                    final double dx = candidate.posX - camX;
                    final double dy = candidate.posY - camY;
                    final double dz = candidate.posZ - camZ;
                    if(dx * dx + dy * dy + dz * dz > REACH_SQ) {
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
                    if(!candidate.inFrustum) {
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
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
                        lastVisibleAt.put(candidate.id, now);
                        continue;
                    }
                    if(visible) {
                        lastVisibleAt.put(candidate.id, now);
                    } else {
                        hidden.add(candidate.id);
                    }
                }
                culled.set(hidden);
                lastConsidered = candidates.size();
                lastCulled = hidden.size();
                prune(lastVisibleAt, now);
            } catch(Throwable failure) {
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
