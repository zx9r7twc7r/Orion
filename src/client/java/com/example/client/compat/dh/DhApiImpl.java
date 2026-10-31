package com.example.client.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiFogConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * DH 接入的观察层（P0 探针）与生命周期中枢。
 *
 * <h2>DH 接触面的边界（P1 起放宽了一个粒度，改动前务必先读）</h2>
 * <p>硬约束不变：<b>DH 类型只允许出现在包 {@code com.example.client.compat.dh} 内</b>，
 * 包外的任何类（含 {@link DhBridge}）都不得 import，否则未装 DH 的玩家会在类加载阶段
 * 拿到 {@link NoClassDefFoundError}。当前包内直接 import 了 DH 的有：</p>
 * <ul>
 *   <li><b>本类</b>——事件注册、能力矩阵、句柄探测（Q1/Q2/Q5/Q7/Q8/Q10）；</li>
 *   <li>{@code com.example.client.compat.dh.mixin.DhLodBufferHookMixin}——DH LOD
 *       顶点缓冲的接管点（由 {@code DhMixinPlugin} 门控，未装 DH 时连加载都不发生）。</li>
 * </ul>
 * <p>两者都由 {@link DhBridge} 在"DH 已安装 + API 版本达标"之后才首次触碰。</p>
 *
 * <h2>本类要回答的问题（对应规划文档的 Q1–Q10）</h2>
 * <ul>
 *   <li>Q1 DH 实际用哪个渲染引擎（决定"句柄直通"路线是否可行）</li>
 *   <li>Q2 各项配置能否被 API 覆盖（决定能否关掉 DH 自身渲染与雾）</li>
 *   <li>Q5 {@code DhApiAfterDhInitEvent} 的到达时序</li>
 *   <li>Q7 color/depth/blockRatio 句柄能否拿到可直通的底层对象（<b>必须等世界里首帧之后</b>）</li>
 *   <li>Q8 DH 渲染相对我们自己的 RT 帧提交是"同帧之前"还是"晚一帧"</li>
 *   <li>Q10 雾配置全值（远场雾所有权）</li>
 * </ul>
 *
 * <p>关闭探针：{@code -Dorion.dh.probe=false}。开关集中在 {@link DhFlags}。</p>
 *
 * <h2>已知两个坑（都已修，勿回退）</h2>
 * <ol>
 *   <li><b>不要用 {@code Long.MIN_VALUE} 当"尚未探测过"的哨兵并参与减法</b>——
 *       {@code frame - Long.MIN_VALUE} 在 long 上溢出成负数，会让"距上次探测是否够久"
 *       的判定恒为真，探针被永久挡住。改用 {@code nextXxxFrame} 正向比较。</li>
 *   <li><b>句柄不能只在第一帧查一次</b>——DH 的 color/depth 纹理要等世界里真正渲染过
 *       才会创建，首帧查必然拿到 "hasn't been created and/or bound yet"。必须重探至成功。</li>
 * </ol>
 */
final class DhApiImpl {
    private static final String TAG = "[DH]";

    private static final boolean PROBE_ENABLED = DhFlags.PROBE;
    private static final boolean DISABLE_DH_RENDERING = DhFlags.DISABLE_DH_RENDERING;

    private static final long HANDLE_PROBE_INTERVAL_FRAMES = 30L;
    private static final int MAX_HANDLE_PROBES = 60;

    private static final long NEAR_CLIP_PROBE_INTERVAL_FRAMES = 120L;

    private static final int MAX_RENDER_EVENT_LOGS = 8;

    private static final int MAX_DUMP_DEPTH = 3;
    private static final int MAX_DUMP_MEMBERS = 16;

    private static boolean ready;
    private static boolean ownsNearClipOverride;
    private static boolean ownsAtmosphereOverride;
    

    private static boolean atmosphereAttempted;
    

    private static boolean ownsRendering;
    private static Boolean ownsRenderingValue;
    private static boolean renderingOwnershipAttempted;
    private static Object lastLevelIdentity;

    private static boolean handlesReady;
    private static int handleProbes;
    private static long nextHandleProbeFrame;
    private static long nextNearClipProbeFrame;
    private static float lastNearClipBlocks = Float.NaN;

    private static int beforeRenderLogs;
    private static int afterRenderLogs;

    private DhApiImpl() {
    }


    static int apiMajor() {
        return DhApi.getApiMajorVersion();
    }

    static String apiVersion() {
        return DhApi.getApiMajorVersion() + "." + DhApi.getApiMinorVersion() + "." + DhApi.getApiPatchVersion();
    }

    static String modVersion() {
        return DhApi.getModVersion();
    }


    static void install() {
        try {
            DhApiResult<Void> result = DhApiEventRegister.on(DhApiAfterDhInitEvent.class,
                    new DhApiAfterDhInitEvent() {
                        @Override
                        public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
                            log("收到 DhApiAfterDhInitEvent（frame=" + DhBridge.frame() + "）"
                                    + " ⇒ Delayed.* 此刻应已可安全访问");
                        }
                    });
            log("注册 DhApiAfterDhInitEvent：success=" + result.success + " message=\"" + result.message + "\"");
        } catch (Throwable failure) {
            log("注册 DhApiAfterDhInitEvent 失败：" + failure);
        }

