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
    static final boolean PROBE = flag("orion.dh.probe", true);

    

    static final boolean DISABLE_DH_RENDERING = flag("orion.dh.disableRendering", false);

    

    static final boolean PUBLISH_TEXTURES = flag("orion.dh.publishTextures", true);

    

    static final boolean MATRICES = flag("orion.dh.matrices", false);

    

    static final boolean LOD_MESH = flag("orion.dh.lodMesh", false);

    

    static final boolean LOD_HOOK = flag("orion.dh.lodHook", true);

    static final long LOD_REFRESH_FRAMES = (long) number("orion.dh.lodRefreshFrames", 120.0f);

    

    static final int LOD_MOVE_THRESHOLD = (int) number("orion.dh.lodMoveThreshold", 32.0f);

    

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
