package com.example.client.compat.dh;

import com.example.client.compat.FarFieldGate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Distant Horizons 接入的隔离门面（当前处于 P0 探针阶段）。
 *
 * <h2>三条硬约束，改动前务必先读</h2>
 * <ol>
 *   <li><b>本类不得 import 任何 {@code com.seibel.distanthorizons.*} 类型。</b>
 *       DH 是可选依赖（build.gradle 里只有 {@code compileOnly}）。若本类字段/签名直接
 *       引用 DH 类型，JVM 在加载本类时就要解析它们，未装 DH 的玩家会拿到
 *       {@link NoClassDefFoundError}，把整个模组拖崩——而不是"默默没有远场"。</li>
 *   <li><b>所有 DH 访问集中在包 {@code com.example.client.compat.dh} 内</b>
 *       （当前是 {@link DhApiImpl}、{@link DhLodMeshSink} 的调用方 mixin，以及
 *       {@code DhTextureHandles}）。包外的任何类都不得 import DH。</li>
 *   <li><b>DH 的任何异常都在本层吞掉并降级。</b> 远场是增益特性，不能让它的失败
 *       冒泡到渲染主循环，更不能让一次异常反复刷屏。</li>
 * </ol>
 */
public final class DhBridge {
    /** DH 的 Fabric mod id。注意 API jar 自身的 id 是 {@code dh-api}，它的 parent 才指向这里。 */
    private static final String MOD_ID = "distanthorizons";

    /** 低于此 API 主版本一律拒绝接入：语义未知时宁可不要远场，也不要在猜测上建栈。 */
    private static final int MIN_API_MAJOR = 7;

    private static final boolean MOD_PRESENT = FabricLoader.getInstance().isModLoaded(MOD_ID);

    private static boolean installed;
    private static boolean supported;
    private static String status = MOD_PRESENT ? "未探测" : "未安装 Distant Horizons";
    private static long frame;
    private static boolean frameEnded;
    /** 远场网格路径的熔断器：出错一次即停用，避免每帧刷屏。 */
    private static boolean lodMeshEnabled = true;

    private DhBridge() {
    }

    /** DH 模组是否存在（与访问是否成功无关）。 */
    public static boolean present() {
        return MOD_PRESENT;
    }

    /** 是否已成功接入并且当前仍可用。 */
    public static boolean active() {
        return installed && supported;
    }

    /** 人类可读的当前状态，供设置界面/调试 HUD 显示。 */
    public static String status() {
        return status;
    }

    static long frame() {
        return frame;
    }

    /**
     * 本帧里我们自己的 RT 提交是否已经结束（{@code endEntityFrame} 已调用）。
     *
     * <p>探针用它回答 Q8：DH 的渲染事件落在我们 {@code endEntityFrame} 之前还是之后。
     * 若 DH 在我们之前提交，则同帧内它的 color/depth 就已写好，读它不会晚一帧；
     * 否则远场图像会晚一帧，需要按 depth 重投影。</p>
     */
    static boolean frameEnded() {
        return frameEnded;
    }

    /** 由渲染 mixin 在 {@code LevelRenderer.render} 返回处调用（见 LevelRendererMixin）。 */
    public static void markFrameEnded() {
        frameEnded = true;
    }

    /** 客户端初始化调用一次（ExampleModClient#onInitializeClient）。 */
    public static void install() {
        if (installed) return;
        installed = true;

        if (!MOD_PRESENT) {
            log("未检测到 Distant Horizons，远场通路保持关闭");
            return;
        }

        try {
            int major = DhApiImpl.apiMajor();
            String version = DhApiImpl.modVersion() + " / API " + DhApiImpl.apiVersion();
            if (major < MIN_API_MAJOR) {
                supported = false;
                status = "API 版本过低（" + version + "，需要 API major >= " + MIN_API_MAJOR + "）";
                log(status + " ⇒ 远场通路关闭");
                return;
            }
            DhApiImpl.install();
            supported = true;
            status = "已接入 " + version;
            log("已接入 Distant Horizons：" + version);
        } catch (Throwable failure) {
            supported = false;
            status = "接入失败：" + failure;
            log("DH 接入失败，降级为无远场：" + failure);
        }
    }

    /**
     * 渲染线程每帧调用一次。挂在 {@code LevelRendererMixin} 的 render HEAD，
     * 与既有的远场通路同一时序位置。
     *
     * <p>本方法<b>不会阻塞</b>：DH 的 LOD 顶点缓冲由 DH 自己的线程构建，我们只在
     * hook 里把它们拷进暂存区，这里只做状态机推进与合并上传。</p>
     */
    public static void tick() {
        frameEnded = false;
        if (!MOD_PRESENT || !supported) return;
        frame++;

        Minecraft minecraft = Minecraft.getInstance();
        boolean worldLoaded = minecraft.level != null;

        try {
            // level 只作为"身份"参与引用比较（换世界检测），不做任何字段访问。
            DhApiImpl.tick(frame, worldLoaded, minecraft.level);
        } catch (Throwable failure) {
            // 运行期一次性异常即彻底停用：探针/接入代码再出错也不该影响每一帧。
            supported = false;
            status = "运行期异常，已停用：" + failure;
            log(status);
            return;
        }

        // C 阶段：把 DH 地形当几何喂给 TLAS（默认关闭，见 DhFlags.LOD_MESH）。
        // 与上面的探针解耦：它自己判断世界/玩家是否可用，出错也只停它自己。
        if (lodMeshEnabled) {
            try {
                DhFarFieldUploader.tick();
            } catch (Throwable failure) {
                lodMeshEnabled = false;
                DhFarFieldUploader.reset();
                log("远场网格路径异常，已停用（其余接入不受影响）：" + failure);
            }
        }
    }

    /** 世界卸载/切换时调用，丢弃一切与旧世界绑定的 DH 状态。 */
    public static void reset() {
        if (!MOD_PRESENT || !supported) return;
        try {
            DhApiImpl.reset();
        } catch (Throwable ignored) {
            // 清理失败没有副作用：下次进世界会从头重建。
        }
        try {
            // 旧世界的网格必须撤下：它的坐标只对旧世界成立。
            DhFarFieldUploader.reset();
        } catch (Throwable ignored) {
            // 同上。
        }
        // P1-A（09-25）：DH 换世界/退世界时也要清掉远场接管闸门。此前硬复位只挂在
        // VoxyLodManager.clearNative() 里，DH 单独跑时会漏 ⇒ 旧世界的"LOD 在供数据"
        // 状态会把近场压制带进新世界。
        FarFieldGate.onLodHardReset();
        lodMeshEnabled = true;
    }

    /** 远场网格的计数摘要（上传次数 / 当前 quad 数 / 暂存区状态），供调试 HUD 使用。 */
    public static String lodMeshStats() {
        if (!MOD_PRESENT || !supported) return "DH 未接入";
        try {
            return DhFarFieldUploader.stats();
        } catch (Throwable ignored) {
            return "读取失败";
        }
    }

    private static void log(String message) {
        System.err.println("[DH-BRIDGE] " + message);
    }
}