        try {
            DhApiResult<Void> before = DhApiEventRegister.on(DhApiBeforeRenderEvent.class,
                    new DhApiBeforeRenderEvent() {
                        @Override
                        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                            DhFarFieldGeometry.push(event.value);

                            if (beforeRenderLogs++ >= MAX_RENDER_EVENT_LOGS) return;
                            DhApiRenderParam param = event.value;
                            log("[beforeRender #" + beforeRenderLogs + "] 我们帧计数=" + DhBridge.frame()
                                    + " 我们自己这帧的 RT 提交是否已结束=" + DhBridge.frameEnded()
                                    + " pass=" + param.renderPass
                                    + " partialTicks=" + param.partialTicks
                                    + " near=" + param.nearClipPlane + " far=" + param.farClipPlane
                                    + " worldYOffset=" + param.worldYOffset);
                            if (beforeRenderLogs == 1) {
                                dumpRenderParam(Math.max(param.partialTicks, 0.0f));
                            }
                        }
                    });
            log("注册 DhApiBeforeRenderEvent：success=" + before.success + " message=\"" + before.message + "\"");
        } catch (Throwable failure) {
            log("注册 DhApiBeforeRenderEvent 失败：" + failure);
        }

        try {
            DhApiResult<Void> after = DhApiEventRegister.on(DhApiAfterRenderEvent.class,
                    new DhApiAfterRenderEvent() {
                        @Override
                        public void afterRender(DhApiEventParam<Void> event) {
                            if (afterRenderLogs++ >= MAX_RENDER_EVENT_LOGS) return;
                            log("[afterRender #" + afterRenderLogs + "] 我们帧计数=" + DhBridge.frame()
                                    + " 我们自己这帧的 RT 提交是否已结束=" + DhBridge.frameEnded());
                        }
                    });
            log("注册 DhApiAfterRenderEvent：success=" + after.success + " message=\"" + after.message + "\"");
        } catch (Throwable failure) {
            log("注册 DhApiAfterRenderEvent 失败：" + failure);
        }
    }

    /**
     * 渲染线程每帧调用。
     *
     * <p>注意这里的 ready 判定<b>不依赖</b> {@code DhApiAfterDhInitEvent}：DH 的 API 文档
     * 明确说 {@link DhApi.Delayed} 的字段在 DH 初始化完成前恒为 null，所以直接轮询
     * {@code Delayed.configs} 最稳——事件的到达时机受"我们注册得够不够早"影响，轮询不受。</p>
     *
     * @param levelIdentity 当前 {@code Minecraft.level} 的对象身份（可为 null）。
     *                      只做引用比较，用来识别换世界——玩家切维度/换存档时，
     *                      地形缓存与句柄都会失效，必须整体重建。
     */
    static void tick(long frame, boolean worldLoaded, Object levelIdentity) {
        if (!PROBE_ENABLED) return;

        watchWorldChange(levelIdentity);

        if (!ready) {
            if (DhApi.Delayed.configs == null) {
                if (frame % 600L == 0L) {
                    log("等待 DH 初始化：Delayed.configs 仍为 null（frame=" + frame + "）");
                }
                return;
            }
            ready = true;
            log("DH 的 Delayed.* 已就绪（frame=" + frame + "）");
            log("开关状态：probe=" + PROBE_ENABLED + " lodMesh=" + DhFlags.LOD_MESH
                    + " lodHook=" + DhFlags.LOD_HOOK
                    + " disableRendering=" + DISABLE_DH_RENDERING);
            dumpCapabilities();
        }

        if (worldLoaded) {
            applyFarFieldRenderingOwnership();
        } else {
            releaseFarFieldRenderingOwnership();
        }

        if (worldLoaded && !DISABLE_DH_RENDERING && DhFlags.PUBLISH_TEXTURES) {
            ensureNearClipCoverage();
            probeNearClip(frame);
        } else {
            releaseNearClipCoverage();
        }

        if (worldLoaded && DhFlags.OWN_ATMOSPHERE) {
            applyAtmosphereOwnership();
        } else {
            releaseAtmosphereOwnership();
        }

        probeHandles(frame);

        DhTextureHandles.tick(DhApi.Delayed.renderProxy, frame);
    }

    /**
     * 换世界检测：{@code Minecraft.level} 换了对象就整体重置。
     *
     * <p>与既有远场通路（VoxyLodManager 的 {@code world != minecraft.level} 判定）同一手法。
     * 不重置的话，句柄与相机矩阵都还指向旧世界，轻则读到空数据、重则踩到 DH 已释放的对象。</p>
     */
    private static void watchWorldChange(Object levelIdentity) {
        if (levelIdentity == null) return;
        if (lastLevelIdentity == null) {
            lastLevelIdentity = levelIdentity;
            return;
        }
        if (lastLevelIdentity == levelIdentity) return;
        lastLevelIdentity = levelIdentity;
        log("检测到世界切换 ⇒ 重置 DH 句柄与纹理状态");
        reset();
    }

    static void reset() {
        releaseNearClipCoverage();
        releaseAtmosphereOwnership();
        DhTextureHandles.reset();
        DhFarFieldGeometry.invalidate();
        handlesReady = false;
        handleProbes = 0;
        nextHandleProbeFrame = 0L;
    }

    /**
     * 诊断：DH 这一帧<b>实际用的近裁剪面</b>是多少格（只读，不写任何配置）。
     *
     * <h2>它要回答什么</h2>
     * <p>用户报的"<b>升到一定高度</b>就是能看到那个圆形裁剪区域"直接指向 DH 的"高度覆盖"：
     * 相机高过 {@code level.getMaxHeight() + 1000} 之后，DH 会把 near clip 换成
     * {@code playerY - (maxHeight + 1000)}（**整个覆盖掉** overdrawPrevention 那条路），
     * 返回的那个值就是 DH 在相机周围挖掉的圆洞半径（格），随飞行高度线性增长 —— 这正是
     * "升到一定高度才出现、而且越来越大"的机制。所以把这个数读出来，就是判据本身。</p>
     *
     * <h2>⭐ 09-24：改用官方通道，不再反射 DH 的 core 包</h2>
     * <p>旧实现是 {@code Class.forName("com.seibel.distanthorizons.core.util.RenderUtil")}
     * 再反射 {@code getHeightBasedNearClipOverrideBlockDistance()} +
     * {@code getNearClipPlaneInBlocks()} 两个方法，然后自己判"哪个生效"。那有两个问题：
     * {@code RenderUtil} <b>不在 API jar 里</b>（编译期不可见、DH 改包名/改方法名就静默失效），
     * 而且"两个数拼起来猜"本身就是把 DH 的内部实现当成契约。</p>
     * <p>7.2.0 的 {@code IDhApiRenderProxy.getNearClipPlaneDistanceInBlocks(partialTicks)}
     * 返回的就是<b>DH 这一帧真正在用的那个值</b>（高度覆盖与 overdraw 两条动态路径的结果
     * 已经内含），语义与我们的需求逐字对应。</p>
     *
     * <p>⚠ 如实记录代价：官方接口只给<b>最终值</b>，不再能分辨"是高度覆盖还是 overdraw
     * 路径生效"。那是诊断期的分辨率需求，不值得让<b>生产路径</b>长期依赖 DH 的内部类；
     * 真需要时再单独反射一次即可。</p>
     *
     * <p>官方 javadoc 明说这个值可能逐帧变（FOV / 玩家速度 / 配置），所以<b>不能缓存</b>，
     * 这里按 {@value #NEAR_CLIP_PROBE_INTERVAL_FRAMES} 帧读一次，且只在变化时打印。</p>
     */
    private static void probeNearClip(long frame) {
        if (frame < nextNearClipProbeFrame) return;
        nextNearClipProbeFrame = frame + NEAR_CLIP_PROBE_INTERVAL_FRAMES;

        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) return;

        final float nearBlocks;
        try {
            nearBlocks = proxy.getNearClipPlaneDistanceInBlocks(0.0f);
        } catch (Throwable failure) {
            log("[DH CLIP] getNearClipPlaneDistanceInBlocks() 失败：" + failure);
            return;
        }
        if (!Float.isFinite(nearBlocks)) return;

        if (Float.compare(nearBlocks, lastNearClipBlocks) == 0) return;
        lastNearClipBlocks = nearBlocks;

        String playerY = "?";
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            playerY = String.format(Locale.ROOT, "%.1f", mc.player.getY());
        }
        log("[DH CLIP] DH 实际近裁剪面=" + nearBlocks + " 格（官方通道，含高度覆盖/overdraw）"
                + "  玩家 Y=" + playerY
                + "  ⇒ 这就是 DH 在相机周围挖掉的圆洞半径；飞高后它线性变大");
    }

    /**
     * 深度约定护栏：把 DH 自报的约定与 <b>rgen 里硬编码的那套</b>对一次账。
     *
     * <p>rgen 重建远场距离时用的是<b>闭式</b> reverse-Z 逆变换
     * （{@code z = n·f / (n + d·(f−n))}），而它隐含了两个假设：</p>
     * <ol>
     *   <li>深度值域是 {@code ZERO_TO_POS_ONE}（清屏值 0 = 远，被遮住落在 {@code (0,1]}）；</li>
     *   <li>方向是 {@code REVERSE_Z}（1 = 近、0 = 远）。</li>
     * </ol>
     * <p>这两条一旦不成立，闭式逆变换就整体错，症状是"远场几何深度全错"——而 shader 那边
     * <b>没有任何办法自己发现</b>。所以这里主动对账：一致就打一行确认，不一致就大声警告
     * 并指出要改哪一侧。</p>
     *
     * <p>这两个枚举还<b>自带 {@code nearDepth} / {@code farDepth}</b>，也就是说深度值域的两端
     * 由 DH 直接告诉我们，不必再从"清屏值 = 0"这种观察反推。</p>
     */
    private static void checkDepthConvention(EDhApiDepthRange range, EDhApiDepthDirection direction) {
        if (range == null || direction == null) {
            log("深度约定核对：拿不到（DH 尚未初始化完？），rgen 继续用它的硬编码假设"
                    + "（ZERO_TO_POS_ONE + REVERSE_Z）");
            return;
        }
        final boolean rangeOk = range == EDhApiDepthRange.ZERO_TO_POS_ONE;
        final boolean directionOk = direction == EDhApiDepthDirection.REVERSE_Z;
        log("深度约定核对：range=" + range + " direction=" + direction
                + "（nearDepth=" + direction.nearDepth + " farDepth=" + direction.farDepth + "） ⇒ "
                + (rangeOk && directionOk
                ? "与 rgen 的硬编码假设一致（清屏 0 = 远、被遮住落在 (0,1]）"
                : "★不一致！rgen 的闭式 reverse-Z 逆变换与 dhFarDepthIsGeometry 都会算错。"
                + "改法：rt_test.rgen 的 dhFarDistanceFor 解析档 + dhFarDepthIsGeometry"
                + "（range" + (rangeOk ? "一致" : "需按 " + range + " 改")
                + " / direction" + (directionOk ? "一致" : "需按 " + direction + " 改") + "）"));
    }

    /**
     * 关掉 DH 的 overdraw 近裁剪（{@code overdrawPreventionRadius} → 0）。
     *
     * <p>DH 的 overdrawPrevention 会主动不画相机附近一圈 LOD 来省性能，但那个
     * "开始渲染距离"在我们这边就是一个<b>以相机为中心的圆洞</b>（那里 DH 的 depth
     * 是清屏值 0）。RT 的近景本来就被我们自己的 G-buffer 覆盖，不需要 DH 再省这一层，
     * 所以把半径压到最小。</p>
     *
     * <p>⚠ 两条实测限制（09-23 第五次定案，改这个之前务必读）：</p>
     * <ol>
     *   <li><b>压不到 0</b>：{@code RenderUtil.getNearClipPlaneInBlocks()} 对配置值做
     *       {@code clamp(0.05, v, 1.0)}，再乘 {@code renderDistance×16} ⇒ 本局
     *       （renderDistance=4）实际是 {@code 0.05 × 64 = 3.2 格}（auto 时是 0.3×64=19.2）。
     *       3.2 格的洞远小于近场半径 ⇒ 肉眼不可见，这就是我们要的效果。</li>
     *   <li><b>会被"高度覆盖"整个抢走</b>：见 {@link #probeNearClip(long)}。相机高过
     *       {@code maxHeight + 1000} 之后本配置**完全失效**，洞半径变成
     *       {@code playerY - maxHeight - 1000}。那种高度下唯一的诚实做法是 rgen 里
     *       "DH 没几何 ⇒ 什么都不写"（不要补空气），因为那个洞里本该是原版/近场的地面，
     *       我们没有那份几何。</li>
     * </ol>
     *
     * <p>只走 {@code setValue(v, "Orion")} 这条<b>运行时 API 覆盖</b>，不碰
     * {@code DistantHorizons.toml}；{@link #releaseNearClipCoverage()} 在退出世界 /
     * 换世界时撤回，游戏重启后一切照旧。别人若已持有该覆盖，我们不抢。</p>
     */
    private static void ensureNearClipCoverage() {
        if (ownsNearClipOverride) return;
        try {
            IDhApiConfigValue<Float> radius =
                    DhApi.Delayed.configs.graphics().overdrawPreventionRadius();
            if (radius.getApiValue() != null || !radius.getCanBeOverrodeByApi()) return;
            Float previous = radius.getValue();
            if (radius.setValue(0.0f, "Orion")) {
                ownsNearClipOverride = true;
                log("远场合成：DH 近裁剪半径 " + previous + " → 0（仅当前世界的 API 覆盖）");
            }
        } catch (Throwable failure) {
            log("DH 近裁剪半径覆盖失败，保留原设置：" + failure);
        }
    }

    /** 撤回 {@link #ensureNearClipCoverage()} 持有的覆盖；只清自己写进去的那个 0。 */
    private static void releaseNearClipCoverage() {
        if (!ownsNearClipOverride) return;
        ownsNearClipOverride = false;
        try {
            IDhApiConfigValue<Float> radius =
                    DhApi.Delayed.configs.graphics().overdrawPreventionRadius();
            if (Float.valueOf(0.0f).equals(radius.getApiValue())) {
                radius.clearValue();
            }
        } catch (Throwable failure) {
            log("DH 近裁剪半径覆盖恢复失败：" + failure);
        }
    }

    /**
     * 远场大气所有权：把 DH 自己的<b>雾</b>与<b>云</b>关掉（只改运行时覆盖，不碰 toml）。
     *
     * <h2>为什么要我们持有</h2>
     * <ul>
     *   <li><b>雾</b>：远场的大气透视由 rgen 按我们自己的天空/太阳算（近场同一个函数），
     *       DH 的雾若叠上来就是两套大气，接缝处会出现亮度台阶与颜色跳变。</li>
     *   <li><b>云</b>：DH 的云是它自己画的平面几何，会在远场里冒出与我们的体积云无关的
     *       形状（实测用户报过"云层黑色多边形"）。</li>
     * </ul>
     *
     * <h2>为什么用 API 而不是让用户改配置</h2>
     * <p>之前是"请用户去 {@code DistantHorizons.toml} 里关"（{@code enableCloudRendering} /
     * {@code farFogStart}）。那样的问题：<b>换机器、重装、删配置文件、换存档</b>都会丢，
     * 而症状是"远场莫名其妙多一层雾/云"，排查成本极高。运行时覆盖可逆、带归属名
     * （{@code "Orion"}）、退出世界自动撤回，且 DH 自己会在界面上标注"被 Orion 覆盖"。</p>
     *
     * <h2>边界（刻意保留的克制）</h2>
     * <ol>
     *   <li>别人已经用 API 覆盖过这项配置时<b>不抢</b>（与 {@link #ensureNearClipCoverage()}
     *       同一规矩）——冲突时以先到者为准，我们只记一行日志。</li>
     *   <li>{@code getCanBeOverrodeByApi()=false} 时不写（用户可能在配置里锁死了）。</li>
     *   <li>只清自己写进去的值：撤回时逐项确认"当前 API 值 == 我写的那个"，否则留着别人的。</li>
     *   <li>⚠ 这里动的是 {@code graphics().fog()} 与 {@code genericRendering()}，
     *       <b>不是</b> {@code graphics().renderingEnabled()}（那个管 fake chunk 地形本身，
     *       关掉等于把远场整个抹了）。两者别搞混。</li>
     * </ol>
     */
    private static void applyAtmosphereOwnership() {
        if (atmosphereAttempted) return;
        try {
            IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
            if (graphics == null) return;
            atmosphereAttempted = true;
            boolean fogOff = overrideBoolean("远场雾",
                    graphics.fog() == null ? null : graphics.fog().enableDhFog(), Boolean.FALSE);
            boolean cloudOff = overrideBoolean("DH 云",
                    graphics.genericRendering() == null
                            ? null : graphics.genericRendering().cloudRenderingEnabled(), Boolean.FALSE);

            ownsAtmosphereOverride = fogOff || cloudOff;
            if (ownsAtmosphereOverride) {
                log("远场大气所有权：DH 雾=" + (fogOff ? "关" : "未动")
                        + " DH 云=" + (cloudOff ? "关" : "未动")
                        + "（运行时覆盖，退出世界自动撤回）");
            }
        } catch (Throwable failure) {
            log("远场大气所有权申请失败，保留 DH 原设置：" + failure);
        }
    }

    /** 撤回 {@link #applyAtmosphereOwnership()} 写进去的值；只清自己写的那些。 */
    private static void releaseAtmosphereOwnership() {
        atmosphereAttempted = false;
        if (!ownsAtmosphereOverride) return;
        ownsAtmosphereOverride = false;
        try {
            IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
            if (graphics == null) return;
            clearIfOurs(graphics.fog() == null ? null : graphics.fog().enableDhFog(), Boolean.FALSE);
            clearIfOurs(graphics.genericRendering() == null
                    ? null : graphics.genericRendering().cloudRenderingEnabled(), Boolean.FALSE);
        } catch (Throwable failure) {
            log("远场大气所有权撤回失败：" + failure);
        }
    }
    /**
     * 写一个布尔配置的运行时覆盖。
     *
     * @return 是否真的写成功了（调用方据此决定要不要在撤回时清理）
     */
    private static boolean overrideBoolean(String label, IDhApiConfigValue<Boolean> value, Boolean target) {
        if (value == null) return false;
        try {
            if (value.getApiValue() != null) {
                log("  远场大气：" + label + " 已被别的 API 使用者覆盖（" + value.getApiValue() + "），不抢");
                return false;
            }
            if (!value.getCanBeOverrodeByApi()) {
                log("  远场大气：" + label + " 禁止 API 覆盖（用户在配置里锁定了），跳过");
                return false;
            }
            Boolean previous = value.getValue();
            boolean ok = value.setValue(target, "Orion");
            log("  远场大气：" + label + " " + previous + " → " + value.getValue()
                    + "（setValue 返回 " + ok + "）");
            return ok;
        } catch (Throwable failure) {
            log("  远场大气：" + label + " 覆盖失败：" + failure);
            return false;
        }
    }

    /** 只在"当前 API 值 == 我们写的那个"时清除覆盖，避免误清别人的。 */
    private static void clearIfOurs(IDhApiConfigValue<Boolean> value, Boolean ours) {
        if (value == null) return;
        try {
            if (ours.equals(value.getApiValue())) {
                value.clearValue();
            }
        } catch (Throwable failure) {
            log("  远场大气：撤回失败：" + failure);
        }
    }

    /**
     * 申请"远场绘制所有权"：把 DH 自己的 LOD 绘制关掉（{@link DhFlags#DISABLE_DH_RENDERING}）。
     *
     * <p>为什么必须是默认行为而不是诊断开关：我们的远场几何已经在 TLAS 里，走与近场完全
     * 相同的命中/光照路径。DH 再画一遍就是<b>同一条远场两套壳</b>，且它那套是光栅的
     * （自己的图集 mip、自己的雾/SSAO/TAA）。实机取证（{@code run/logs/latest.log}）：</p>
     * <pre>
     *   [DH] 开关状态：… disableRendering=false
     *   [DH]   renderingEnabled = true   [文件值=true 默认=true API可写=true]
     * </pre>
     * <p>覆盖对等：DH 自己的 LOD 距离 {@code lodChunkRenderDistanceRadius = 256} 区块，
     * 我们的粗环也铺到 256 区块 ⇒ 关掉它不会让天际线变空。</p>
     *
     * <p>规矩与 {@link #applyAtmosphereOwnership()} 一致：别人已经用 API 占用了这项配置就
     * 不抢（只记一行）；用户在配置里锁死（{@code canBeOverrodeByApi=false}）也不写。
     * 只尝试一次，退出世界时由 {@link #releaseFarFieldRenderingOwnership()} 撤回。</p>
     */
    private static void applyFarFieldRenderingOwnership() {
        if (renderingOwnershipAttempted) return;
        try {
            IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
            if (graphics == null) return;
            IDhApiConfigValue<Boolean> rendering = graphics.renderingEnabled();
            if (rendering == null) {
                renderingOwnershipAttempted = true;
                log("远场绘制所有权：renderingEnabled 不存在（该版本无此配置），跳过");
                return;
            }
            renderingOwnershipAttempted = true;
            final boolean want = !DISABLE_DH_RENDERING;
            final Object current = rendering.getValue();
            final Object apiValue = rendering.getApiValue();
            if (Boolean.valueOf(want).equals(current) && apiValue == null) {
                log("远场绘制：DH 自己的 LOD 绘制 = " + current + "（已是我们要的状态，无需干预）");
                return;
            }
            if (!rendering.getCanBeOverrodeByApi()) {
                ownsRendering = false;
                log("远场绘制所有权：renderingEnabled 禁止 API 覆盖（配置里锁定了），跳过；当前值="
                        + current);
                return;
            }
            final boolean ok = rendering.setValue(want, "Orion");
            ownsRendering = ok;
            ownsRenderingValue = want;
            log("远场绘制所有权：DH 自己的 LOD 绘制 " + current + " → " + rendering.getValue()
                    + "（setValue 返回 " + ok + "）"
                    + (want ? "⇒ 远场由 DH 自己渲染（我们只做近场光追）" : "⇒ 远场由我方 TLAS 绘制"));
        } catch (Throwable failure) {
            log("远场绘制所有权申请失败：" + failure);
        }
    }

    /** 撤回 {@link #applyFarFieldRenderingOwnership()} 写进去的值（只清自己写的那个）。 */
    private static void releaseFarFieldRenderingOwnership() {
        renderingOwnershipAttempted = false;
        if (!ownsRendering) return;
        ownsRendering = false;
        try {
            IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
            if (graphics == null) return;
            clearIfOurs(graphics.renderingEnabled(), ownsRenderingValue);
            ownsRenderingValue = null;
        } catch (Throwable failure) {
            log("远场绘制所有权撤回失败：" + failure);
        }
    }


    private static void dumpCapabilities() {
        log("========== DH 能力矩阵（P0 探针） ==========");
        dumpRenderProxy();
        dumpGraphicsConfig();
        dumpFogConfig();
        log("（color/depth/blockRatioAtlas 句柄此刻通常还不存在——要等世界里首帧，见后续 [handles] 行）");
        log("========== 能力矩阵结束 ==========");
    }

    private static void dumpRenderProxy() {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) {
            log("renderProxy = null");
            return;
        }
        EDhApiRenderingEngine engine = call("renderProxy.getRenderingEngine()", proxy::getRenderingEngine);
        EDhApiRenderingApi api = call("renderProxy.getRenderingApi()", proxy::getRenderingApi);
        log("渲染引擎：" + engine + "  /  渲染 API：" + api);
        if (engine == EDhApiRenderingEngine.BLAZE_3D) {
            log("  ⇒ BLAZE_3D：color/depth 有希望拿到 Blaze3D 句柄直通（见后续 [handles] 行）");
        } else if (engine == EDhApiRenderingEngine.OPEN_GL) {
            log("  ⇒ OPEN_GL：只能拿到 GL 纹理名，直通路线需另设方案");
        }

        Boolean nativeRenderer = call("renderProxy.isNativeRenderer()", proxy::isNativeRenderer);
        log("isNativeRenderer = " + nativeRenderer
                + "（false 表示走 Blaze3D 这类解释层）");
        EDhApiDepthRange range = call("renderProxy.getDepthRange()", proxy::getDepthRange);
        EDhApiDepthDirection direction = call("renderProxy.getDepthDirection()", proxy::getDepthDirection);
        log("depthRange = " + range + "  depthDirection = " + direction);
        checkDepthConvention(range, direction);
        Float nearClip = call("renderProxy.getNearClipPlaneDistanceInBlocks(0)",
                () -> proxy.getNearClipPlaneDistanceInBlocks(0.0f));
        log("nearClipPlane(blocks) = " + nearClip);
        log("deferTransparentRendering = "
                + call("renderProxy.getDeferTransparentRendering()", proxy::getDeferTransparentRendering));
    }

    /**
     * Q7：DH 的 color/depth 能否拿到可直通的底层对象。
     *
     * <p>这是整份规划里最关键的一个问号：它决定"图像定色 + 几何定光"路线里
     * color/depth 到底能不能直接进我们的 Vulkan RT。</p>
     */
    private static void probeHandles(long frame) {
        if (handlesReady) return;
        if (frame < nextHandleProbeFrame) return;
        nextHandleProbeFrame = frame + HANDLE_PROBE_INTERVAL_FRAMES;
        handleProbes++;

        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) return;

        log("---- [handles] 第 " + handleProbes + " 次探测（frame=" + frame + "）----");

        boolean color = describeHandle("color",
                call("getDhColorTextureBlazeWrapper()", proxy::getDhColorTextureBlazeWrapper));
        boolean depth = describeHandle("depth",
                call("getDhDepthTextureBlazeWrapper()", proxy::getDhDepthTextureBlazeWrapper));
        boolean atlas = describeHandle("blockRatioAtlas",
                call("getDhBlockRatioAtlasTextureBlazeWrapper()",
                        proxy::getDhBlockRatioAtlasTextureBlazeWrapper));

        log("  GL 纹理名（Vulkan 下应为无意义值，仅作对照）：color="
                + describeGlId(call("getDhColorTextureGlId()", proxy::getDhColorTextureGlId))
                + " depth=" + describeGlId(call("getDhDepthTextureGlId()", proxy::getDhDepthTextureGlId))
                + " blockRatio=" + describeGlId(call("getDhBlockRatioAtlasTextureGlId()",
                proxy::getDhBlockRatioAtlasTextureGlId)));

        if (color && depth && atlas) {
            handlesReady = true;
            log("  ⇒ 三个句柄全部可用，停止重探。");
        } else if (handleProbes >= MAX_HANDLE_PROBES) {
            handlesReady = true;
            log("  ⇒ 已达重探上限 " + MAX_HANDLE_PROBES + " 次，仍有句柄不可用，停止重探"
                    + "（color=" + color + " depth=" + depth + " atlas=" + atlas + "）。");
        }
    }

    private static String describeGlId(DhApiResult<Integer> result) {
        if (result == null) return "<调用失败>";
        return String.valueOf(result.payload) + "(success=" + result.success + ", "
                + result.message + ")";
    }

    /** @return 是否拿到了可用的底层对象 */
    private static boolean describeHandle(String label, DhApiResult<IDhApiBlazeTextureWrapper> result) {
        if (result == null) {
            log("  [" + label + "] 句柄查询调用失败（见上方异常）");
            return false;
        }
        if (!result.success || result.payload == null) {
            log("  [" + label + "] 不可用：success=" + result.success + " message=\"" + result.message + "\"");
            return false;
        }
        IDhApiBlazeTextureWrapper wrapper = result.payload;
        Object wrapped = call("wrapper.getWrappedMcObject()", wrapper::getWrappedMcObject);
        log("  [" + label + "] name=" + call("getName()", wrapper::getName)
                + " 尺寸=" + call("getWidth()", wrapper::getWidth) + "x" + call("getHeight()", wrapper::getHeight));
        log("  [" + label + "] wrapper=" + wrapper.getClass().getName()
                + "  wrapped=" + (wrapped == null ? "null" : wrapped.getClass().getName()));

        try {
            log("  [" + label + "] ---- 下沉扫描（只读反射，不触碰 GPU）----");
            dumpObject("  [" + label + "]   ", wrapper, 0, new IdentityHashMap<>());
            if (wrapped != null && wrapped != wrapper) {
                dumpObject("  [" + label + "]   ", wrapped, 0, new IdentityHashMap<>());
            }
        } catch (Throwable failure) {
            log("  [" + label + "] 下沉扫描失败：" + failure);
        }
        return true;
    }

    /**
     * 有界只读反射：把对象里"像 GPU 句柄"的东西挖出来。
     *
     * <p>数组逐元素下沉、对象的非静态字段逐个报告并下沉；深度上限 {@value #MAX_DUMP_DEPTH}、
     * 每层最多 {@value #MAX_DUMP_MEMBERS} 个成员、同一对象只访问一次（防环）。</p>
     *
     * <p>纯 JVM 内存读取，不会产生任何 GPU 调用——它的副作用最多是打印一堆类名。</p>
     */
    private static void dumpObject(String indent, Object value, int depth, IdentityHashMap<Object, Object> seen) {
        if (value == null || depth > MAX_DUMP_DEPTH) return;
        Class<?> type = value.getClass();

        if (type.isArray()) {
            int length = Array.getLength(value);
            log(indent + "数组 " + type.getComponentType().getSimpleName() + "[" + length + "]");
            for (int i = 0; i < Math.min(length, MAX_DUMP_MEMBERS); i++) {
                Object element = Array.get(value, i);
                log(indent + "  [" + i + "] " + (element == null ? "null" : element.getClass().getName()));
                if (element != null) dumpObject(indent + "      ", element, depth + 1, seen);
            }
            if (length > MAX_DUMP_MEMBERS) log(indent + "  ...（共 " + length + " 个元素）");
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence
                || value instanceof Enum<?>) {
            log(indent + type.getSimpleName() + " = " + value);
            return;
        }
        if (seen.put(value, value) != null) return;

        log(indent + type.getName() + "  ⇒ " + vulkanVerdict(type));
        Field[] fields;
        try {
            fields = type.getDeclaredFields();
        } catch (Throwable failure) {
            return;
        }
        int reported = 0;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (reported++ >= MAX_DUMP_MEMBERS) {
                log(indent + "  ...（字段过多，已截断）");
                break;
            }
            Object fieldValue;
            try {
                field.setAccessible(true);
                fieldValue = field.get(value);
            } catch (Throwable failure) {
                log(indent + "  ." + field.getName() + " : " + field.getType().getSimpleName()
                        + " = <不可读 " + failure.getClass().getSimpleName() + ">");
                continue;
            }
            log(indent + "  ." + field.getName() + " : " + field.getType().getSimpleName()
                    + " = " + (fieldValue == null ? "null" : fieldValue.getClass().getName()));
            if (fieldValue != null) dumpObject(indent + "      ", fieldValue, depth + 1, seen);
        }
    }

    /** @return "是/否 像 Vulkan 句柄"，顺带把命中的类名列出来 */
    private static String vulkanVerdict(Class<?> type) {
        StringBuilder builder = new StringBuilder();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (name.contains("vulkan") || name.contains("Vulkan")) {
                if (builder.length() > 0) builder.append(", ");
                builder.append(name);
            }
        }
        return builder.length() == 0 ? "类层次中无 Vulkan 类型" : "★Vulkan 类型：" + builder;
    }

    /** Q8 的解析输入：DH 自己用的投影与 MC 的投影不一样，两套都给全。 */
    private static void dumpRenderParam(float partialTicks) {
        log("  ---- DhApiRenderParam 快照（partialTicks=" + partialTicks + "）----");
        log("  两套矩阵都会给出：mcProjectionMatrix / mcModelViewMatrix（MC 用） 与"
                + " dhProjectionMatrix / dhModelViewMatrix / dhMvmProjMatrix / dhInverseMvmProjectionMatrix（DH 用）");
        log("  调用点代码可直接取 param.dhInverseMvmProjectionMatrix 把 DH 的 depth 反投影回世界坐标。");
    }

    /** Q2/Q10：哪些配置能被 API 覆盖（决定能否关掉 DH 自身渲染与它自己的雾）。 */
    private static void dumpGraphicsConfig() {
        IDhApiGraphicsConfig graphics;
        try {
            graphics = DhApi.Delayed.configs.graphics();
        } catch (Throwable failure) {
            log("configs.graphics() 失败：" + failure);
            return;
        }
        if (graphics == null) {
            log("configs.graphics() = null");
            return;
        }
        log("--- graphics ---");
        cfg("chunkRenderDistance", graphics.chunkRenderDistance());
        cfg("renderingEnabled", graphics.renderingEnabled());
        cfg("renderingMode", graphics.renderingMode());
        cfg("maxHorizontalResolution", graphics.maxHorizontalResolution());
        cfg("verticalQuality", graphics.verticalQuality());
        cfg("transparency", graphics.transparency());
        cfg("lodShading", graphics.lodShading());
        cfg("brightnessMultiplier", graphics.brightnessMultiplier());
        cfg("saturationMultiplier", graphics.saturationMultiplier());
        cfg("lodOnlyMode", graphics.lodOnlyMode());
    }

    private static void dumpFogConfig() {
        IDhApiFogConfig fog;
        try {
            fog = DhApi.Delayed.configs.graphics().fog();
        } catch (Throwable failure) {
            log("configs.graphics().fog() 失败：" + failure);
            return;
        }
        if (fog == null) {
            log("fog() = null");
            return;
        }
        log("--- fog（远场雾的所有权，Q10）---");
        cfg("enableDhFog", fog.enableDhFog());
        cfg("color", fog.color());
        cfg("enableVanillaFog", fog.enableVanillaFog());
        try {
            var far = fog.farFog();
            log("  farFog:");
            cfg("  farFogStartDistance", far.farFogStartDistance());
            cfg("  farFogEndDistance", far.farFogEndDistance());
            cfg("  farFogMinThickness", far.farFogMinThickness());
            cfg("  farFogMaxThickness", far.farFogMaxThickness());
            cfg("  farFogFalloff", far.farFogFalloff());
            cfg("  farFogDensity", far.farFogDensity());
        } catch (Throwable failure) {
            log("  farFog() 失败：" + failure);
        }
        try {
            var height = fog.heightFog();
            log("  heightFog:");
            cfg("  heightFogBaseHeight", height.heightFogBaseHeight());
            cfg("  heightFogDensity", height.heightFogDensity());
            cfg("  heightFogFalloff", height.heightFogFalloff());
        } catch (Throwable failure) {
            log("  heightFog() 失败：" + failure);
        }
    }


    /** 打印一个配置项的当前值 / 文件值 / 默认值 / 是否可被 API 覆盖。 */
    private static void cfg(String name, IDhApiConfigValue<?> value) {
        if (value == null) {
            log("  " + name + " = <该配置在此 DH 版本不存在>");
            return;
        }
        Object active;
        Object stored;
        Object fallback;
        Object writable;
        try {
            active = value.getValue();
        } catch (Throwable failure) {
            active = "<异常 " + failure.getClass().getSimpleName() + ">";
        }
        try {
            stored = value.getTrueValue();
        } catch (Throwable failure) {
            stored = "?";
        }
        try {
            fallback = value.getDefaultValue();
        } catch (Throwable failure) {
            fallback = "?";
        }
        try {
            writable = value.getCanBeOverrodeByApi();
        } catch (Throwable failure) {
            writable = "?";
        }
        log("  " + name + " = " + active
                + "   [文件值=" + stored + " 默认=" + fallback + " API可写=" + writable + "]");
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    private static <T> T call(String what, ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable failure) {
            log("  " + what + " 失败：" + failure);
            return null;
        }
    }

    private static void log(String message) {
        System.err.println(TAG + " " + message);
    }
}
