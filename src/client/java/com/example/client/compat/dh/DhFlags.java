package com.example.client.compat.dh;

import java.util.Locale;

/**
 * DH 接入相关的诊断开关（集中一处，<b>不含任何 DH 类型引用</b>）。
 *
 * <p>每个开关都支持两个通道：JVM 属性优先，其次同名环境变量。
 * {@code -Dorion.dh.probe=false} 与 {@code ORION_DH_PROBE=false} 等效。
 * 给环境变量通道是为了不改 {@code build.gradle}——IDE 运行配置里加一行即可。</p>
 */
final class DhFlags {
    /** 总探针开关。关掉后 {@code DhApiImpl} 除了版本查询什么都不做。 */
    static final boolean PROBE = flag("orion.dh.probe", true);

    /**
     * 让 DH 保持加载、保持生成数据，但<b>自己不绘制远场</b>。
     *
     * <p>与 {@link #LOD_MESH}=true 配对使用：DH 的 LOD 几何被我们接管进 TLAS，
     * 由光追管线统一着色（GI / 阴影 / 反射 / 大气），DH 自己的光栅化那一遍必须关掉，
     * 否则同一条远场会被画两遍。</p>
     *
     * <p>历史（别重复踩）：这条曾被设为 false 且 {@link #LOD_MESH}=false，结果是远场
     * 只剩 DH 自己的光栅画面 —— 正是用户后来反复抱怨的"进不了光追 / 与近场不搭"。
     * 实机日志证过 DH 自己渲染时的读数：{@code [DH] renderingEnabled = true}
     * （{@code DistantHorizons.toml} 里那个 {@code enableRendering} 是"顺带关掉原版 MC
     * 渲染"，与本项无关）。</p>
     *
     * <p>打开（{@code -Dorion.dh.disableRendering=true}）= 关掉 DH 的绘制，只有在我们
     * 接管几何（{@link #LOD_MESH}=true）时才有意义。</p>
     */
    static final boolean DISABLE_DH_RENDERING = flag("orion.dh.disableRendering", false);

    /**
     * 把 DH 的 color/depth/blockRatioAtlas 句柄推进 C++（{@code publishDhTextures}）。
     *
     * <p>⭐ 方案 c（远场外观取 DH 画面）需要它，<b>接管几何这条路（{@link #LOD_MESH}）
     * 也用它</b>：远场顶点的 {@code textureTileId} 要靠 binding 49 指向的
     * <b>DH blockRatioAtlas</b> 才采得到纹理（DH 的 {@code terrain/blaze/frag.fsh}
     * 里那个 {@code uBlockAtlas} 就是它）。</p>
     *
     * <p>⚠ 09-25 打开它（连同 {@link #MATRICES} 与 {@code farLight.mode=13}）时进世界崩溃：
     * {@code hs_err} 的崩溃点是 {@code rtx_backend.dll} 的未捕获 C++ 异常从
     * {@code recordFrameCommand} 冒出，同期 {@code rt_debug.log} 写着 {@code [DH TEX] … 来源=回落图集}。
     * 也就是说崩的是<b>方案 c 那条"采样 DH 画面"的路</b>（门开着 + 句柄没就绪）。
     * 现在默认打开它，但 {@link #MATRICES} 与 mode 都还是关的 ⇒ 方案 c 的门是 0，
     * rgen 根本不会去取那两个 sampler；C++ 侧也补了 null 检查、{@code FGDeviceWaitIdle}
     * 与"句柄无效就回落 {@code g_atlasImageView}"。只开这一个是最先验证过的安全档。</p>
     */
    static final boolean PUBLISH_TEXTURES = flag("orion.dh.publishTextures", true);

    /**
     * stage B：每帧把 DH 的逆 MVP＋near/far 推进 C++（{@code updateDhFarField}）。
     *
     * <p>⭐ 方案 c（合成 DH 画面）需要它，<b>当前默认关</b>（与 {@link #PUBLISH_TEXTURES}
     * 一起在"进世界崩溃"那次被回退；要启用时两个一起开）。矩阵自身有 NaN 护栏 ——
     * 世界刚加载那几帧 DH 的矩阵还是 NaN，日志会写
     * {@code [DH-MAT] 本帧矩阵不可用（非有限），跳过推送}，不会把垃圾推下去。</p>
     */
    static final boolean MATRICES = flag("orion.dh.matrices", false);

    /**
     * C 阶段：<b>接管 DH 已经网格化好的 LOD 顶点缓冲</b>，转成我方顶点格式后送进 TLAS。
     *
     * <p>这是照 Radiance（Vista）形态做的路线：<b>DH 怎么处理远场，我们就怎么处理</b> ——
     * 不从 DH 的列数据自搓几何，只在 {@code DhLodBufferHookMixin} 里拦下 DH 建好的
     * section 顶点缓冲，逐节转向量、以 {@code uploadDhLodSection} 增量交给光追管线。
     * 几何是 DH 的（逐方块精度的森林/崖壁层理），着色是我们自己的（与近场共用光追）。</p>
     *
     * <p>与 {@link #DISABLE_DH_RENDERING}=true 配对使用：几何既然进了我方 TLAS，
     * DH 自己的光栅那一遍就该关掉。</p>
     *
     * <p>打开：{@code -Dorion.dh.lodMesh=true}。</p>
     */
    static final boolean LOD_MESH = flag("orion.dh.lodMesh", false);

    /**
     * LOD 顶点缓冲接管开关（{@code DhLodBufferHookMixin} 挂在
     * {@code LodBufferContainer.tryMakeAndUploadBuffersAsync} 上）。
     *
     * <p>关掉它：{@code -Dorion.dh.lodHook=false}。它是接管链路的取数口，
     * 与 {@link #LOD_MESH}（是否把收到的数据送进 TLAS）解耦。</p>
     */
    static final boolean LOD_HOOK = flag("orion.dh.lodHook", true);

    /** 区域重读间隔（帧）。DH 的 section 是静态数据，重建得比帧率慢才正确。 */
    static final long LOD_REFRESH_FRAMES = (long) number("orion.dh.lodRefreshFrames", 120.0f);

    /**
     * 重读的<b>移动阈值</b>（格，切比雪夫距离：用 max(|dx|,|dz|) 而不是欧氏距离，
     * 因为窗口是个方框）。
     *
     * <p>地形是静态的：站着不动时重新合并上传毫无意义。加上阈值后，静止 = 零开销；
     * 走路（4.5 格/秒）也不会每 2 秒重来一次。</p>
     *
     * <p>⚠ 合并上传时用统一原点，窗口每帧按玩家位置发布（{@code DhLodMeshSink.setWindow}），
     * 所以"中心落后玩家最多阈值格"只影响合并的时机，不影响收数范围。</p>
     */
    static final int LOD_MOVE_THRESHOLD = (int) number("orion.dh.lodMoveThreshold", 32.0f);

    /**
     * 远场大气的所有权：世界内把 DH 自己的<b>雾</b>与<b>云</b>用官方 API 关掉，退出世界撤回。
     *
     * <p><b>默认关</b>。切线：当远场几何进了我方 TLAS（{@link #LOD_MESH}=true）时，
     * 大气透视应由 rgen 按我们自己的天空/太阳算（与近场同一个函数），DH 的雾叠上来就是
     * 两套大气；DH 的云是它自己画的平面几何，会在远场里冒出与我们的体积云无关的形状。
     * 那时打开它（{@code -Dorion.dh.ownAtmosphere=true}）。</p>
     *
     * <p>⭐ 用它取代"请用户去 {@code DistantHorizons.toml} 关
     * {@code enableCloudRendering} / {@code farFogStart}"：后者换机器、重装、删配置就丢，
     * 症状是"远场莫名其妙多一层雾/云"，排查成本极高。这里走
     * {@code IDhApiConfigValue.setValue(v, "Orion")}，可逆、带归属名、别人已覆盖时不抢。</p>
     *
     * <p>设 false ⇒ 完全不碰 DH 的这两项配置（退回"用户自己改 toml"的老路）。</p>
     */
    static final boolean OWN_ATMOSPHERE = flag("orion.dh.ownAtmosphere", false);

    private DhFlags() {
    }

    private static boolean flag(String propertyName, boolean defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null) {
            raw = System.getenv(propertyName.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return raw == null ? defaultValue : !"false".equalsIgnoreCase(raw.trim());
    }

    /** 数值型开关：解析失败一律回默认值，绝不因为一个属性写错就崩在初始化上。 */
    private static float number(String propertyName, float defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null) {
            raw = System.getenv(propertyName.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        if (raw == null) return defaultValue;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
