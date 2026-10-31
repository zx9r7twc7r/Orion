package com.example.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


public final class RTXSettingsConfig {

    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .create();


    private static final Path CONFIG_PATH =
            FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve(
                            "rtx-settings.json"
                    );


    private static int reflexMode =
            2;

    private static int cloudModelVersion = readCloudModelVersionFile();

    private static int readCloudModelVersionFile() {
        try {
            java.nio.file.Path p =
                    java.nio.file.Paths.get("config", "cloud-model.txt");

            if (java.nio.file.Files.exists(p)) {
                String s = new String(
                        java.nio.file.Files.readAllBytes(p),
                        java.nio.charset.StandardCharsets.UTF_8).trim();

                int v = Integer.parseInt(s);

                if (v >= 2 && v <= 3) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }

        return 3;
    }

    public static synchronized int getCloudModelVersion() {
        return cloudModelVersion;
    }

    public static synchronized void setCloudModelVersion(int v) {
        if (v < 2 || v > 3) {
            throw new IllegalArgumentException("Invalid cloudModelVersion: " + v);
        }

        if (cloudModelVersion != v) {
            cloudModelVersion = v;

            try {
                java.nio.file.Files.write(
                        java.nio.file.Paths.get("config", "cloud-model.txt"),
                        String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("[RTX CONFIG] write cloud-model.txt failed: " + e.getMessage());
            }
        }
    }

    public static final int RR_MODE_PERFORMANCE = 0;
    public static final int RR_MODE_BALANCED = 1;
    public static final int RR_MODE_QUALITY = 2;
    public static final int RR_MODE_DLAA = 3;
    public static final int RR_MODE_ULTRA_PERFORMANCE = 4;
    public static final int UPSCALER_MODE_RR = 0;
    public static final int UPSCALER_MODE_SR = 1;

    private static int rrMode =
            RR_MODE_PERFORMANCE;
    private static int upscalerMode = UPSCALER_MODE_RR;
    private static int srMode = RR_MODE_PERFORMANCE;


    private static float noDirectLightAmbient =
            0.0001f;



    private static float pomDepth =
            0.03f;

    private static boolean pomSharp =
            false;

    public static final int UE_CLOUD_PARAM_COUNT =
            8;

    private static float[] ueCloud = {
            1.0f,
            0.5f,
            0.5f,
            1.0f,
            0.15f,
            1.0f,
            0.35f,
            1.0f
    };
    private static boolean trueSubsurfaceScattering =
            false;

    private static float localLightMaxDistance =
            15.0f;

    private static float localLightIntensity =
            1.0f;

    private static float dynamicLightIntensity =
            1.0f;

    private static float postBloomStrength =
            0.0f;

    private static float postGainR =
            1.0f;
    private static float postGainG =
            1.0f;
    private static float postGainB =
            1.0f;

    public static final SunVisualSettings DEFAULT_SUN_VISUAL_SETTINGS =
            new SunVisualSettings(0.98f, 0.12f, 0.30f);

    private static SunVisualSettings sunVisualSettings =
            DEFAULT_SUN_VISUAL_SETTINGS;



    public static final AtmosphereSettings DEFAULT_ATMOSPHERE_SETTINGS =
            new AtmosphereSettings(2.00f, 1.00f, true, 0.60f, 0.70f);

    private static AtmosphereSettings atmosphereSettings =
            DEFAULT_ATMOSPHERE_SETTINGS;

    private static float powderStrength =
            3.0f;

    private static float cloudSunForwardScattering =
            1.0f;

    private static float auroraBrightness =
            1.0f;

    private static float auroraFlowSpeed =
            1.0f;


    private static float cloudSunReddening =
            0.0f;

    private static float cloudTopStretch =
            0.0f;

    private static float cloudDetileStrength =
            10.0f;

    private static float cloudType = 0.8f;
    private static float cloudErosionScale = 1.0f;

    private static float cloudWeatherScale = 0.75f;
    private static float cloudSilver = 0.08f;
    private static float cloudViewDistance = 25000.0f;

    public static final VolumetricFogSettings DEFAULT_VOLUMETRIC_FOG_SETTINGS =
            new VolumetricFogSettings(true, 1.0f, 65.0f, 50.0f, 0.65f,
                    1.0f, 1.0f, 672.0f, 0.0f, 4, 2.0f);

    private static VolumetricFogSettings volumetricFogSettings =
            DEFAULT_VOLUMETRIC_FOG_SETTINGS;


    public static final VolumetricLightSettings DEFAULT_VOLUMETRIC_LIGHT_SETTINGS =
            new VolumetricLightSettings(true, 12, 1, 1.0f,
                    0.85f);

    private static VolumetricLightSettings volumetricLightSettings =
            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS;

    private static final int CLOUD_MODEL_VERSION = 2;
    private static final int WATER_WAVE_VERSION = 1;

    public static final float[] DEFAULT_CLOUD_HP = {
            0.55f, 0.7f, 0.85f, 0.08f, 0.4f, 0.15f, 0.75f, 2.0f, 0.45f, 0.45f, 2.2f, 1.0f, 1.0f, 0.25f, 0.35f, 0.6f, 0.85f, 0.3f, 0.6f, 1.0f, 0.0f, 1.0f, 0.1f, 1.0f, 0.5f, 1.0f, 0.3f, 1.0f
    };
    public static final String[] CLOUD_HP_LABELS = {
            "Cu 侵蚀强度",
            "Tcu 侵蚀强度",
            "Cb 侵蚀强度",
            "云底平滑高度",
            "Wispy 边缘宽度",
            "Wispy 外延",
            "Wispy 顶高",
            "Wispy 顶硬度",
            "Wispy 权重",
            "Billowy 权重",
            "顶部拉伸上限",
            "顶部拉伸强度",
            "覆盖度标定",
            "Sc 强度",
            "Sc 高度压缩",
            "Sc 胞厚",
            "前向偏心率",
            "后向偏心率",
            "phi 前向强度",
            "phi 深度幂",
            "phi 深度偏移",
            "phi 压缩",
            "散射 OD 尺度",
            "散射曲线幂",
            "MS 偏心率",
            "AO 向上尺度",
            "远处淡出起点",
            "粉末效应（保留）"
    };
    public static final float[][] CLOUD_HP_RANGES = {
            { 0.0f, 1.5f, 0.01f },
            { 0.0f, 1.5f, 0.01f },
            { 0.0f, 1.5f, 0.01f },
            { 0.0f, 0.3f, 0.005f },
            { 0.01f, 1.0f, 0.01f },
            { 0.0f, 0.5f, 0.01f },
            { 0.0f, 0.99f, 0.01f },
            { 0.0f, 4.0f, 0.1f },
            { 0.0f, 2.0f, 0.01f },
            { 0.0f, 2.0f, 0.01f },
            { 1.0f, 3.0f, 0.05f },
            { 0.0f, 1.0f, 0.01f },
            { 0.2f, 3.0f, 0.05f },
            { 0.0f, 1.0f, 0.01f },
            { 0.05f, 1.0f, 0.01f },
            { 0.0f, 1.0f, 0.01f },
            { 0.0f, 1.0f, 0.01f },
            { 0.0f, 1.0f, 0.01f },
            { 0.0f, 2.0f, 0.01f },
            { 0.0f, 2.0f, 0.01f },
            { -0.3f, 0.5f, 0.01f },
            { 0.0f, 3.0f, 0.05f },
            { 0.01f, 0.5f, 0.005f },
            { 0.5f, 3.0f, 0.05f },
            { 0.0f, 1.0f, 0.01f },
            { 0.0f, 2.0f, 0.01f },
            { 0.0f, 0.95f, 0.01f },
            { 0.0f, 1.0f, 0.01f }
    };

    public record CloudHpSettings(float[] values) {
        public static final int COUNT = 28;

        public CloudHpSettings {
            if(values == null || values.length != COUNT)
                throw new IllegalArgumentException("cloud hp needs " + COUNT + " values");
            values = values.clone();
            for(int i = 0; i < COUNT; ++i)
            {
                if(!Float.isFinite(values[i]))
                    throw new IllegalArgumentException("Invalid cloud hp value #" + i);
                float lo = CLOUD_HP_RANGES[i][0];
                float hi = CLOUD_HP_RANGES[i][1];
                values[i] = Math.min(Math.max(values[i], lo), hi);
            }
        }

        /** 旧存档 / 长度不符 / 含 NaN 时回落出厂值，其余逐项钳制。 */
        public static CloudHpSettings sanitized(float[] raw) {
            float[] out = DEFAULT_CLOUD_HP.clone();
            if(raw != null)
            {
                for(int i = 0; i < COUNT && i < raw.length; ++i)
                {
                    if(Float.isFinite(raw[i])) out[i] = raw[i];
                }
            }
            return new CloudHpSettings(out);
        }

        public float value(int index) {
            return values[index];
        }

        public float[] toParamArray() {
            return values.clone();
        }
    }

    private static CloudHpSettings cloudHpSettings =
            new CloudHpSettings(DEFAULT_CLOUD_HP.clone());
    public static synchronized CloudHpSettings getCloudHpSettings() { return cloudHpSettings; }
    public static synchronized void setCloudHpSettings(CloudHpSettings settings) {
        cloudHpSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    public static final float[] DEFAULT_COLOR_GRADE = {
            0.00f, 0.00f, 1.00f, 1.00f, 0.18f,
            0.00f, 0.00f, 0.00f,
            1.00f, 1.00f, 1.00f,
            1.00f, 1.00f, 1.00f,
            200.0f, 0.00f, 45.00f, 0.00f,
            0.00f, 0.00f, 0.00f, 0.60f, 0.00f, 0.00f
    };
    public static final String[] COLOR_GRADE_LABELS = {
            "手动曝光（档）",
            "黑点",
            "白点",
            "显示对比",
            "对比支点",
            "Lift R（阴影偏红）",
            "Lift G（阴影偏绿）",
            "Lift B（阴影偏蓝）",
            "Gamma R（中间调）",
            "Gamma G",
            "Gamma B",
            "Gain R（高光偏红）",
            "Gain G",
            "Gain B",
            "阴影色相（度）",
            "阴影着色强度",
            "高光色相（度）",
            "高光着色强度",
            "自然饱和度",
            "褪色 / 发灰",
            "暗角强度",
            "暗角半径",
            "色相旋转（度）",
            "绿 / 品红"
    };
    public static final float[][] COLOR_GRADE_RANGES = {
            { -4.0f, 4.0f, 0.05f },
            { 0.0f, 0.3f, 0.005f },
            { 0.7f, 1.5f, 0.01f },
            { 0.5f, 2.0f, 0.01f },
            { 0.05f, 0.95f, 0.01f },
            { -0.15f, 0.15f, 0.005f },
            { -0.15f, 0.15f, 0.005f },
            { -0.15f, 0.15f, 0.005f },
            { 0.5f, 2.0f, 0.01f },
            { 0.5f, 2.0f, 0.01f },
            { 0.5f, 2.0f, 0.01f },
            { 0.5f, 2.0f, 0.01f },
            { 0.5f, 2.0f, 0.01f },
            { 0.5f, 2.0f, 0.01f },
            { 0.0f, 360.0f, 1.0f },
            { 0.0f, 1.0f, 0.01f },
            { 0.0f, 360.0f, 1.0f },
            { 0.0f, 1.0f, 0.01f },
            { -1.0f, 1.0f, 0.01f },
            { 0.0f, 1.0f, 0.01f },
            { 0.0f, 1.0f, 0.01f },
            { 0.1f, 1.2f, 0.01f },
            { -180.0f, 180.0f, 1.0f },
            { -1.0f, 1.0f, 0.01f }
    };

    public record ColorGradeSettings(float[] values) {
        public static final int COUNT = 24;

        public ColorGradeSettings {
            if(values == null || values.length != COUNT)
                throw new IllegalArgumentException("color grade needs " + COUNT + " values");
            values = values.clone();
            for(int i = 0; i < COUNT; ++i)
            {
                if(!Float.isFinite(values[i]))
                    throw new IllegalArgumentException("Invalid color grade value #" + i);
                float lo = COLOR_GRADE_RANGES[i][0];
                float hi = COLOR_GRADE_RANGES[i][1];
                values[i] = Math.min(Math.max(values[i], lo), hi);
            }
        }

        /** 旧存档 / 长度不符 / 含 NaN 时回落出厂值，其余逐项钳制。 */
        public static ColorGradeSettings sanitized(float[] raw) {
            float[] out = DEFAULT_COLOR_GRADE.clone();
            if(raw != null)
            {
                for(int i = 0; i < COUNT && i < raw.length; ++i)
                {
                    if(Float.isFinite(raw[i])) out[i] = raw[i];
                }
            }
            return new ColorGradeSettings(out);
        }

        public float value(int index) {
            return values[index];
        }

        public float[] toParamArray() {
            return values.clone();
        }
    }

    private static ColorGradeSettings colorGradeSettings =
            new ColorGradeSettings(DEFAULT_COLOR_GRADE.clone());
    public static synchronized ColorGradeSettings getColorGradeSettings() { return colorGradeSettings; }
    public static synchronized void setColorGradeSettings(ColorGradeSettings settings) {
        colorGradeSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    public record CloudMsSettings(
            float phiIntensity,
            float phiDepthPow,
            float phiDepthBias,
            float octaveStrength) {
        public CloudMsSettings {
            if(!Float.isFinite(phiIntensity) || phiIntensity < 0 || phiIntensity > 6 ||
               !Float.isFinite(phiDepthPow) || phiDepthPow < 0 || phiDepthPow > 2 ||
               !Float.isFinite(phiDepthBias) || phiDepthBias < -0.5f || phiDepthBias > 0.5f ||
               !Float.isFinite(octaveStrength) || octaveStrength < 0 || octaveStrength > 1)
                throw new IllegalArgumentException("Invalid cloud ms settings");
        }

        public float[] toParamArray() {
            return new float[] { phiIntensity, phiDepthPow, phiDepthBias, octaveStrength };
        }
    }
    private static CloudMsSettings cloudMsSettings = new CloudMsSettings(
            1.5f, 1.0f, 0.0f, 0.5f);
    public static synchronized CloudMsSettings getCloudMsSettings() { return cloudMsSettings; }
    public static synchronized void setCloudMsSettings(CloudMsSettings settings) {
        cloudMsSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 参考重构（2026-10-02）phi_fwd 四件套。与 HPVolumeCloud VolumetricClouds.hlsl
    // 的 _HP_HpHpPhiFwd_* uniform 同名同义；toParamArray() 是唯一下发入口
    // （JNI setCloudPhiParams → CameraUBO.cloudPhiParams，偏移 1072 尾块）。
    //   p0 = 边界受光置信度（1 = 参考全量：顶高代理的 2D 高度差分 wrap 光照；
    //        0 = 关，C_top 恒 1）
    //   p1 = 各向同性建立速率 MSBuildScale（参考建议 1.0）
    //   p2 = 最终软饱和 Compress（0 = 线性；用来压厚芯过曝）
    //   p3 = 锥形采样步长比率 CONE_RATIO（参考是编译期宏 2.0）
    // 负值对 p1/p2/p3 有特殊含义：着色器侧**回落到编译期宏**（A/B 通道），
    // 所以校验下界放到 -1；只有 BoundaryConfidence 钳在 [0,1]。
    public record CloudPhiSettings(
            float boundaryConfidence,
            float msBuildScale,
            float compress,
            float coneRatio) {
        public CloudPhiSettings {
            if(!Float.isFinite(boundaryConfidence) || boundaryConfidence < 0 || boundaryConfidence > 1 ||
               !Float.isFinite(msBuildScale) || msBuildScale < -1 || msBuildScale > 8 ||
               !Float.isFinite(compress) || compress < -1 || compress > 8 ||
               !Float.isFinite(coneRatio) || coneRatio < -1 || coneRatio > 6)
                throw new IllegalArgumentException("Invalid cloud phi settings");
        }

        public float[] toParamArray() {
            return new float[] { boundaryConfidence, msBuildScale, compress, coneRatio };
        }
    }
    private static CloudPhiSettings cloudPhiSettings = new CloudPhiSettings(
            1.0f, 1.0f, 1.5f, 2.0f);
    public static synchronized CloudPhiSettings getCloudPhiSettings() { return cloudPhiSettings; }
    public static synchronized void setCloudPhiSettings(CloudPhiSettings settings) {
        cloudPhiSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 参考重构（2026-10-02）受光逻辑的三个尺度量。与 HPVolumeCloud
    // VolumetricClouds.hlsl 的 _HP_ScatterSourceODScale / _HP_ScatterSourceCurvePow /
    // _HP_AOUpwardScale 同名同义；toParamArray() 是唯一下发入口
    // （JNI setCloudLightParams → CameraUBO.cloudLightParams，偏移 1088 尾块）。
    //   l0 = 有效源强曲线尺度（越小 ⇒ 薄边越早饱和；很大 ⇒ 线性）
    //   l1 = 该曲线指数（>1 更压薄边）
    //   l2 = 环境 upward-AO 强度（1.0 = 物理值；0 = 不做天顶遮蔽）
    // 负值对 l0/l1 表示"回落到着色器编译期宏"（A/B 通道）；l2 用 >= 0 判定，
    // 所以 -1 是它的回落值。校验下界放 -1。
    public record CloudLightSettings(
            float sourceOdScale,
            float sourceCurvePow,
            float aoUpwardScale,
            float sunsetAmbientTint) {
        public CloudLightSettings {
            if(!Float.isFinite(sourceOdScale) || sourceOdScale < -1 || sourceOdScale > 8 ||
               !Float.isFinite(sourceCurvePow) || sourceCurvePow < -1 || sourceCurvePow > 8 ||
               !Float.isFinite(aoUpwardScale) || aoUpwardScale < -1 || aoUpwardScale > 8 ||
               !Float.isFinite(sunsetAmbientTint) || sunsetAmbientTint < 0 || sunsetAmbientTint > 1)
                throw new IllegalArgumentException("Invalid cloud light settings");
        }

        public float[] toParamArray() {
            return new float[] {
                sourceOdScale, sourceCurvePow, aoUpwardScale, sunsetAmbientTint };
        }
    }
    private static CloudLightSettings cloudLightSettings = new CloudLightSettings(
            1.0f, 1.0f, 1.0f, 0.6f);
    public static synchronized CloudLightSettings getCloudLightSettings() { return cloudLightSettings; }
    public static synchronized void setCloudLightSettings(CloudLightSettings settings) {
        cloudLightSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

 // 平面云。9 个分量对应 CameraUBO.highCloudParams
    // 的前 9 个 lane（偏移 944）与 high_cloud_field.glsl 末尾的 sampleHighCloud()。
    // toParamArray() 是唯一下发入口（JNI setPlaneCloudParams）。
    //   0 启用（0/1）
    //   1 高度偏移（距体积云顶，格；可为负 —— 把平面云压进体积云里做 A/B）
    //   2 尺度（云团大小倍率）
    //   3 速度（风场速度倍率）
    //   4 密度（不透明度曲线斜率）
    //   5 覆盖率（0–1）
    //   6 八度数（1–8）
    //   7 淡出距离（格）
    //   8 亮度
 // 出厂值取自 CloudSettings.glsl 的出厂档（SCALE 1.0 / SPEED 1.0 /
    // DENSITY 4.0 / AMOUNT 0.45 / OCTAVES 5 / FADE 100000），高度改成"距体积云顶
 // +6000 格"以对齐绝对高度 10000。
    // ⚠ 观感**没有在游戏里验证过** —— 移植后的首次出厂，请以实机为准。
    public record PlaneCloudSettings(
            boolean enabled,
            float heightOffset,
            float scale,
            float speed,
            float density,
            float coverage,
            int octaves,
            float fadeDistance,
            float brightness) {
        public PlaneCloudSettings {
            if(!Float.isFinite(heightOffset) || heightOffset < -20000 || heightOffset > 200000 ||
               !Float.isFinite(scale) || scale < 0.01f || scale > 20 ||
               !Float.isFinite(speed) || speed < 0 || speed > 20 ||
               !Float.isFinite(density) || density < 0 || density > 100 ||
               !Float.isFinite(coverage) || coverage < 0 || coverage > 1 ||
               octaves < 1 || octaves > 8 ||
               !Float.isFinite(fadeDistance) || fadeDistance < 1000 || fadeDistance > 300000 ||
               !Float.isFinite(brightness) || brightness < 0 || brightness > 6)
                throw new IllegalArgumentException("Invalid plane cloud settings");
        }

        public float[] toParamArray() {
            return new float[] {
                enabled ? 1.0f : 0.0f,
                heightOffset, scale, speed, density, coverage,
                octaves, fadeDistance, brightness };
        }
    }
    private static PlaneCloudSettings planeCloudSettings = new PlaneCloudSettings(
            true, 6000.0f, 1.0f, 1.0f, 4.0f, 0.45f, 5, 100000.0f, 1.0f);
    public static synchronized PlaneCloudSettings getPlaneCloudSettings() { return planeCloudSettings; }
    public static synchronized void setPlaneCloudSettings(PlaneCloudSettings settings) {
        planeCloudSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 参考重构（2026-10-02）云底软化（形状）+ 两级步进阈值。与
    // HPVolumeCloud 的 _HP_BottomSmoothHeight / _HP_BottomSmoothPow 同义，
    // 第三个分量是参考 HI_CLOUD_DENSITY_THRESHOLD 的对应物。
    // **密度场共享**：写到 CameraUBO.cloudShapeParams（偏移 1104），
    // 视图 / 云影 / 反射 / 光柱四个消费者都读它 ⇒ 改一处四处同变（这是要点）。
    // 负值对前两项 = 回落编译期宏；0 是第一项的合法值（关闭云底软化）。
    public record CloudShapeSettings(
            float bottomSmoothHeight,
            float bottomSmoothPow,
            float twoTierThreshold) {
        public CloudShapeSettings {
            if(!Float.isFinite(bottomSmoothHeight) || bottomSmoothHeight < -1 || bottomSmoothHeight > 1 ||
               !Float.isFinite(bottomSmoothPow) || bottomSmoothPow < -1 || bottomSmoothPow > 8 ||
               !Float.isFinite(twoTierThreshold) || twoTierThreshold < -1 || twoTierThreshold > 0.5f)
                throw new IllegalArgumentException("Invalid cloud shape settings");
        }

        public float[] toParamArray() {
            return new float[] { bottomSmoothHeight, bottomSmoothPow, twoTierThreshold, 0.0f };
        }
    }
    private static CloudShapeSettings cloudShapeSettings = new CloudShapeSettings(
            0.22f, 1.0f, 0.004f);
    public static synchronized CloudShapeSettings getCloudShapeSettings() { return cloudShapeSettings; }
    public static synchronized void setCloudShapeSettings(CloudShapeSettings settings) {
        cloudShapeSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 参考重构（2026-10-02）物理月光项（CameraUBO.cloudMoonParams，偏移 1120）。
    // 射线生成器已把日落后光源换成月亮并压到正午的 3%，这里补的是**月相**：
    // 夜间直射与漫射一起按被照亮比例缩放（白天不参与）。默认 1.0/1.0 = 不改画面。
    // 负值 = 回落默认（着色器侧）。
    public record CloudMoonSettings(
            float moonStrength,
            float moonPhase) {
        public CloudMoonSettings {
            if(!Float.isFinite(moonStrength) || moonStrength < -1 || moonStrength > 4 ||
               !Float.isFinite(moonPhase) || moonPhase < -1 || moonPhase > 1)
                throw new IllegalArgumentException("Invalid cloud moon settings");
        }

        public float[] toParamArray() {
            return new float[] { moonStrength, moonPhase, 0.0f, 0.0f };
        }
    }
    private static CloudMoonSettings cloudMoonSettings = new CloudMoonSettings(1.0f, 1.0f);
    public static synchronized CloudMoonSettings getCloudMoonSettings() { return cloudMoonSettings; }
    public static synchronized void setCloudMoonSettings(CloudMoonSettings settings) {
        cloudMoonSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 第四阶段（2026-10-02）细节/形状旋钮（CameraUBO.cloudShape2Params，偏移 1136）。
    // 参考同名量：_HP_DetailScale（细节频率倍率）与细节对比、廓形曲线（HDRP 的
    // _DensityProfile LUT 的解析等价）。**密度场共享** ⇒ 四个消费者同源。
    // <=0 = 回落着色器默认（1.0）。
    public record CloudShape2Settings(
            float detailScale,
            float detailContrast,
            float profileCurve) {
        public CloudShape2Settings {
            if(!Float.isFinite(detailScale) || detailScale < -1 || detailScale > 8 ||
               !Float.isFinite(detailContrast) || detailContrast < -1 || detailContrast > 8 ||
               !Float.isFinite(profileCurve) || profileCurve < -1 || profileCurve > 8)
                throw new IllegalArgumentException("Invalid cloud shape2 settings");
        }

        public float[] toParamArray() {
            return new float[] { detailScale, detailContrast, profileCurve, 0.0f };
        }
    }
    private static CloudShape2Settings cloudShape2Settings = new CloudShape2Settings(
            1.0f, 1.0f, 1.0f);
    public static synchronized CloudShape2Settings getCloudShape2Settings() { return cloudShape2Settings; }
    public static synchronized void setCloudShape2Settings(CloudShape2Settings settings) {
        cloudShape2Settings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // =====================================================================
    // 2026-10-02 参考重构 #2~#7。五组各持 4 个分量，分别写到 CameraUBO 的
    // 1152 / 1168 / 1184 / 1200 / 1216 尾块（每块 16 字节，UBO 1152 → 1232）。
    // 出厂值 = 参考口径；每组都能用明确的值回退旧档（见字段注释与 GUI 标签）。
    // **Profile / Detail 两组是密度场共享**（视图 / 云影 / 反射 / 光柱同源）。
    // =====================================================================
    // #2 环境光接天空（CameraUBO.cloudAmbientParams）。参考
    // EvaluateEnvironmentLighting（SH9 探针 × _HP_AmbientTop/BottomMultiplier）；
    // 我们没有探针也不新增描述符，改用**已有天空模型**解析天光色相（亮度归一 ⇒
    // 只改色彩走向，不整体调亮）。
    //   a0 天顶环境倍率（1 = 旧档常量亮度）
    //   a1 地面环境倍率（1 = 旧档）
    //   a2 朝阳侧暖环境强度（0.35 出厂；0 = 关 = 旧档）
    //   a3 天光色相跟随（1 = 跟随当前阳光色相；0 = 固定冷蓝 = 旧档）
    public record CloudAmbientSettings(
            float topMultiplier,
            float bottomMultiplier,
            float sunSideStrength,
            float hueFollow) {
        public CloudAmbientSettings {
            if(!Float.isFinite(topMultiplier) || topMultiplier < 0 || topMultiplier > 3 ||
               !Float.isFinite(bottomMultiplier) || bottomMultiplier < 0 || bottomMultiplier > 3 ||
               !Float.isFinite(sunSideStrength) || sunSideStrength < 0 || sunSideStrength > 2 ||
               !Float.isFinite(hueFollow) || hueFollow < 0 || hueFollow > 1)
                throw new IllegalArgumentException("Invalid cloud ambient settings");
        }

        public float[] toParamArray() {
            return new float[] { topMultiplier, bottomMultiplier, sunSideStrength, hueFollow };
        }
    }
    private static CloudAmbientSettings cloudAmbientSettings = new CloudAmbientSettings(
            1.0f, 1.0f, 0.35f, 1.0f);
    public static synchronized CloudAmbientSettings getCloudAmbientSettings() { return cloudAmbientSettings; }
    public static synchronized void setCloudAmbientSettings(CloudAmbientSettings settings) {
        cloudAmbientSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-14 日落天光修正（CameraUBO.cloudSkyHueParams，偏移 1472）。
    // 用户报告：日落时体积云"整个被染色、背光面不暗"。
    // 量化根因：背光面 99.6% 的亮度来自环境项，而环境项的色相直接取自**直射阳光**
    //（skyLightHue = cloudHueOf(directLightColor)）。日落直射是高饱和橘红 ⇒ 整块云
    // 都橙；而物理上天顶天光是蓝紫（瑞利散射把蓝光送到天顶），且能量应随太阳高度
    // 显著衰减（实测环境光亮度日落/正午只掉 4%，直射掉 55%）。
    //   x skyHueCool   天光冷化强度（0 = 关 = 逐位旧档；1 = 曙暮窗内天光色相完全
    //                  转向暮色冷紫。同时作用于第二层"夕阳环境染色"）
    //   y skyHueEnergy 天光能量衰减强度（0 = 关 = 旧曲线；1 = daylight 的 smoothstep
    //                  上界 0.15 → 0.35，日落时环境光再掉约 35%）
    // 出厂 0.65 / 0.50（用户"B 先试试"档）。两项都是**保亮度**或**只压能量**的
    // 改动，不会把云整体调亮。
    //
    // 2026-10-17 追加两字段（原 UBO .z/.w 保留位）：
    //   skyDirStrength ② 方向性天光强度 [0,2]（出厂 0 = 关 = 逐位旧档）。
    //     天光色相源从「直射阳光色相」换成「三方向 clearSkyInscatter 的 HG 加权
    //     混合」⇒ 日落时背光面不再被染橙、朝阳侧偏暖。只改色相（亮度归一）。
    //     强度 1 时「天光冷化」不再有可见效果（色相已由天空模型给出）。
    //   ambientUnify 顶底性质统一强度 [0,1]（出厂 0 = 关 = 逐位旧档）。
    //     云底基色 := 天顶天光 × 地面反照率（与顶同一色相源、亮度比 = 真实
    //     反照率），直射项高度增益 0.68+0.38h 归零。
    //     ⚠ 强度 1 时「地面环境倍率」失效（由反照率取代，这是统一的目的）。
    //     ⚠ 不接管「云底遮蔽强度」：那条竖直消光口径（0.02/步 × 638 格）尚未裁定，
    //       绑在一起会顺手把云内打成全黑。
    public record CloudSkyHueSettings(
            float skyHueCool,
            float skyHueEnergy,
            float skyDirStrength,
            float ambientUnify) {
        public CloudSkyHueSettings {
            if(!Float.isFinite(skyHueCool) || skyHueCool < 0 || skyHueCool > 1 ||
               !Float.isFinite(skyHueEnergy) || skyHueEnergy < 0 || skyHueEnergy > 1 ||
               !Float.isFinite(skyDirStrength) || skyDirStrength < 0 || skyDirStrength > 2 ||
               !Float.isFinite(ambientUnify) || ambientUnify < 0 || ambientUnify > 1)
                throw new IllegalArgumentException("Invalid cloud sky hue settings");
        }

        public float[] toParamArray() {
            return new float[] { skyHueCool, skyHueEnergy, skyDirStrength, ambientUnify };
        }
    }
    private static CloudSkyHueSettings cloudSkyHueSettings = new CloudSkyHueSettings(
            0.65f, 0.50f, 0.0f, 0.0f);
    public static synchronized CloudSkyHueSettings getCloudSkyHueSettings() { return cloudSkyHueSettings; }
    public static synchronized void setCloudSkyHueSettings(CloudSkyHueSettings settings) {
        cloudSkyHueSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-15 云地面反射与视觉回退参数（CameraUBO.cloudPhysicsParams，偏移 1488）。
    // x groundAlbedo：均匀地面 Lambert 反照率 [0,0.8]（出厂 0.20）。
    // y groundBounce：直射日光地面反射近似强度 [0,1]（出厂 1.0）。
    // z sunlightResidual：非物理自阴影残光回退 [0,0.25]（出厂 0）。
    // w phaseFloor：非物理黄金/月光相位地板回退 [0,1]（出厂 0）。
    public record CloudPhysicsSettings(
            float groundAlbedo,
            float groundBounce,
            float sunlightResidual,
            float phaseFloor) {
        public CloudPhysicsSettings {
            if(!Float.isFinite(groundAlbedo) || groundAlbedo < 0 || groundAlbedo > 0.8f ||
               !Float.isFinite(groundBounce) || groundBounce < 0 || groundBounce > 1 ||
               !Float.isFinite(sunlightResidual) || sunlightResidual < 0 || sunlightResidual > 0.25f ||
               !Float.isFinite(phaseFloor) || phaseFloor < 0 || phaseFloor > 1)
                throw new IllegalArgumentException("Invalid cloud physics settings");
        }

        public float[] toParamArray() {
            return new float[] { groundAlbedo, groundBounce, sunlightResidual, phaseFloor };
        }
    }
    private static CloudPhysicsSettings cloudPhysicsSettings = new CloudPhysicsSettings(
            0.20f, 1.0f, 0.0f, 0.0f);
    public static synchronized CloudPhysicsSettings getCloudPhysicsSettings() { return cloudPhysicsSettings; }
    public static synchronized void setCloudPhysicsSettings(CloudPhysicsSettings settings) {
        cloudPhysicsSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // #6 散射染色 + MS 级联三参数（CameraUBO.cloudMsParams2）。参考 _ScatteringTint
    // 与 _HP_MS_Attenuation/_Contribution/_Eccentricity（Contribution 沿用既有
    // cloudMsParams.w）。
    //   q0 散射染色强度（0.35 出厂；0 = 白 = 旧档）
    //   q1 MS 级数 1..3（2 出厂 = 旧档两项；3 = 参考的第三项，厚云透光底更足）
    //   q2 MS 衰减率（0 = 回落编译期宏 0.35）
    //   q3 MS 偏心率衰减率（0 = 回落编译期宏 0.5）
    public record CloudMs2Settings(
            float scatterTint,
            float octaves,
            float attenuation,
            float eccentricity) {
        public CloudMs2Settings {
            if(!Float.isFinite(scatterTint) || scatterTint < 0 || scatterTint > 1 ||
               !Float.isFinite(octaves) || octaves < 1 || octaves > 3 ||
               !Float.isFinite(attenuation) || attenuation < 0 || attenuation > 1 ||
               !Float.isFinite(eccentricity) || eccentricity < 0 || eccentricity > 1)
                throw new IllegalArgumentException("Invalid cloud ms2 settings");
        }

        public float[] toParamArray() {
            return new float[] { scatterTint, octaves, attenuation, eccentricity };
        }
    }
    private static CloudMs2Settings cloudMs2Settings = new CloudMs2Settings(
            0.35f, 2.0f, 0.5f, 0.5f);
    public static synchronized CloudMs2Settings getCloudMs2Settings() { return cloudMs2Settings; }
    public static synchronized void setCloudMs2Settings(CloudMs2Settings settings) {
        cloudMs2Settings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // #5 光路太阳色 + #7 粉末口径（CameraUBO.cloudLightParams2）。参考
    // EvaluateSunColor(envLighting, relativeRayDistance) 与 PowderEffect（参考**不**
    // 把粉末施加于方向性散射，以保留云缘亮边）。
    //   r0 光路红化强度（0.6 出厂；0 = 旧档：不做光路红化）
    //   r1 云光路大气消光（1.0 出厂；0 = 关 = 逐位旧档）。2026-10-10：逐样本
    //      Chapman 斜程高度增益强度 [0,1] —— 云在 2~4km 上，阳光到达它没穿过
    //      云底以下那一截大气 ⇒ 高空云在低太阳时更亮、更少红化。见
    //      cpp/shaders/sky_chapman_common.glsl。
    //   r2 粉末口径（1 出厂 = 参考：粉末乘环境项、保亮边；0 = 旧档：乘方向性项）
    // 2026-10-03 删除 r3「粉末强度」（cloudShadingPowderStrength）：它是
    // 10-02 参考重构引入的**着色阶段**粉末旋钮，但 populateCloudLightParams2
    // 一直把光步粉末（cloudParams5.w 的 cloudPowderStrength）写进
    // cloudLightParams2.w，本设置存进原子量后从未被读取 —— 一次也没生效过
    // 的死设置。着色阶段粉末跟随云粉状暗边（单一旋钮，两个消费点同值）。
    public record CloudLight2Settings(
            float sunPathReddening,
            float atmosphereExtinction,
            float powderMode) {
        public CloudLight2Settings {
            if(!Float.isFinite(sunPathReddening) || sunPathReddening < 0 || sunPathReddening > 2 ||
               !Float.isFinite(atmosphereExtinction) || atmosphereExtinction < 0 || atmosphereExtinction > 1 ||
               !Float.isFinite(powderMode) || powderMode < 0 || powderMode > 1)
                throw new IllegalArgumentException("Invalid cloud light2 settings");
        }

        public float[] toParamArray() {
            return new float[] { sunPathReddening, atmosphereExtinction, powderMode, 0.0f };
        }
    }
    private static CloudLight2Settings cloudLight2Settings = new CloudLight2Settings(
            0.6f, 1.0f, 1.0f);
    public static synchronized CloudLight2Settings getCloudLight2Settings() { return cloudLight2Settings; }
    public static synchronized void setCloudLight2Settings(CloudLight2Settings settings) {
        cloudLight2Settings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // #3 剖面云型（CameraUBO.cloudProfileParams，**密度场共享**）。参考
    // _CloudLutTexture 的 Cu/Tcu/Cb 三型剖面 + 径向距离项；云型只**相对**缩放既有
    // 锚点 ⇒ 0（Cu）时与旧档逐位一致。
    //   f0 云型 0=Cu / 0.5=Tcu / 1=Cb（0 = 旧档剖面）
    //   f1 云型随区域幅度（0 = 均一 = 旧档）
    public record CloudProfileSettings(
            float cloudType,
            float typeSpread) {
        public CloudProfileSettings {
            if(!Float.isFinite(cloudType) || cloudType < 0 || cloudType > 1 ||
               !Float.isFinite(typeSpread) || typeSpread < 0 || typeSpread > 1)
                throw new IllegalArgumentException("Invalid cloud profile settings");
        }

        public float[] toParamArray() {
            return new float[] { cloudType, typeSpread, 0.0f, 0.0f };
        }
    }
    private static CloudProfileSettings cloudProfileSettings = new CloudProfileSettings(
            0.25f, 0.0f);
    public static synchronized CloudProfileSettings getCloudProfileSettings() { return cloudProfileSettings; }
    public static synchronized void setCloudProfileSettings(CloudProfileSettings settings) {
        cloudProfileSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // #4 两族侵蚀（CameraUBO.cloudDetailParams，**密度场共享**）。参考
    // _HP_Wispy*/Billowy* 一族；我们用形状噪声体此前未使用的 B(胞元7)/A(胞元15)
    // 通道实现，**零额外纹理读**（同一次 textureLod 取 .rba）。
    //   t0 絮状强度（0 = 关 = 旧档）
    //   t1 絮状边缘宽度（0 = 回落编译期宏 0.35）
    //   t2 絮状下沉量（0 = 旧档：不额外压低侵蚀下限）
    //   t3 絮状顶部起始高度（0 = 回落编译期宏 0.45）
    public record CloudDetailSettings(
            float wispyStrength,
            float wispyEdgeWidth,
            float wispyReach,
            float wispyTopHeight) {
        public CloudDetailSettings {
            if(!Float.isFinite(wispyStrength) || wispyStrength < 0 || wispyStrength > 2 ||
               !Float.isFinite(wispyEdgeWidth) || wispyEdgeWidth < 0 || wispyEdgeWidth > 1 ||
               !Float.isFinite(wispyReach) || wispyReach < 0 || wispyReach > 1 ||
               !Float.isFinite(wispyTopHeight) || wispyTopHeight < 0 || wispyTopHeight > 1)
                throw new IllegalArgumentException("Invalid cloud detail settings");
        }

        public float[] toParamArray() {
            return new float[] { wispyStrength, wispyEdgeWidth, wispyReach, wispyTopHeight };
        }
    }
    private static CloudDetailSettings cloudDetailSettings = new CloudDetailSettings(
            0.25f, 0.0f, 0.0f, 0.0f);
    public static synchronized CloudDetailSettings getCloudDetailSettings() { return cloudDetailSettings; }
    public static synchronized void setCloudDetailSettings(CloudDetailSettings settings) {
        cloudDetailSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-23 **体积雾扩展四项**（CameraUBO.fogExtrasParams，偏移 1376）。
    //
 // 这四根 lane 原来是 2026-10-10 「彩虹强度 / 闪电强度 /
    // 晨雾密度 / 晨雾标高」；2026-10-19 体积雾重做后，那三项
    // 在着色器里**一个读取点都没有**（空旋钮），于是这里重新装成四项雾扩展。
    // 每一项都是"0 = 逐位旧档"：
    //   x 动态光源强度 [0,4]：火把/岩浆/发光方块进雾的 in-scatter（0 = 关）
    //   y 地面锚定强度 [0,1]：密度带锚到逐列地面（0 = 绝对世界 Y 带）
    //   z 天空环境项强度 [0,1]：环境入散射接晴空辐亮度（0 = 旧两色插值）
    //   w 时域抖动强度 [0,1]：逐帧低差异相位（0 = 固定 4×4 Bayer）
    // 消费点见 cpp/shaders/volumetric_fog.glsl 头注释。
    public record FogExtrasSettings(
            float dynamicLightStrength,
            float groundAnchorStrength,
            float skyAmbientStrength,
            float temporalJitterStrength) {
        public FogExtrasSettings {
            if(!Float.isFinite(dynamicLightStrength) ||
                    dynamicLightStrength < 0 || dynamicLightStrength > 4 ||
               !Float.isFinite(groundAnchorStrength) ||
                    groundAnchorStrength < 0 || groundAnchorStrength > 1 ||
               !Float.isFinite(skyAmbientStrength) ||
                    skyAmbientStrength < 0 || skyAmbientStrength > 1 ||
               !Float.isFinite(temporalJitterStrength) ||
                    temporalJitterStrength < 0 || temporalJitterStrength > 1)
                throw new IllegalArgumentException("Invalid fog extras settings");
        }

        public float[] toParamArray() {
            return new float[] {
                    dynamicLightStrength,
                    groundAnchorStrength,
                    skyAmbientStrength,
                    temporalJitterStrength };
        }
    }
    private static FogExtrasSettings fogExtrasSettings = new FogExtrasSettings(
            1.0f, 1.0f, 1.0f, 1.0f);
    public static synchronized FogExtrasSettings getFogExtrasSettings() { return fogExtrasSettings; }
    public static synchronized void setFogExtrasSettings(FogExtrasSettings settings) {
        fogExtrasSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-23 雨天地面雾浪（rain-driven ground mist wisps）。
    //
    // 车道：cloudParams4.y/.z/.w + cloudParams9.w（复用旧档 Rayleigh 残留与保留位，
    // 见 cpp/VulkanCore.cpp 的 atomic 段）。着色器实现、噪声/各向异性/域扭曲的
    // 设计说明见 cpp/shaders/volumetric_fog.glsl 的「雨天地面雾浪」常量段。
    //
    // 关键约束：**只在雨天出现** —— 着色器里乘 smoothstep(0, 0.35, 雨强)，
    // 所以晴天无论怎么调都看不到它，也就不需要另做一个"晴天开关"。
    public record FogWispSettings(
            boolean enabled,
            float strength,
            float wavelength,
            float speed) {
        public FogWispSettings {
            if(!Float.isFinite(strength) || strength < 0 || strength > 4 ||
               !Float.isFinite(wavelength) || wavelength < 8 || wavelength > 1024 ||
               !Float.isFinite(speed) || speed < 0 || speed > 8)
                throw new IllegalArgumentException("Invalid fog wisp settings");
        }

        public float[] toParamArray() {
            return new float[] {
                    enabled ? 1.0f : 0.0f, strength, wavelength, speed };
        }
    }
    private static FogWispSettings fogWispSettings =
            new FogWispSettings(true, 1.2f, 180.0f, 0.55f);
    public static synchronized FogWispSettings getFogWispSettings() { return fogWispSettings; }
    public static synchronized void setFogWispSettings(FogWispSettings settings) {
        fogWispSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

 // 2026-10-10 **#3 天光自遮挡 / #4 时域速度抑制**
    //（CameraUBO.cloudTemporalParams，偏移 1392）。三项**出厂 0 = 关 ⇒ 逐位旧档**。
 // x 天光自遮挡强度（Clouds.fsh:187 CalculateCloudSkyVisibility：
    //     从云内样本**竖直向上** march 求天光透过率；0 = 用既有解析近似）
    //   y 天光自遮挡步数（0/1 = 回落着色器编译期 6；上限 16）
 // z 时域速度抑制强度（C3_UpscaleClouds.comp：按相机速度**连续**
    //     衰减 history，取代 `speed > 8 ? 0.58 : 0.95` 的硬台阶）
    //     —— 云底环境光的权重从几何代理 (1−h) 换成真实向下光学深度。与 x 共用
    //     同一条竖直 march（按 h 分配上下采样数，总采样数不变 ⇒ 零新增成本）；
    //     0 = 关 = 逐位旧档，1 = 完全物理 ⇒ 云底只剩最下面一格还有环境光）
    public record CloudTemporalSettings(
            float skyVisibilityStrength,
            float skyVisibilitySteps,
            float temporalDampStrength,
            float bottomOcclusion) {
        public CloudTemporalSettings {
            if(!Float.isFinite(skyVisibilityStrength) || skyVisibilityStrength < 0 || skyVisibilityStrength > 1 ||
               !Float.isFinite(skyVisibilitySteps) || skyVisibilitySteps < 0 || skyVisibilitySteps > 32 ||
               !Float.isFinite(temporalDampStrength) || temporalDampStrength < 0 || temporalDampStrength > 1 ||
               !Float.isFinite(bottomOcclusion) || bottomOcclusion < 0 || bottomOcclusion > 1)
                throw new IllegalArgumentException("Invalid cloud temporal settings");
        }

        public float[] toParamArray() {
            return new float[] {
                    skyVisibilityStrength, skyVisibilitySteps, temporalDampStrength, bottomOcclusion };
        }
    }
    private static CloudTemporalSettings cloudTemporalSettings = new CloudTemporalSettings(
            0.0f, 0.0f, 0.0f, 0.0f);
    public static synchronized CloudTemporalSettings getCloudTemporalSettings() { return cloudTemporalSettings; }
    public static synchronized void setCloudTemporalSettings(CloudTemporalSettings settings) {
        cloudTemporalSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-10 借鉴 **#1 云后空气透视 / #2 地面遮挡衰减**
    //（CameraUBO.cloudAerialParams，偏移 1408）。三项**出厂 0 = 关 ⇒ 逐位旧档**。
    //   x 云层大气透视强度（透射率加权代表点：
    //     用云深代表点采一次解析天空，按云不透明度混进云色；0 = 云不参与大气消透）
    //   y 地面大气透视强度（rgen 解析晴空消透的额外标度；0 = 出厂
    //     AP_BASE_EXTINCTION_SCALE）
    //   z 地面大气透视遮挡衰减（沿视线用远场阴影图求被地形遮挡比例再压 inscatter；
    //     0 = 不衰减）
    public record CloudAerialSettings(
            float cloudAerialStrength,
            float groundAerialStrength,
            float groundAerialOcclusion) {
        public CloudAerialSettings {
            if(!Float.isFinite(cloudAerialStrength) || cloudAerialStrength < 0 || cloudAerialStrength > 2 ||
               !Float.isFinite(groundAerialStrength) || groundAerialStrength < 0 || groundAerialStrength > 2 ||
               !Float.isFinite(groundAerialOcclusion) || groundAerialOcclusion < 0 || groundAerialOcclusion > 1)
                throw new IllegalArgumentException("Invalid cloud aerial settings");
        }

        public float[] toParamArray() {
            return new float[] { cloudAerialStrength, groundAerialStrength, groundAerialOcclusion, 0.0f };
        }
    }
    private static CloudAerialSettings cloudAerialSettings = new CloudAerialSettings(
            0.0f, 0.0f, 0.0f);
    public static synchronized CloudAerialSettings getCloudAerialSettings() { return cloudAerialSettings; }
    public static synchronized void setCloudAerialSettings(CloudAerialSettings settings) {
        cloudAerialSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 批次2（2026-10-02）步进旋钮。4 个分量写入 CameraUBO.cloudMarchParams[4]；
    // toParamArray() 是唯一下发入口（JNI setCloudMarchParams）。
    //   m0 = 自阴影覆盖距离（格，0=旧固定 48·2^n 档，默认 2000；
    //        HP 自适应首步 w0 = coverDist/(2^N-1)，步数用满 quality 档）
    //   m1 = 两级视步进（空样本 cheap 探测 2× 大步跨越，命中才全量采样）
    //   m2 = 远景衰减距离（格=米；<=0 = 关闭。球壳表征在远处会露破绽 —— 曲率压成
    //        碗 / 掠射长弦摊成条带 / 超出云底入射距离后整圈填满 —— 给云一个"完全
    //        消失距离"，自 0.55× 起 smoothstep 淡出，云体溶解进天空。2026-10-02 起
    //        启用，原先这个 lane 是"保留恒 0"）
    //   m3 = 平均深度时域重投影（深度 .g 写透射率加权平均云深，软边更稳）
    public record CloudMarchSettings(
            float adaptiveLightRange,
            boolean twoTierStep,
            boolean meanDepthReproject,
            float farFadeDistance) {
        public CloudMarchSettings {
            if(!Float.isFinite(adaptiveLightRange) || adaptiveLightRange < 0 || adaptiveLightRange > 12000)
                throw new IllegalArgumentException("Invalid cloud march settings");
            if(!Float.isFinite(farFadeDistance) || farFadeDistance < 0 || farFadeDistance > 420000)
                throw new IllegalArgumentException("Invalid cloud far fade distance");
        }

        public float[] toParamArray() {
            return new float[] {
                adaptiveLightRange,
                twoTierStep ? 1.0f : 0.0f,
                farFadeDistance,
                meanDepthReproject ? 1.0f : 0.0f
            };
        }
    }
    private static CloudMarchSettings cloudMarchSettings = new CloudMarchSettings(
            2000.0f, true, true, 80000.0f);
    public static synchronized CloudMarchSettings getCloudMarchSettings() { return cloudMarchSettings; }
    public static synchronized void setCloudMarchSettings(CloudMarchSettings settings) {
        cloudMarchSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 批次3（2026-10-02）水体观感四件套。4 个分量写入 CameraUBO.waterParams[4]
    //（偏移 1056 尾块，只有 rt_test.rgen 声明）；toParamArray() 是唯一下发入口
    //（JNI setWaterParams）。
    //   w0 = 波峰透光强度（0=关，默认 1；HPWater diffT 背光透射：逆光薄波峰
    //        的强前向散射银边）
    //   w1 = 焦散色散（0=关，默认 0.4；法线陡度近似 RGB 色散，红散蓝锐）
    //   w2 = 焦散水深弥散（0=关，默认 0.5；密度随水深向 1 回退）
    //   w3 = 水下物理相位（0=旧档 HG(视线竖直分量)，1=HP 5%Rayleigh+95%Mie
    //        +35% 各向同性化，随太阳/月亮方向）
    public record WaterParamsSettings(
            float crestBacklit,
            float causticDispersion,
            float causticDepthDiffusion,
            boolean physicalPhase) {
        public WaterParamsSettings {
            if(!Float.isFinite(crestBacklit) || crestBacklit < 0 || crestBacklit > 2 ||
               !Float.isFinite(causticDispersion) || causticDispersion < 0 || causticDispersion > 1 ||
               !Float.isFinite(causticDepthDiffusion) || causticDepthDiffusion < 0 || causticDepthDiffusion > 1)
                throw new IllegalArgumentException("Invalid water params settings");
        }

        public float[] toParamArray() {
            return new float[] {
                crestBacklit,
                causticDispersion,
                causticDepthDiffusion,
                physicalPhase ? 1.0f : 0.0f
            };
        }
    }
    private static WaterParamsSettings waterParamsSettings = new WaterParamsSettings(
            1.0f, 0.4f, 0.5f, false);

    // =====================================================================
    // 2026-10-02 水面波面参考重构（HPWater）。分两组：
    //   · 波面法线侧 → CameraUBO.waterWaveParams（偏移 1232，rchit/rgen 读）
    //   · 波动方程侧 → water_sim.comp 的 push constant（不占 UBO lane）
    // 默认值 = 参考口径；每项都能回旧档（见字段注释）。
    // =====================================================================
    // 参考 HPWaterFluidHeightToNormal（中心差分 + 1/(2Δ)）与
    // HPWaterFluidWorldNormalBlend（XZ 相加 / Y 相乘）。
    //   w0 交互场法线标定（1 = 参考 1/(2Δ)；0 = 旧档实机标定 0.19）
    //   w1 交互场法线强度倍率（1 = 不额外缩放）
    //   w2 波面混合模式（1 = 参考；0 = 旧档：两组坡度直接相加）
    //   w3 反射焦散强度（2026-10-20，绝对值 0..8）：拆杆之前反射层直接借用折射那根
    //      「焦散亮度」（随包快照 1.3 / 用户实机 1.1），本字段出厂 4.0 ⇒ 本版默认把
    //      增益斜率拉到旧档的 3.1 倍，就是"反射焦散不明显"的修法；拖回 1.3 即旧观感。
    //      落在 CameraUBO.waterWaveParams 的**原保留 lane**上 ⇒ 复用不改 UBO 布局，
    //      不触发其它消费者重编。它和折射那份分开，是因为两者照的面完全不同
    //      （折射 = 水下地板，反射 = 朝水的天花板）。
    //      注意 toParamArray() 里它排在 index 4 而**不是** index 3：index 3 是旧 jar
    //      一直在发的常量占位，改成真值会让"只换 DLL 不换 jar"把反射焦散静悄悄关成 0。
    public record WaterWaveSettings(
            float calibration,
            float strength,
            float blend,
            float reflectCausticStrength) {
        public WaterWaveSettings {
            if(!Float.isFinite(calibration) || calibration < 0 || calibration > 1 ||
               !Float.isFinite(strength) || strength < 0 || strength > 2 ||
               !Float.isFinite(blend) || blend < 0 || blend > 1 ||
               !Float.isFinite(reflectCausticStrength) ||
               reflectCausticStrength < 0 || reflectCausticStrength > 8)
                throw new IllegalArgumentException("Invalid water wave settings");
        }

        public float[] toParamArray() {
            return new float[] { calibration, strength, blend, 0.0f,
                reflectCausticStrength };
        }
    }
    private static WaterWaveSettings waterWaveSettings = new WaterWaveSettings(
            0.0f, 1.0f, 0.0f, 4.0f);
    public static synchronized WaterWaveSettings getWaterWaveSettings() { return waterWaveSettings; }
    public static synchronized void setWaterWaveSettings(WaterWaveSettings settings) {
        waterWaveSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 参考 HPWaterWaveEquation.compute 的四个量：
    //   s0 海绵层宽度（参考 thickness = width*0.1；0 = 关 ⇒ 回旧档圆形幅度淡出）
    //   s1 高斯波源（参考的高斯凸起；0 = 旧档同心余弦环）
    //   s2 阻尼倍率（参考 _DampingFactor 的等价旋钮；1 = 与今天一致）
    //   s3 波场传播速度（参考 _WaveSpeed 的等价物）
    // 注：参考的「障碍物反射」（IsObstacle + 诺伊曼边界）已按需求移除 ——
    // 连同它的 binding 3 / 每帧描述符补写 / GUI 旋钮一起删除。
    public record WaterSimWaveSettings(
            float spongeWidth,
            float gaussianSource,
            float dampingScale,
            float propagationSpeed) {
        public WaterSimWaveSettings {
            if(!Float.isFinite(spongeWidth) || spongeWidth < 0 || spongeWidth > 0.45f ||
               !Float.isFinite(gaussianSource) || gaussianSource < 0 || gaussianSource > 1 ||
               !Float.isFinite(dampingScale) || dampingScale < 0 || dampingScale > 3 ||
               !Float.isFinite(propagationSpeed) || propagationSpeed < 0 || propagationSpeed > 2)
                throw new IllegalArgumentException("Invalid water sim wave settings");
        }

        public float[] toParamArray() {
            return new float[] {
                spongeWidth, gaussianSource, dampingScale, propagationSpeed };
        }
    }
    // 2026-10-03 薄层 SSS 四件套（CameraUBO.waterSssParams，偏移 1264，rt_test.rgen 读）：
    //   非线性光程混合（参考 HPWATER_SSS_NONLINEAR_STRENGTH = 0.5）
    //   散射能量补偿（参考 HPWATER_SSS_SCATTER_BOOST = 2.0）
    //   散射相位各向异性 g（参考 _PhaseG ≈ 0.8）
    //   总强度倍率（1 = 参考口径，0 = 关）
    public record WaterSssSettings(
            float nonlinear,
            float boost,
            float phaseG,
            float strength) {
        public WaterSssSettings {
            if(!Float.isFinite(nonlinear) || nonlinear < 0 || nonlinear > 1 ||
               !Float.isFinite(boost) || boost < 0 || boost > 4 ||
               !Float.isFinite(phaseG) || phaseG < 0 || phaseG > 0.95f ||
               !Float.isFinite(strength) || strength < 0 || strength > 2)
                throw new IllegalArgumentException("Invalid water SSS settings");
        }

        public float[] toParamArray() {
            return new float[] { nonlinear, boost, phaseG, strength };
        }
    }
    private static WaterSssSettings waterSssSettings = new WaterSssSettings(
            0.5f, 2.0f, 0.8f, 1.0f);
    public static synchronized WaterSssSettings getWaterSssSettings() { return waterSssSettings; }
    public static synchronized void setWaterSssSettings(WaterSssSettings settings) {
        waterSssSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    private static WaterSimWaveSettings waterSimWaveSettings = new WaterSimWaveSettings(
            0.0f, 0.0f, 1.0f, 1.0f);
    public static synchronized WaterSimWaveSettings getWaterSimWaveSettings() { return waterSimWaveSettings; }
    public static synchronized void setWaterSimWaveSettings(WaterSimWaveSettings settings) {
        waterSimWaveSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-03 FFT 频谱海面（Tessendorf/Phillips 谱）：**全距离**水面形态。
    //   enabled     总开关（关 = 着色端直接跳过取样，binding 59 不被访问）
 // amplitude 波高倍率。0 = 静水
    //   choppiness  波峰锐化（0 = 纯正弦，1 = 出厂）
 // tilePeriod 世界平铺周期（格，2..128，出厂 10 = fract(worldXZ·0.1)）。
    //               越大重复越少、可解析的最细波长越粗（宿主按 texelWorldSize=period/N 折算）
    //   detail      2026-10-19 波面细分细节层倍率（0..3，1 = 出厂，0 = 关）。
    //               宿主把细节斜率乘进斜率图 .ba；只在烘焙时生效 ⇒ 不重编 RT 着色器。
    //
    // 2026-10-04 清理：原第 7 个分量 updateHz（频谱更新频率）整条删除 —— 死设置。
    // 2026-10-13 清理：tilePeriod 曾删除（当时只有后备 FFT 分支读 waterFftParams.x）。
 // 2026-10-17 清理：波面法线已整体换成 
    //   Tessendorf FFT 海洋，风固定 normalize(1,1)、谱峰只随雨强，故以下三项已无
    //   消费者而删除：windSpeed「浪速」/ windDirectionDeg「海浪风向」/ swellGain
    //   「涌浪强度」（旧 JSON 键读出后静默丢弃）。tilePeriod 恢复为可调滑杆。
    public record WaterFftSettings(
            boolean enabled,
            float amplitude,
            float choppiness,
            float tilePeriod,
            float detail) {
        public WaterFftSettings {
            if(!Float.isFinite(amplitude) || amplitude < 0 || amplitude > 3 ||
               !Float.isFinite(choppiness) || choppiness < 0 || choppiness > 3 ||
               !Float.isFinite(tilePeriod) || tilePeriod < 2 || tilePeriod > 128 ||
               !Float.isFinite(detail) || detail < 0 || detail > 3)
                throw new IllegalArgumentException("Invalid water FFT settings");
        }

        public float[] toParamArray() {
            return new float[] {
                enabled ? 1.0f : 0.0f, amplitude, choppiness, tilePeriod, detail };
        }
    }
    private static WaterFftSettings waterFftSettings = new WaterFftSettings(
            true, 1.0f, 1.0f, 10.0f, 1.0f);
    public static synchronized WaterFftSettings getWaterFftSettings() { return waterFftSettings; }
    public static synchronized void setWaterFftSettings(WaterFftSettings settings) {
        waterFftSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-03 后处理（新「后处理」设置页）：色彩映射 + 曝光/对比/饱和/gamma。
    //   tonemapMode       0=Uchimura/GT 1=AgX 2=Khronos PBR Neutral 3=ACES 4=Uncharted2
    //                     5=Reinhard 6=ACES 2.0（2026-10-23，官方 CTL 逐函数移植）
    //   exposureStrength  自动曝光参与度（出厂 0.6；0 = 完全关闭自动曝光）
    //   contrast          Uchimura 的 a（出厂 1.35，社区 GT 口径）
    //   saturation        饱和度（出厂 1.08）
    //   gamma             显示 gamma（出厂 2.2）
    //   rrFarCloudDistance 远云 RR guide 补写距离（格 = 米；0 = 关闭，出厂 4000）
    public record PostProcessingSettings(
            int tonemapMode,
            float exposureStrength,
            float contrast,
            float saturation,
            float gamma,
            int mirrorBounceCount,
            int tlasCullDistance,
            int tlasFrustumCullMargin,
            int rrFarCloudDistance) {
        public PostProcessingSettings {
            if(tonemapMode < 0 || tonemapMode > 6 ||
               !Float.isFinite(exposureStrength) || exposureStrength < 0 || exposureStrength > 1 ||
               !Float.isFinite(contrast) || contrast < 0.5f || contrast > 2 ||
               !Float.isFinite(saturation) || saturation < 0 || saturation > 2 ||
               !Float.isFinite(gamma) || gamma < 1.6f || gamma > 2.8f ||
               mirrorBounceCount < 1 || mirrorBounceCount > 8 ||
               tlasCullDistance < 0 || tlasCullDistance > 512 ||
               tlasFrustumCullMargin < 0 || tlasFrustumCullMargin > 256 ||
               rrFarCloudDistance < 0 || rrFarCloudDistance > 20000)
                throw new IllegalArgumentException("Invalid post processing settings");
        }

        /** setPostProcessingParams 的 8 个 lane（远云距离走独立的 setRrGuideSettings）。 */
        public float[] toParamArray() {
            return new float[] {
                tonemapMode, exposureStrength, contrast, saturation, gamma,
                mirrorBounceCount, tlasCullDistance, tlasFrustumCullMargin };
        }
    }
    private static PostProcessingSettings postProcessingSettings = new PostProcessingSettings(
            0, 0.6f, 1.35f, 1.08f, 2.2f, 1, 0, 0, 4000);
    public static synchronized PostProcessingSettings getPostProcessingSettings() { return postProcessingSettings; }
    public static synchronized void setPostProcessingSettings(PostProcessingSettings settings) {
        postProcessingSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-02 波形本体（CameraUBO.waterWaveShapeParams，偏移 1248）。
    // 2026-10-03 **接靶变更**：旧的解析自然波面已删除（water_wave_field.glsl 只保留
    // 波场→光学斜率的转换），这四个量不再经过 UBO 尾块 waterWaveShapeParams，而是由
    // 宿主写进水模拟的 push constant（WaterSimPushConstants.waveShape*）驱动**风源**：
    //   s0 波纹尺度（>1 更细更密、<1 更长更缓；源半径按 1/s 缩放）
    //   s1 波纹方向（度，0..360；把风源图案整体旋转）
    //   s2 波纹幅度（倍率，风源注入强度）
    //   s3 波峰不规则度（倍率；(s3-1) 偏移作用在源半径抖动上 ⇒ 1.0 时严格恒等）
    // 默认 1 / 0 / 1 / 1 = 出厂风源形态（不动旋钮看不出变化）。
    // UBO 尾块 waterWaveShapeParams 仍按 std140 顺序保留（消费者链不动），只是不再被读。
    public record WaterWaveShapeSettings(
            float lengthScale,
            float directionDegrees,
            float crestSharpness,
            float warpStrength) {
        public WaterWaveShapeSettings {
            if(!Float.isFinite(lengthScale) || lengthScale < 0.35f || lengthScale > 4.0f ||
               !Float.isFinite(directionDegrees) || directionDegrees < -3600.0f ||
               directionDegrees > 3600.0f ||
               !Float.isFinite(crestSharpness) || crestSharpness < 0 || crestSharpness > 2 ||
               !Float.isFinite(warpStrength) || warpStrength < 0 || warpStrength > 2)
                throw new IllegalArgumentException("Invalid water wave shape settings");
        }

        public float[] toParamArray() {
            return new float[] { lengthScale, directionDegrees, crestSharpness, warpStrength };
        }
    }
    private static WaterWaveShapeSettings waterWaveShapeSettings = new WaterWaveShapeSettings(
            1.0f, 0.0f, 1.0f, 1.0f);
    public static synchronized WaterWaveShapeSettings getWaterWaveShapeSettings() { return waterWaveShapeSettings; }
    public static synchronized void setWaterWaveShapeSettings(WaterWaveShapeSettings settings) {
        waterWaveShapeSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    // 2026-10-15 **ROUTE C**（陡剖面波列 + 逐层黄金角方位旋转，唯一自然波后端）
    // 波面后端（复用退役的 CameraUBO.waterWaveShapeParams，偏移 1248，closest-hit / rgen 读）。
    // 实现见 cpp/shaders/water_wave_field.glsl 的 waterDirRotWaveSlopeCapped；
    // 同日"退役旧后端"重构后，WATER_WAVES_{DIRROT,GUNNELL,FBM,AFL,NOISE,SEASCAPE}
    // 的 #if 天梯已整体删除，只剩 ROUTE C 一条 ⇒ 本组旋钮直接驱动它。
    // 四个字段名沿用 ROUTE A 时代（**改动的是语义不是名字**：改名会让"只换 jar 不换
    // DLL"或反之在 JNI 解析符号时失败、整个模组启动即崩）。新语义：
    //   wavelength → 最长波长 λmax（格，2..8；波长在 [λmax/band, λmax] 上对数均匀随机）
    //   warp       → 域扭曲 drag（0..1.5；把每根波的位置沿已有波推歪 = 消重复的主杠杆；
    //                 2026-10-15 下限 0.6→0，0 = 关掉域扭曲回到规则波列）
    //   octaves    → 波数 count（1..12；彼此独立的波列根数 = 成本旋钮，每根 5 次 SFU）
    //   waves      → 波长带宽 band（5..10；= λmax/λmin，越大频段越宽 = 越碎）
    // 其余波形旋钮复用「海浪」一组：海浪强度（= 幅度，0 = 关）/ 浪速 / 海浪风向 /
    // 波峰锐化（→ 整数 SteepSine 指数 S = 1..4）。
    //
    // 2026-10-20 死设置移除：2026-10-17 把可见水面定档 FFT 涌浪、ROUTE C
    //（waterDirRotWaveSlopeCapped）整块 `#if 0` 屏蔽后，本组只剩「最长波长」「波数」
    // 两项还有编译端读它 —— 走的是**无描述符消费者**（volumetric_light.comp 的水下
 // 波闪）的解析细波层。另两项（域扭曲 drag / 波长带宽 band）只存在于
    // 那段 `#if 0` 里 ⇒ GUI 拖了没有任何后果，按"死入口"删除（同 2026-10-17 删
    // 「浪速/海浪风向/涌浪强度」）。
    // ⚠ 删的是**入口与持久化**，不是 wire 契约：toParamArray() 仍发 4 个量，index 1/3
    // 填 ROUTE C 的出厂常数（0.90 / 8.0）。这样宿主 setter 与 waterWaveShapeParams
    // 的四个 lane 一字不用改，将来放开那段 `#if 0` 复活 ROUTE C 时也不会读到 0。
    public record WaterWaveAflSettings(
            float wavelength,
            float octaves) {
        public WaterWaveAflSettings {
            if(!Float.isFinite(wavelength) || wavelength < 2 || wavelength > 8 ||
               !Float.isFinite(octaves) || octaves < 1 || octaves > 12)
                throw new IllegalArgumentException("Invalid water wave dirrot settings");
        }

        public float[] toParamArray() {
            return new float[] { wavelength, kRETIRED_DRAG, octaves, kRETIRED_BAND };
        }

        public static final float kRETIRED_DRAG = 0.90f;
        public static final float kRETIRED_BAND = 8.0f;
    }
    private static WaterWaveAflSettings waterWaveAflSettings = new WaterWaveAflSettings(
            3.5f, 12.0f);
    public static synchronized WaterWaveAflSettings getWaterWaveAflSettings() { return waterWaveAflSettings; }
    public static synchronized void setWaterWaveAflSettings(WaterWaveAflSettings settings) {
        waterWaveAflSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    public static synchronized WaterParamsSettings getWaterParamsSettings() { return waterParamsSettings; }
    public static synchronized void setWaterParamsSettings(WaterParamsSettings settings) {
        waterParamsSettings = java.util.Objects.requireNonNull(settings);
        markDirty();
    }

    /**
     * 体积雾（2026-10-19 第二次重做，B 方案）。
     *
     * 完整对照与平台适配见 cpp/shaders/volumetric_fog.glsl 头注释。字段与参考
     * Lib/Settings.glsl:522-551 的 VFOG_* 一一对应：
     * <pre>
     *   density         -> VFOG_DENSITY_BASE
     *   height          -> VFOG_HEIGHT
     *   scaleHeight     -> VFOG_HEIGHT_2
     *   anisotropy      -> HG 的 g（参考 0.65）
     *   sunStrength     -> VFOG_SUNLIGHT_DENSITY
     *   ambientStrength -> VFOG_FOG_DENSITY
     *   sunSteps        -> VFOG_SUNLIGHT_STEPS
     *   rainBoost       -> VFOG_RAIN_DENSITY_MUL
     * </pre>
     * VFOG_DENSITY(0.01) / VFOG_FALLOFF(40) / VFOG_QUALITY(6) /
     * VFOG_SUNLIGHT_ABSORPTION(1.0) / VFOG_SUNLIGHT_STEPLENGTH(5.0) 用参考出厂
     * 常量，不开放滑杆。
     */
    public record VolumetricFogSettings(
            boolean enabled,
            float density,
            float height,
            float scaleHeight,
            float anisotropy,
            float sunStrength,
            float ambientStrength,
            float maxDistance,
            float startDistance,
            int sunSteps,
            float rainBoost
    ) {
        public VolumetricFogSettings {
            if(!Float.isFinite(density) || !Float.isFinite(height) ||
                    !Float.isFinite(scaleHeight) || !Float.isFinite(anisotropy) ||
                    !Float.isFinite(sunStrength) || !Float.isFinite(ambientStrength) ||
                    !Float.isFinite(maxDistance) || !Float.isFinite(startDistance) ||
                    !Float.isFinite(rainBoost) ||
                    density < 0.0f || density > 100.0f ||
                    height < -64.0f || height > 320.0f ||
                    scaleHeight < -64.0f || scaleHeight > 320.0f ||
                    anisotropy < 0.0f || anisotropy > 0.95f ||
                    sunStrength < 0.0f || sunStrength > 4.0f ||
                    ambientStrength < 0.0f || ambientStrength > 4.0f ||
                    maxDistance < 16.0f || maxDistance > 4096.0f ||
                    startDistance < 0.0f || startDistance > 256.0f ||
                    sunSteps < 1 || sunSteps > 8 ||
                    rainBoost < 0.0f || rainBoost > 32.0f) {
                throw new IllegalArgumentException("Invalid volumetric fog settings");
            }
        }
    }

    /**
     * 体积光（god rays）。范围与 C++ 侧校验一致。
     *
     * 2026-10-19 起空气体积光退役，本组设置主要控制水下分支；VL_AIR_GODRAYS = 1
     * 时旧空气 A/B 路径也读这些成本/强度旋钮。{@code steps} 与
     * {@code occlusionSamples} 是纯成本旋钮；{@code intensity} 乘在昼夜 strength
     * 上（1.0 = 标定档，0 = 关光柱）。2026-10-06 的 {@code selfShadow} 已删除。
     */
    public record VolumetricLightSettings(
            boolean enabled,
            int steps,
            int occlusionSamples,
            float intensity,
            

            float reprojection
    ) {
        public VolumetricLightSettings {
            if(steps < 4 || steps > 48 ||
                    occlusionSamples < 1 || occlusionSamples > 6 ||
                    !Float.isFinite(intensity) ||
                    intensity < 0.0f || intensity > 4.0f ||
                    !Float.isFinite(reprojection) ||
                    reprojection < 0.0f || reprojection > 0.95f) {
                throw new IllegalArgumentException("Invalid volumetric light settings");
            }
        }
    }

    public static final CloudSettings DEFAULT_CLOUD_SETTINGS =
            new CloudSettings(true, 1200.0f, 1800.0f, 0.55f, 2.8f,
                    0.25f, 11.5f, 2.0f, 1, 0.55f, 1.0f, 0.33f,
                    1.79f);

    private static CloudSettings cloudSettings =
            DEFAULT_CLOUD_SETTINGS;



    private static boolean hdrEnabled =
            true;


    private static boolean autoExposureEnabled =
            false;

    private static float autoExposureCompensation =
            0.0f;

    private static boolean fgFrameSync =
            true;

    private static boolean fgRefreshCap =
            true;

    private static boolean slDevPlugins =
            false;

    private static boolean serEnabled =
            true;

    public static final int FG_MULTIPLIER_OFF = 0;

    private static int fgMultiplier =
            3;

    public static final int FG_BASE_LIMIT_AUTO = -1;
    public static final int FG_BASE_LIMIT_OFF = 0;

    private static int fgBaseFrameLimit =
            FG_BASE_LIMIT_AUTO;

    private static boolean entityArmorMetal =
            false;

    private static boolean cloudFadeFollowsRrMode =
            true;

    private static boolean cloudPostRrComposite =
            true;

    private static int cloudResFloorPercent =
            100;

    private static float cloudFarStepDensity =
            0.45f;


    private static int giRoulette =
            1;

    private static int directLightMode =
            0;

    

    private static int indirectMode =
            0;

    private static int sparseRatePercent =
            0;

    private static int motionDebugView =
            0;

    private static int motionVectorsEnabled =
            1;

    private static int sunMarchFarDistance =
            32;

    public static final PerformanceSettings DEFAULT_PERFORMANCE_SETTINGS =
            new PerformanceSettings(1, 1, 1, 2, 2, 2, 1, 64, 2, 256, 1.0f, 0);

    private static PerformanceSettings performanceSettings =
            DEFAULT_PERFORMANCE_SETTINGS;

    public static final RainSettings DEFAULT_RAIN_SETTINGS =
            new RainSettings(1.0f, 0.4f, 0.05f, 1.0f, 1.0f);

    private static RainSettings rainSettings =
            DEFAULT_RAIN_SETTINGS;

    public static final SnowSettings DEFAULT_SNOW_SETTINGS =
            new SnowSettings(1.0f, 1.0f, 0.85f, 1.0f, 1.0f, 0.30f);

    private static SnowSettings snowSettings =
            DEFAULT_SNOW_SETTINGS;

    public static final RainWallSettings DEFAULT_RAIN_WALL_SETTINGS =
            new RainWallSettings(true, 2.0f, 1.0f, 2.25f, 1.0f, 1.1f, 24.0f);

    private static RainWallSettings rainWallSettings =
            DEFAULT_RAIN_WALL_SETTINGS;

    public static final GalaxySettings DEFAULT_GALAXY_SETTINGS =
            new GalaxySettings(0.85f, 30.0f, -45.0f, 0.5f);

    private static GalaxySettings galaxySettings =
            DEFAULT_GALAXY_SETTINGS;

    public static final WaterStyleSettings DEFAULT_WATER_STYLE_SETTINGS =
            new WaterStyleSettings(1.15f, 1.0f, 1.0f, 4.0f, 1.0f, 1.0f, 1.0f, 1.0f);

    private static WaterStyleSettings waterStyleSettings =
            DEFAULT_WATER_STYLE_SETTINGS;


    static {
        load();
    }


    private RTXSettingsConfig() {
    }


    public static synchronized int getReflexMode() {
        return reflexMode;
    }


    public static synchronized void setReflexMode(
            int mode
    ) {
        if(mode < 0 || mode > 2) {
            throw new IllegalArgumentException(
                    "Invalid Reflex mode: " + mode
            );
        }


        if (reflexMode != mode) {
            reflexMode = mode;
            markDirty();
        }
    }


    public static synchronized boolean isFGFrameSyncEnabled() {
        return fgFrameSync;
    }

    public static synchronized boolean isSerEnabled() {
        return serEnabled;
    }

    public static synchronized void setSerEnabled(
            boolean enabled
    ) {
        if (serEnabled != enabled) {
            serEnabled = enabled;
            markDirty();
        }
    }

    public static synchronized void setFGFrameSyncEnabled(
            boolean enabled
    ) {
        if (fgFrameSync != enabled) {
            fgFrameSync = enabled;
            markDirty();
        }
    }

    public static synchronized boolean isSLDevPluginsEnabled() {
        return slDevPlugins;
    }

    public static synchronized void setSLDevPluginsEnabled(
            boolean enabled
    ) {
        if (slDevPlugins != enabled) {
            slDevPlugins = enabled;
            markDirty();
        }
    }

    public static synchronized boolean isFGRefreshCapEnabled() {
        return fgRefreshCap;
    }

    public static synchronized void setFGRefreshCapEnabled(
            boolean enabled
    ) {
        if (fgRefreshCap != enabled) {
            fgRefreshCap = enabled;
            markDirty();
        }
    }

    public static boolean isValidFGMultiplier(
            int multiplier
    ) {
        return multiplier == FG_MULTIPLIER_OFF
                || multiplier == 2
                || multiplier == 3
                || multiplier == 4
                || multiplier == 6;
    }

    public static synchronized int getFGMultiplier() {
        return fgMultiplier;
    }

    public static boolean isValidFGBaseFrameLimit(
            int fps
    ) {
        return fps == FG_BASE_LIMIT_AUTO
                || fps == FG_BASE_LIMIT_OFF
                || (fps >= 15 && fps <= 480);
    }

    public static synchronized int getFGBaseFrameLimit() {
        return fgBaseFrameLimit;
    }

    public static synchronized void setFGBaseFrameLimit(
            int fps
    ) {
        if(!isValidFGBaseFrameLimit(fps)) {
            throw new IllegalArgumentException(
                    "Invalid FG base frame limit: " + fps
            );
        }


        if (fgBaseFrameLimit != fps) {
            fgBaseFrameLimit = fps;
            markDirty();
        }
    }

    public static synchronized boolean isEntityArmorMetalEnabled() {
        return entityArmorMetal;
    }

    public static synchronized void setEntityArmorMetalEnabled(
            boolean enabled
    ) {
        if (entityArmorMetal != enabled) {
            entityArmorMetal = enabled;
            markDirty();
        }
    }

    public static synchronized boolean isCloudFadeFollowsRrMode() {
        return cloudFadeFollowsRrMode;
    }

    public static synchronized void setCloudFadeFollowsRrMode(
            boolean enabled
    ) {
        if (cloudFadeFollowsRrMode != enabled) {
            cloudFadeFollowsRrMode = enabled;
            markDirty();
        }
    }

    public static synchronized boolean isCloudPostRrComposite() {
        return cloudPostRrComposite;
    }

    public static synchronized void setCloudPostRrComposite(
            boolean enabled
    ) {
        if (cloudPostRrComposite != enabled) {
            cloudPostRrComposite = enabled;
            markDirty();
        }
    }

    public static synchronized int getCloudResFloorPercent() {
        return cloudResFloorPercent;
    }

    public static synchronized void setCloudResFloorPercent(
            int percent
    ) {
        final int clamped = Math.max(0, Math.min(100, percent));
        if (cloudResFloorPercent != clamped) {
            cloudResFloorPercent = clamped;
            markDirty();
        }
    }

    /** 2026-10-07：GI 循环俄罗斯轮盘。1 = 开（出厂）/ 0 = 旧档（关）。 */
    public static synchronized int getGiRoulette() {
        return giRoulette;
    }

    public static synchronized void setGiRoulette(
            int enabled
    ) {
        final int clamped = enabled == 0 ? 0 : 1;
        if (giRoulette != clamped) {
            giRoulette = clamped;
            markDirty();
        }
    }

    /** 2026-10-26：直接光模式。0 = 传统 RIS（出厂）/ 1 = RTXDI 式时域 reservoir。 */
    public static synchronized int getDirectLightMode() {
        return directLightMode;
    }

    public static synchronized void setDirectLightMode(
            int mode
    ) {
        final int clamped = mode == 0 ? 0 : 1;
        if (directLightMode != clamped) {
            directLightMode = clamped;
            markDirty();
        }
    }

    /** 2026-10-28：间接光模式。0 = GI（出厂）/ 1 = SHaRC（需重启生效）。 */
    public static synchronized int getIndirectMode() {
        return indirectMode;
    }

    public static synchronized void setIndirectMode(
            int mode
    ) {
        final int clamped = mode == 0 ? 0 : 1;
        if (indirectMode != clamped) {
            indirectMode = clamped;
            markDirty();
        }
    }

    /** 2026-10-26：稀疏渲染采样率 %（0 = 关，出厂；100 = 全采样）。 */
    public static synchronized int getSparseRatePercent() {
        return sparseRatePercent;
    }

    public static synchronized void setSparseRatePercent(
            int percent
    ) {
        final int clamped = Math.max(0, Math.min(100, percent));
        if (sparseRatePercent != clamped) {
            sparseRatePercent = clamped;
            markDirty();
        }
    }

    /**
     * 2026-10-09 运动矢量调试视图。0 = 关（出厂）/ 1 = 幅值热图 / 2 = 有向色。
     *
     * <p>改完调 {@code NativeVulkan.setMotionDebugView} 推送（设置界面 Apply 或启动恢复），
     * native 只存原子量、每帧写 UBO 保留 lane ⇒ 下一帧生效。</p>
     */
    public static synchronized int getMotionDebugView() {
        return motionDebugView;
    }

    public static synchronized void setMotionDebugView(
            int mode
    ) {
        final int clamped = (mode >= 0 && mode <= 2) ? mode : 0;
        if (motionDebugView != clamped) {
            motionDebugView = clamped;
            markDirty();
        }
    }

    /**
     * 2026-10-09 运动矢量档位；2026-10-22 扩成 4 档（同名键，向后兼容）：
     * <pre>
     *   0 = 关（rgen 每帧写 0，回到接 MV 之前的逐位行为）
     *   1 = 像素 → mvecScale { 1/w,  1/h }（官方文档口径，出厂）
     *   2 = 像素 → mvecScale { 1/w, -1/h }（Vulkan y 向下约定）
     *   3 = UV   → mvecScale { 1, 1 }（改动前的旧行为）
     * </pre>
     * 1/2/3 只在"MV 数据是像素位移"这一实测事实下才有意义（见 cpp 里
     * g_mvScaleMode 的说明）。
     */
    public static synchronized int getMotionVectorsEnabled() {
        return motionVectorsEnabled;
    }

    public static synchronized void setMotionVectorsEnabled(
            int enabled
    ) {
        final int clamped = Math.max(0, Math.min(3, enabled));
        if (motionVectorsEnabled != clamped) {
            motionVectorsEnabled = clamped;
            markDirty();
        }
    }

    /** 2026-10-07：太阳透射 march 的远距简化距离（格）。0 = 关 = 固定 6 步（旧档）。 */
    public static synchronized int getSunMarchFarDistance() {
        return sunMarchFarDistance;
    }

    public static synchronized void setSunMarchFarDistance(
            int blocks
    ) {
        final int clamped = Math.max(0, Math.min(256, blocks));
        if (sunMarchFarDistance != clamped) {
            sunMarchFarDistance = clamped;
            markDirty();
        }
    }

    public static synchronized float getCloudFarStepDensity() {
        return cloudFarStepDensity;
    }

    public static synchronized void setCloudFarStepDensity(
            float density
    ) {
        final float clamped = Float.isFinite(density)
                ? Math.max(0.0f, Math.min(1.0f, density))
                : 0.10f;
        if (Float.compare(cloudFarStepDensity, clamped) != 0) {
            cloudFarStepDensity = clamped;
            markDirty();
        }
    }

    public static synchronized void setFGMultiplier(
            int multiplier
    ) {
        if(!isValidFGMultiplier(multiplier)) {
            throw new IllegalArgumentException(
                    "Invalid FG multiplier: " + multiplier
            );
        }


        if (fgMultiplier != multiplier) {
            fgMultiplier = multiplier;
            markDirty();
        }
    }

    public static synchronized PerformanceSettings getPerformanceSettings() {
        return performanceSettings;
    }

    public static synchronized void setPerformanceSettings(
            PerformanceSettings settings
    ) {
        if (settings == null) {
            throw new IllegalArgumentException("Performance settings must not be null");
        }
        if (!performanceSettings.equals(settings)) {
            performanceSettings = settings;
            markDirty();
        }
    }

    public static synchronized RainSettings getRainSettings() {
        return rainSettings;
    }


    public static synchronized RainWallSettings getRainWallSettings() {
        return rainWallSettings;
    }

    public static synchronized WaterStyleSettings getWaterStyleSettings() {
        return waterStyleSettings;
    }

    public static synchronized void setWaterStyleSettings(
            WaterStyleSettings settings
    ) {
        if(settings == null) {
            throw new IllegalArgumentException("Water style settings must not be null");
        }
        if(!waterStyleSettings.equals(settings)) {
            waterStyleSettings = settings;
            markDirty();
        }
    }


    public static synchronized void setRainWallSettings(
            RainWallSettings settings
    ) {
        if(settings == null) {
            throw new IllegalArgumentException("Rain wall settings must not be null");
        }
        if(!rainWallSettings.equals(settings)) {
            rainWallSettings = settings;
            markDirty();
        }
    }

    public static synchronized void setRainSettings(
            RainSettings settings
    ) {
        if (settings == null) {
            throw new IllegalArgumentException("Rain settings must not be null");
        }
        if (!rainSettings.equals(settings)) {
            rainSettings = settings;
            markDirty();
        }
    }

    public static synchronized SnowSettings getSnowSettings() {
        return snowSettings;
    }

    public static synchronized GalaxySettings getGalaxySettings() {
        return galaxySettings;
    }

    public static synchronized void setGalaxySettings(
            GalaxySettings settings
    ) {
        if (settings == null) {
            throw new IllegalArgumentException("Galaxy settings must not be null");
        }
        if (!galaxySettings.equals(settings)) {
            galaxySettings = settings;
            markDirty();
        }
    }

    public static synchronized void setSnowSettings(
            SnowSettings settings
    ) {
        if (settings == null) {
            throw new IllegalArgumentException("Snow settings must not be null");
        }
        if (!snowSettings.equals(settings)) {
            snowSettings = settings;
            markDirty();
        }
    }

    public static synchronized int getRRMode() {
        return rrMode;
    }

    public static synchronized int getUpscalerMode() { return upscalerMode; }
    public static synchronized void setUpscalerMode(int mode) {
        if(mode < 0 || mode > 1) throw new IllegalArgumentException("Invalid upscaler mode: " + mode);
        if (upscalerMode != mode) {
            upscalerMode = mode;
            markDirty();
        }
    }
    public static synchronized int getSRMode() { return srMode; }
    public static synchronized void setSRMode(int mode) {
        if(mode < 0 || mode > 3) throw new IllegalArgumentException("Invalid SR mode: " + mode);
        if (srMode != mode) {
            srMode = mode;
            markDirty();
        }
    }



    public static synchronized void setRRMode(
            int mode
    ) {
        if(mode < RR_MODE_PERFORMANCE ||
                mode > RR_MODE_ULTRA_PERFORMANCE) {

            throw new IllegalArgumentException(
                    "Invalid RR mode: " + mode
            );
        }


        if (rrMode != mode) {
            rrMode = mode;
            markDirty();
        }
    }


    public static synchronized float getNoDirectLightAmbient() {
        return noDirectLightAmbient;
    }


    public static synchronized void setNoDirectLightAmbient(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < 0.0f ||
                value > 1.0f) {

            throw new IllegalArgumentException(
                    "Invalid no-direct-light ambient: "
                            + value
            );
        }


        float rounded = Math.round(value * 10000.0f) / 10000.0f;
        if (Float.compare(noDirectLightAmbient, rounded) != 0) {
            noDirectLightAmbient = rounded;
            markDirty();
        }
    }


    public static synchronized float getPOMDepth() {
        return pomDepth;
    }


    /** 2026-10-18 视差高度场采样：false = 平滑（双线性，默认）；true = 锐利（单纹素）。 */
    public static synchronized boolean isPOMSharp() {
        return pomSharp;
    }


    /** 2026-10-22 UE 体积云八项参数之一（索引见 {@link #UE_CLOUD_PARAM_COUNT} 上方注释）。 */
    public static synchronized float getUeCloud(
            int index
    ) {
        if (index < 0 || index >= UE_CLOUD_PARAM_COUNT) {
            return 0.0f;
        }
        return ueCloud[index];
    }


    /** 返回整组参数的副本（UI 初始化时用，别直接暴露内部数组）。 */
    public static synchronized float[] getUeCloudAll() {
        return ueCloud.clone();
    }


    /** 2026-10-19 树叶次表面散射：false = 太阳背光（默认）；true = 真次表面散射。 */
    public static synchronized boolean isTrueSubsurfaceScattering() {
        return trueSubsurfaceScattering;
    }

    public static synchronized float getLocalLightMaxDistance() {
        return localLightMaxDistance;
    }

    public static synchronized void setLocalLightMaxDistance(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < 4.0f ||
                value > 30.0f) {

            throw new IllegalArgumentException(
                    "Invalid local light max distance: "
                            + value
            );
        }


        float rounded = Math.round(value);
        if (Float.compare(localLightMaxDistance, rounded) != 0) {
            localLightMaxDistance = rounded;
            markDirty();
        }
    }

    public static synchronized float getLocalLightIntensity() {
        return localLightIntensity;
    }

    public static synchronized void setLocalLightIntensity(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < 0.0f ||
                value > 4.0f) {

            throw new IllegalArgumentException(
                    "Invalid local light intensity: "
                            + value
            );
        }


        float rounded =
                Math.round(value * 100.0f) / 100.0f;

        if (Float.compare(localLightIntensity, rounded) != 0) {
            localLightIntensity = rounded;
            markDirty();
        }
    }

    public static synchronized float getDynamicLightIntensity() {
        return dynamicLightIntensity;
    }

    public static synchronized void setDynamicLightIntensity(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < 0.0f ||
                value > 4.0f) {

            throw new IllegalArgumentException(
                    "Invalid dynamic light intensity: "
                            + value
            );
        }


        float rounded =
                Math.round(value * 100.0f) / 100.0f;

        if (Float.compare(dynamicLightIntensity, rounded) != 0) {
            dynamicLightIntensity = rounded;
            markDirty();
        }
    }

    public static synchronized float getPostBloomStrength() {
        return postBloomStrength;
    }

    public static synchronized void setPostBloomStrength(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < 0.0f ||
                value > 2.0f) {

            throw new IllegalArgumentException(
                    "Invalid bloom strength: "
                            + value
            );
        }


        float rounded =
                Math.round(value * 100.0f) / 100.0f;

        if (Float.compare(postBloomStrength, rounded) != 0) {
            postBloomStrength = rounded;
            markDirty();
        }
    }

    public static synchronized float getPostGainR() {
        return postGainR;
    }

    public static synchronized float getPostGainG() {
        return postGainG;
    }

    public static synchronized float getPostGainB() {
        return postGainB;
    }

    public static synchronized void setPostGains(
            float r, float g, float b
    ) {
        float cr = roundPostGain(r);
        float cg = roundPostGain(g);
        float cb = roundPostGain(b);

        if (Float.compare(postGainR, cr) != 0
                || Float.compare(postGainG, cg) != 0
                || Float.compare(postGainB, cb) != 0) {
            postGainR = cr;
            postGainG = cg;
            postGainB = cb;
            markDirty();
        }
    }

    private static float roundPostGain(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Invalid post gain: " + value);
        }
        return Math.round(
                Math.min(Math.max(value, 0.25f), 2.5f) * 100.0f
        ) / 100.0f;
    }

    public static synchronized CloudSettings getCloudSettings() {
        return cloudSettings;
    }

    public static synchronized VolumetricFogSettings getVolumetricFogSettings() {
        return volumetricFogSettings;
    }

    public static synchronized void setVolumetricFogSettings(
            VolumetricFogSettings settings
    ) {
        if(settings == null) {
            throw new IllegalArgumentException("Volumetric fog settings must not be null");
        }
        if(!volumetricFogSettings.equals(settings)) {
            volumetricFogSettings = settings;
            markDirty();
        }
    }

    public static synchronized VolumetricLightSettings getVolumetricLightSettings() {
        return volumetricLightSettings;
    }

    public static synchronized void setVolumetricLightSettings(
            VolumetricLightSettings settings
    ) {
        if(settings == null) {
            throw new IllegalArgumentException("Volumetric light settings must not be null");
        }
        if(!volumetricLightSettings.equals(settings)) {
            volumetricLightSettings = settings;
            markDirty();
        }
    }

    public static synchronized SunVisualSettings getSunVisualSettings() {
        return sunVisualSettings;
    }

    public static synchronized void setSunVisualSettings(SunVisualSettings settings) {
        if(settings == null) {
            throw new IllegalArgumentException("Sun visual settings must not be null");
        }
        if(!sunVisualSettings.equals(settings)) {
            sunVisualSettings = settings;
            markDirty();
        }
    }

    public static synchronized AtmosphereSettings getAtmosphereSettings() {
        return atmosphereSettings;
    }

    public static synchronized void setAtmosphereSettings(AtmosphereSettings settings) {
        if(settings == null) {
            throw new IllegalArgumentException("Atmosphere settings must not be null");
        }
        if(!atmosphereSettings.equals(settings)) {
            atmosphereSettings = settings;
            markDirty();
        }
    }

    public static synchronized float getPowderStrength() {
        return powderStrength;
    }

    public static synchronized void setPowderStrength(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 3.0f) {
            throw new IllegalArgumentException("Invalid powder strength: " + value);
        }
        float rounded = Math.round(value * 20.0f) / 20.0f;
        if(Float.compare(powderStrength, rounded) != 0) {
            powderStrength = rounded;
            markDirty();
        }
    }

    public static synchronized float getAuroraBrightness() {
        return auroraBrightness;
    }

    public static synchronized void setAuroraBrightness(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 3.0f) {
            throw new IllegalArgumentException("Invalid aurora brightness: " + value);
        }
        float rounded = Math.round(value * 20.0f) / 20.0f;
        if(Float.compare(auroraBrightness, rounded) != 0) {
            auroraBrightness = rounded;
            markDirty();
        }
    }

    public static synchronized float getAuroraFlowSpeed() {
        return auroraFlowSpeed;
    }

    public static synchronized void setAuroraFlowSpeed(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 4.0f) {
            throw new IllegalArgumentException("Invalid aurora flow speed: " + value);
        }
        float rounded = Math.round(value * 20.0f) / 20.0f;
        if(Float.compare(auroraFlowSpeed, rounded) != 0) {
            auroraFlowSpeed = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudSunForwardScattering() {
        return cloudSunForwardScattering;
    }

    public static synchronized void setCloudSunForwardScattering(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(
                    "Invalid cloud sun forward scattering: " + value);
        }
        float rounded = Math.round(value * 20.0f) / 20.0f;
        if(Float.compare(cloudSunForwardScattering, rounded) != 0) {
            cloudSunForwardScattering = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudSunReddening() {
        return cloudSunReddening;
    }

    public static synchronized void setCloudSunReddening(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 160.0f) {
            throw new IllegalArgumentException("Invalid cloud sun reddening: " + value);
        }
        float rounded = Math.round(value);
        if(Float.compare(cloudSunReddening, rounded) != 0) {
            cloudSunReddening = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudDetileStrength() {
        return cloudDetileStrength;
    }

    public static synchronized void setCloudDetileStrength(float value) {
        if(!Float.isFinite(value) || value < 0.1f || value > 12.0f) {
            throw new IllegalArgumentException("Invalid cloud detile strength: " + value);
        }
        float rounded = Math.round(value * 20.0f) / 20.0f;
        if(Float.compare(cloudDetileStrength, rounded) != 0) {
            cloudDetileStrength = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudType() {
        return cloudType;
    }

    public static synchronized void setCloudType(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("Invalid cloud type: " + value);
        }
        float rounded = Math.round(value * 100.0f) / 100.0f;
        if(Float.compare(cloudType, rounded) != 0) {
            cloudType = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudErosionScale() {
        return cloudErosionScale;
    }

    public static synchronized float getCloudWeatherScale() {
        return cloudWeatherScale;
    }

    public static synchronized float getCloudSilver() {
        return cloudSilver;
    }

    public static synchronized void setCloudSilver(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("Invalid cloud silver: " + value);
        }
        float rounded = Math.round(value * 100.0f) / 100.0f;
        if(Float.compare(cloudSilver, rounded) != 0) {
            cloudSilver = rounded;
            markDirty();
        }
    }

    public static synchronized void setCloudWeatherScale(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 0.99f) {
            throw new IllegalArgumentException("Invalid cloud fade start: " + value);
        }
        float rounded = Math.round(value * 100.0f) / 100.0f;
        if(Float.compare(cloudWeatherScale, rounded) != 0) {
            cloudWeatherScale = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudViewDistance() {
        return cloudViewDistance;
    }

    public static synchronized void setCloudViewDistance(float value) {
        if(!Float.isFinite(value) || value < 2000.0f || value > 200000.0f) {
            throw new IllegalArgumentException("Invalid cloud view distance: " + value);
        }
        float rounded = Math.round(value / 1000.0f) * 1000.0f;
        if(Float.compare(cloudViewDistance, rounded) != 0) {
            cloudViewDistance = rounded;
            markDirty();
        }
    }

    public static synchronized void setCloudErosionScale(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 2.0f) {
            throw new IllegalArgumentException("Invalid cloud erosion scale: " + value);
        }
        float rounded = Math.round(value * 20.0f) / 20.0f;
        if(Float.compare(cloudErosionScale, rounded) != 0) {
            cloudErosionScale = rounded;
            markDirty();
        }
    }

    public static synchronized float getCloudTopStretch() {
        return cloudTopStretch;
    }

    public static synchronized void setCloudTopStretch(float value) {
        if(!Float.isFinite(value) || value < 0.0f || value > 32.0f) {
            throw new IllegalArgumentException("Invalid cloud top stretch: " + value);
        }
        float rounded = Math.round(value);
        if(Float.compare(cloudTopStretch, rounded) != 0) {
            cloudTopStretch = rounded;
            markDirty();
        }
    }

    public static synchronized void setCloudSettings(CloudSettings settings) {
        if(settings == null) {
            throw new IllegalArgumentException("Cloud settings must not be null");
        }
        if(!cloudSettings.equals(settings)) {
            cloudSettings = settings;
            markDirty();
        }
    }


    public static synchronized void setPOMDepth(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < 0.0f ||
                value > 0.20f) {

            throw new IllegalArgumentException(
                    "Invalid POM depth: "
                            + value
            );
        }


        float rounded = Math.round(value * 100.0f) / 100.0f;
        if (Float.compare(pomDepth, rounded) != 0) {
            pomDepth = rounded;
            markDirty();
        }
    }


    public static synchronized void setPOMSharp(
            boolean value
    ) {
        if (pomSharp != value) {
            pomSharp = value;
            markDirty();
        }
    }


    /** 2026-10-22 UE 体积云八项参数之一。范围由调用方（UI 滑条）负责钳制。 */
    public static synchronized void setUeCloud(
            int index,
            float value
    ) {
        if (index < 0 || index >= UE_CLOUD_PARAM_COUNT) {
            return;
        }
        if (ueCloud[index] != value) {
            ueCloud[index] = value;
            markDirty();
        }
    }


    public static synchronized void setTrueSubsurfaceScattering(
            boolean value
    ) {
        if (trueSubsurfaceScattering != value) {
            trueSubsurfaceScattering = value;
            markDirty();
        }
    }


    public static synchronized boolean isHDREnabled() {
        return hdrEnabled;
    }


    public static synchronized void setHDREnabled(
            boolean enabled
    ) {
        if (hdrEnabled != enabled) {
            hdrEnabled = enabled;
            markDirty();
        }
    }


    public static synchronized boolean isAutoExposureEnabled() {
        return autoExposureEnabled;
    }


    public static synchronized void setAutoExposureEnabled(
            boolean enabled
    ) {
        if (autoExposureEnabled != enabled) {
            autoExposureEnabled = enabled;
            markDirty();
        }
    }


    public static synchronized float getAutoExposureCompensation() {
        return autoExposureCompensation;
    }


    public static synchronized void setAutoExposureCompensation(
            float value
    ) {
        if(!Float.isFinite(value) ||
                value < -2.0f ||
                value > 2.0f) {

            throw new IllegalArgumentException(
                    "Invalid auto exposure compensation: "
                            + value
            );
        }


        float rounded = Math.round(value * 10.0f) / 10.0f;
        if (Float.compare(autoExposureCompensation, rounded) != 0) {
            autoExposureCompensation = rounded;
            markDirty();
        }
    }


    private static final String SHIPPED_DEFAULTS_RESOURCE =
            "/rtx-settings.default.json";

    private static void ensureConfigFromShippedDefaults() {

        try {
            if(Files.exists(CONFIG_PATH)) {
                return;
            }

            JsonObject shipped;

            try (
                    InputStream in =
                            RTXSettingsConfig.class.getResourceAsStream(
                                    SHIPPED_DEFAULTS_RESOURCE
                            )
            ) {
                if(in == null) {
                    return;
                }

                JsonElement parsed =
                        JsonParser.parseReader(
                                new InputStreamReader(
                                        in,
                                        StandardCharsets.UTF_8
                                )
                        );

                if(parsed == null || !parsed.isJsonObject()) {
                    return;
                }

                shipped = parsed.getAsJsonObject();
            }

            if(shipped.size() == 0) {
                return;
            }

            Files.createDirectories(CONFIG_PATH.getParent());

            try (
                    Writer writer =
                            Files.newBufferedWriter(
                                    CONFIG_PATH,
                                    StandardCharsets.UTF_8
                            )
            ) {
                GSON.toJson(shipped, writer);
            }

        } catch (Throwable ignored) {
        }
    }


    private static synchronized void load() {

        ensureConfigFromShippedDefaults();

        if(!Files.exists(CONFIG_PATH)) {
            reflexMode =
                    2;

            rrMode =
                    RR_MODE_PERFORMANCE;
            upscalerMode = UPSCALER_MODE_RR;
            srMode = RR_MODE_PERFORMANCE;

            noDirectLightAmbient =
                    0.0001f;

            pomDepth =
                    0.03f;

            pomSharp =
                    false;

            trueSubsurfaceScattering =
                    false;

            localLightMaxDistance =
                    15.0f;

            localLightIntensity =
                    1.0f;

            dynamicLightIntensity =
                    1.0f;

            cloudSettings = DEFAULT_CLOUD_SETTINGS;

            hdrEnabled =
                    false;

            autoExposureEnabled =
                    false;

            autoExposureCompensation =
                    0.0f;

            serEnabled =
                    true;

            performanceSettings =
                    DEFAULT_PERFORMANCE_SETTINGS;

            rainSettings =
                    DEFAULT_RAIN_SETTINGS;

            rainWallSettings =
                    DEFAULT_RAIN_WALL_SETTINGS;

            snowSettings =
                    DEFAULT_SNOW_SETTINGS;

            galaxySettings =
                    DEFAULT_GALAXY_SETTINGS;

            return;
        }


        try (
                Reader reader =
                        Files.newBufferedReader(
                                CONFIG_PATH,
                                StandardCharsets.UTF_8
                        )
        ) {
            Data data =
                    GSON.fromJson(
                            reader,
                            Data.class
                    );


            if(data == null) {
                reflexMode =
                        2;

                rrMode =
                        RR_MODE_PERFORMANCE;
                upscalerMode = UPSCALER_MODE_RR;
                srMode = RR_MODE_PERFORMANCE;

                noDirectLightAmbient =
                        0.0001f;

                pomDepth =
                        0.03f;

                pomSharp =
                        false;

                trueSubsurfaceScattering =
                        false;

                localLightMaxDistance =
                        15.0f;

                localLightIntensity =
                        1.0f;

                dynamicLightIntensity =
                        1.0f;

                sunVisualSettings = DEFAULT_SUN_VISUAL_SETTINGS;

                cloudSettings = DEFAULT_CLOUD_SETTINGS;

                hdrEnabled =
                        false;

                autoExposureEnabled =
                        false;

                autoExposureCompensation =
                        0.0f;

                fgFrameSync =
                        true;

                serEnabled =
                        true;

                fgMultiplier =
                        3;

                fgBaseFrameLimit =
                        165;

                entityArmorMetal =
                        false;

                cloudFadeFollowsRrMode =
                        true;

                cloudPostRrComposite =
                        true;

                cloudResFloorPercent =
                        0;

                cloudFarStepDensity =
                        0.45f;
                giRoulette =
                        1;

                directLightMode =
                        0;

                sparseRatePercent =
                        0;

                sunMarchFarDistance =
                        32;

                motionDebugView =
                        0;

                motionVectorsEnabled =
                        1;

                performanceSettings =
                        DEFAULT_PERFORMANCE_SETTINGS;

                rainSettings =
                        DEFAULT_RAIN_SETTINGS;

                rainWallSettings =
                        DEFAULT_RAIN_WALL_SETTINGS;

                snowSettings =
                        DEFAULT_SNOW_SETTINGS;

                galaxySettings =
                        DEFAULT_GALAXY_SETTINGS;

                atmosphereSettings =
                        DEFAULT_ATMOSPHERE_SETTINGS;

                powderStrength =
                        3.0f;

                cloudSunForwardScattering =
                        1.0f;

                volumetricFogSettings =
                        DEFAULT_VOLUMETRIC_FOG_SETTINGS;

                volumetricLightSettings =
                        DEFAULT_VOLUMETRIC_LIGHT_SETTINGS;

                return;
            }


            if(data.reflexMode >= 0 &&
                    data.reflexMode <= 2) {

                reflexMode =
                        data.reflexMode;

            } else {

                reflexMode =
                        2;
            }


            if(data.rrMode >= RR_MODE_PERFORMANCE &&
                    data.rrMode <= RR_MODE_ULTRA_PERFORMANCE) {

                rrMode =
                        data.rrMode;

            } else {

                rrMode =
                        RR_MODE_PERFORMANCE;
            }


            upscalerMode = data.upscalerMode != null && data.upscalerMode >= 0 && data.upscalerMode <= 1
                    ? data.upscalerMode : UPSCALER_MODE_RR;
            srMode = data.srMode != null && data.srMode >= 0 && data.srMode <= 4
                    ? data.srMode : RR_MODE_PERFORMANCE;


            if(Float.isFinite(
                    data.noDirectLightAmbient
            ) &&
                    data.noDirectLightAmbient >= 0.0f &&
                    data.noDirectLightAmbient <= 1.0f) {

                noDirectLightAmbient =
                        Math.round(
                                data.noDirectLightAmbient *
                                        10000.0f
                        ) / 10000.0f;

            } else {

                noDirectLightAmbient =
                        0.0001f;
            }


            if(data.pomDepth != null &&
                    Float.isFinite(data.pomDepth) &&
                    data.pomDepth >= 0.0f &&
                    data.pomDepth <= 0.20f) {

                pomDepth =
                        Math.round(
                                data.pomDepth *
                                        100.0f
                        ) / 100.0f;

            } else {

                pomDepth =
                        0.03f;
            }

            if(data.pomSharp != null) {

                pomSharp =
                        data.pomSharp;

            } else {

                pomSharp =
                        false;
            }

            if(data.ueCloud != null &&
                    data.ueCloud.length == UE_CLOUD_PARAM_COUNT) {

                ueCloud =
                        data.ueCloud.clone();

            }

            if(data.trueSubsurfaceScattering != null) {

                trueSubsurfaceScattering =
                        data.trueSubsurfaceScattering;

            } else {

                trueSubsurfaceScattering =
                        false;
            }

            if(data.localLightMaxDistance != null &&
                    Float.isFinite(data.localLightMaxDistance) &&
                    data.localLightMaxDistance >= 4.0f &&
                    data.localLightMaxDistance <= 30.0f) {

                localLightMaxDistance =
                        Math.round(
                                data.localLightMaxDistance
                        );

            } else {

                localLightMaxDistance =
                        15.0f;

            }

            if(data.localLightIntensity != null &&
                    Float.isFinite(data.localLightIntensity) &&
                    data.localLightIntensity >= 0.0f &&
                    data.localLightIntensity <= 4.0f) {

                localLightIntensity =
                        Math.round(
                                data.localLightIntensity * 100.0f
                        ) / 100.0f;

            } else {

                localLightIntensity =
                        1.0f;

            }

            if(data.dynamicLightIntensity != null &&
                    Float.isFinite(data.dynamicLightIntensity) &&
                    data.dynamicLightIntensity >= 0.0f &&
                    data.dynamicLightIntensity <= 4.0f) {

                dynamicLightIntensity =
                        Math.round(
                                data.dynamicLightIntensity * 100.0f
                        ) / 100.0f;

            } else {

                dynamicLightIntensity =
                        1.0f;

            }

            if(data.postBloomStrength != null &&
                    Float.isFinite(data.postBloomStrength) &&
                    data.postBloomStrength >= 0.0f &&
                    data.postBloomStrength <= 2.0f) {

                postBloomStrength =
                        Math.round(
                                data.postBloomStrength * 100.0f
                        ) / 100.0f;

            } else {

                postBloomStrength =
                        0.0f;

            }

            postGainR = validCloudSetting(data.postGainR, 1.0f, 0.25f, 2.5f, 0.05f);
            postGainG = validCloudSetting(data.postGainG, 1.0f, 0.25f, 2.5f, 0.05f);
            postGainB = validCloudSetting(data.postGainB, 1.0f, 0.25f, 2.5f, 0.05f);

            sunVisualSettings = new SunVisualSettings(
                    validCloudSetting(data.sunDiskLuminance,
                            DEFAULT_SUN_VISUAL_SETTINGS.diskLuminance(), 0.0f, 8.0f, 0.01f),
                    validCloudSetting(data.sunHaloStrength,
                            DEFAULT_SUN_VISUAL_SETTINGS.haloStrength(), 0.0f, 1.0f, 0.01f),
                    validCloudSetting(data.sunAngularDiameterDegrees,
                            DEFAULT_SUN_VISUAL_SETTINGS.angularDiameterDegrees(), 0.10f, 2.00f, 0.01f)
            );

            atmosphereSettings = new AtmosphereSettings(
                    validCloudSetting(data.starIntensity,
                            DEFAULT_ATMOSPHERE_SETTINGS.starIntensity(), 0.0f, 2.0f, 0.05f),
                    validCloudSetting(data.sunTintStrength,
                            DEFAULT_ATMOSPHERE_SETTINGS.sunTintStrength(), 0.0f, 1.0f, 0.01f),
                    data.physicalSkyEnabled == null || data.physicalSkyEnabled,
                    validCloudSetting(data.scatterIntensity,
                            DEFAULT_ATMOSPHERE_SETTINGS.scatterIntensity(), 0.0f, 2.0f, 0.05f),
                    validCloudSetting(data.ozoneStrength,
                            DEFAULT_ATMOSPHERE_SETTINGS.ozoneStrength(), 0.0f, 2.0f, 0.05f)
            );

            powderStrength = validCloudSetting(data.powderStrength, 3.0f, 0.0f, 3.0f, 0.05f);

            auroraBrightness = validCloudSetting(data.auroraBrightness, 1.0f, 0.0f, 3.0f, 0.05f);

            auroraFlowSpeed = validCloudSetting(data.auroraFlowSpeed, 1.0f, 0.0f, 4.0f, 0.05f);

            cloudSunForwardScattering =
                    validCloudSetting(data.cloudSunForwardScattering, 1.0f, 0.0f, 1.0f, 0.05f);

            if(data.cloudModelVersion != null && data.cloudModelVersion == CLOUD_MODEL_VERSION) {
                planeCloudSettings = new PlaneCloudSettings(
                        data.planeCloudEnabled == null || data.planeCloudEnabled,
                        validCloudSetting(data.planeCloudHeightOffset, 6000.0f, -20000, 200000, 100.0f),
                        validCloudSetting(data.planeCloudScale, 1.0f, 0.01f, 20, 0.01f),
                        validCloudSetting(data.planeCloudSpeed, 1.0f, 0, 20, 0.05f),
                        validCloudSetting(data.planeCloudDensity, 4.0f, 0, 100, 0.1f),
                        validCloudSetting(data.planeCloudCoverage, 0.45f, 0, 1, 0.01f),
                        Math.round(validCloudSetting(data.planeCloudOctaves, 5.0f, 1, 8, 1.0f)),
                        validCloudSetting(data.planeCloudFadeDistance, 100000.0f, 1000, 300000, 1000.0f),
                        validCloudSetting(data.planeCloudBrightness, 1.0f, 0, 6, 0.05f));
                cloudHpSettings = CloudHpSettings.sanitized(data.cloudHp);
                colorGradeSettings = ColorGradeSettings.sanitized(data.colorGrade);
                cloudMsSettings = new CloudMsSettings(
                        validCloudSetting(data.cloudPhiIntensity, 1.5f, 0, 6, 0.05f),
                        validCloudSetting(data.cloudPhiDepthPow, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudPhiDepthBias, 0.0f, -0.5f, 0.5f, 0.05f),
                        validCloudSetting(data.cloudMsOctaveStrength, 0.5f, 0, 1, 0.01f));
                cloudPhiSettings = new CloudPhiSettings(
                        validCloudSetting(data.cloudPhiBoundaryConfidence, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudPhiMsBuildScale, 1.0f, -1, 8, 0.05f),
                        validCloudSetting(data.cloudPhiCompress, 1.5f, -1, 8, 0.25f),
                        validCloudSetting(data.cloudPhiConeRatio, 2.0f, -1, 6, 0.25f));
                cloudLightSettings = new CloudLightSettings(
                        validCloudSetting(data.cloudSourceOdScale, 1.0f, -1, 8, 0.05f),
                        validCloudSetting(data.cloudSourceCurvePow, 1.0f, -1, 8, 0.05f),
                        validCloudSetting(data.cloudAoUpwardScale, 1.0f, -1, 8, 0.05f),
                        validCloudSetting(data.cloudSunsetAmbientTint, 0.6f, 0, 1, 0.05f));
                cloudShapeSettings = new CloudShapeSettings(
                        validCloudSetting(data.cloudBottomSmoothHeight, 0.22f, 0, 1, 0.02f),
                        validCloudSetting(data.cloudBottomSmoothPow, 1.0f, 0.05f, 8, 0.05f),
                        validCloudSetting(data.cloudTwoTierThreshold, 0.004f, 0, 0.5f, 0.001f));
                cloudMoonSettings = new CloudMoonSettings(
                        validCloudSetting(data.cloudMoonStrength, 1.0f, -1, 4, 0.05f),
                        validCloudSetting(data.cloudMoonPhase, 1.0f, -1, 1, 0.05f));
                cloudShape2Settings = new CloudShape2Settings(
                        validCloudSetting(data.cloudDetailScale, 1.0f, 0.05f, 8, 0.05f),
                        validCloudSetting(data.cloudDetailContrast, 1.0f, 0.05f, 8, 0.05f),
                        validCloudSetting(data.cloudProfileCurve, 1.0f, 0.05f, 8, 0.05f));
                cloudAmbientSettings = new CloudAmbientSettings(
                        validCloudSetting(data.cloudAmbientTopMul, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.cloudAmbientBottomMul, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.cloudAmbientSunSide, 0.35f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudAmbientHueFollow, 1.0f, 0, 1, 0.05f));
                cloudSkyHueSettings = new CloudSkyHueSettings(
                        validCloudSetting(data.cloudSkyHueCool, 0.65f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSkyHueEnergy, 0.50f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSkyDirStrength, 0.0f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudAmbientUnify, 0.0f, 0, 1, 0.05f));
                cloudPhysicsSettings = new CloudPhysicsSettings(
                        validCloudSetting(data.cloudGroundAlbedo, 0.20f, 0, 0.8f, 0.05f),
                        validCloudSetting(data.cloudGroundBounce, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSunlightResidual, 0.0f, 0, 0.25f, 0.01f),
                        validCloudSetting(data.cloudPhaseFloor, 0.0f, 0, 1, 0.05f));
                cloudMs2Settings = new CloudMs2Settings(
                        validCloudSetting(data.cloudScatterTint, 0.35f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudMsOctaves, 2.0f, 1, 3, 1.0f),
                        validCloudSetting(data.cloudMsAttenuation, 0.5f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudMsEccentricity, 0.5f, 0, 1, 0.05f));
                cloudLight2Settings = new CloudLight2Settings(
                        validCloudSetting(data.cloudSunPathReddening, 0.6f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudAtmosphereExtinction, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudPowderMode, 1.0f, 0, 1, 1.0f));
                cloudProfileSettings = new CloudProfileSettings(
                        validCloudSetting(data.cloudProfileType, 0.25f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudProfileTypeSpread, 0.0f, 0, 1, 0.05f));
                cloudDetailSettings = new CloudDetailSettings(
                        validCloudSetting(data.cloudWispyStrength, 0.25f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudWispyEdgeWidth, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudWispyReach, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudWispyTopHeight, 0.0f, 0, 1, 0.05f));
                fogExtrasSettings = new FogExtrasSettings(
                        validCloudSetting(data.fogDynamicLightStrength, 1.0f, 0, 4, 0.05f),
                        validCloudSetting(data.fogGroundAnchorStrength, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.fogSkyAmbientStrength, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.fogTemporalJitterStrength, 1.0f, 0, 1, 0.05f));
                fogWispSettings = new FogWispSettings(
                        data.fogWispEnabled == null || data.fogWispEnabled,
                        validCloudSetting(data.fogWispStrength, 1.2f, 0, 4, 0.05f),
                        validCloudSetting(data.fogWispWavelength, 180.0f, 8, 1024, 1.0f),
                        validCloudSetting(data.fogWispSpeed, 0.55f, 0, 8, 0.05f));
                cloudTemporalSettings = new CloudTemporalSettings(
                        validCloudSetting(data.cloudSkyVisibilityStrength, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSkyVisibilitySteps, 0.0f, 0, 32, 1.0f),
                        validCloudSetting(data.cloudTemporalDampStrength, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudBottomOcclusion, 0.0f, 0, 1, 0.05f));
                cloudAerialSettings = new CloudAerialSettings(
                        validCloudSetting(data.cloudAerialStrength, 0.0f, 0, 2, 0.05f),
                        validCloudSetting(data.groundAerialStrength, 0.0f, 0, 2, 0.05f),
                        validCloudSetting(data.groundAerialOcclusion, 0.0f, 0, 1, 0.05f));
                cloudSunReddening = validCloudSetting(data.cloudSunReddening, 0.0f, 0, 160, 1f);
                cloudTopStretch = validCloudSetting(data.cloudTopStretch, 0.0f, 0, 32, 1f);
                cloudDetileStrength = validCloudSetting(data.cloudDetileStrength, 10.0f, 0.1f, 12f, 0.1f);
                if(cloudDetileStrength <= 2.0f) {
                    cloudDetileStrength = 10.0f;
                }
                cloudType = validCloudSetting(data.cloudType, 0.8f, 0f, 1f, 0.01f);
                if(Math.abs(cloudType - 0.35f) < 0.005f) {
                    cloudType = 0.8f;
                }
                cloudErosionScale = validCloudSetting(data.cloudErosionScale, 1.0f, 0f, 2f, 0.05f);
                cloudWeatherScale = validCloudSetting(data.cloudWeatherScale, 0.75f, 0.0f, 0.99f, 0.01f);
                cloudSilver = validCloudSetting(data.cloudSilver, 0.08f, 0.0f, 1.0f, 0.01f);
                cloudViewDistance = validCloudSetting(data.cloudViewDistance, 120000f, 2000f, 200000f, 1000f);
                cloudMarchSettings = new CloudMarchSettings(
                        validCloudSetting(data.cloudAdaptiveLightRange, 2000.0f, 0, 12000, 100.0f),
                        data.cloudTwoTierStep == null || data.cloudTwoTierStep,
                        data.cloudMeanDepthReproject == null || data.cloudMeanDepthReproject,
                        validCloudSetting(data.cloudFarFadeDistance, 80000.0f, 0, 420000, 1000.0f));
                waterParamsSettings = new WaterParamsSettings(
                        validCloudSetting(data.waterCrestBacklit, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.waterCausticDispersion, 0.4f, 0, 1, 0.01f),
                        validCloudSetting(data.waterCausticDepthDiffusion, 0.5f, 0, 1, 0.01f),
                        data.waterPhysicalPhase != null && data.waterPhysicalPhase);
                waterWaveSettings = new WaterWaveSettings(
                        validCloudSetting(data.waterWaveCalibration, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.waterWaveStrength, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.waterWaveBlend, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.waterWaveReflectCausticStrength,
                                4.0f, 0, 8, 0.05f));
                waterSimWaveSettings = new WaterSimWaveSettings(
                        validCloudSetting(data.waterSimSpongeWidth, 0.0f, 0, 0.45f, 0.01f),
                        validCloudSetting(data.waterSimGaussianSource, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.waterSimDampingScale, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.waterSimPropagationSpeed, 1.0f, 0, 2, 0.05f));
                waterWaveShapeSettings = new WaterWaveShapeSettings(
                        validCloudSetting(data.waterWaveLengthScale, 1.0f, 0.35f, 4.0f, 0.05f),
                        validCloudSetting(data.waterWaveDirection, 0.0f, -3600.0f, 3600.0f, 5.0f),
                        validCloudSetting(data.waterWaveCrestSharpness, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.waterWaveWarpStrength, 1.0f, 0, 2, 0.05f));
                waterWaveAflSettings = new WaterWaveAflSettings(
                        validCloudSetting(data.waterWaveAflScale, 3.5f, 2, 8, 0.1f),
                        validCloudSetting(data.waterWaveAflLayers, 12.0f, 1, 12, 1.0f));
                waterFftSettings = new WaterFftSettings(
                        data.waterFftEnabled == null || data.waterFftEnabled,
                        validCloudSetting(data.waterFftAmplitude, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.waterFftChoppiness, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.waterFftTilePeriod, 10.0f, 2, 128, 1.0f),
                        validCloudSetting(data.waterFftDetail, 1.0f, 0, 3, 0.05f));
                postProcessingSettings = new PostProcessingSettings(
                        data.postTonemapMode == null ? 0
                            : Math.max(0, Math.min(6, data.postTonemapMode)),
                        validCloudSetting(data.postExposureStrength, 0.6f, 0, 1, 0.05f),
                        validCloudSetting(data.postContrast, 1.35f, 0.5f, 2, 0.05f),
                        validCloudSetting(data.postSaturation, 1.08f, 0, 2, 0.05f),
                        validCloudSetting(data.postGamma, 2.2f, 1.6f, 2.8f, 0.02f),
                        data.postMirrorBounceCount == null ? 1
                            : Math.max(1, Math.min(8, data.postMirrorBounceCount)),
                        data.postTlasCullDistance == null ? 0
                            : Math.max(0, Math.min(512, data.postTlasCullDistance)),
                        data.postTlasFrustumCullMargin == null ? 0
                            : Math.max(0, Math.min(256, data.postTlasFrustumCullMargin)),
                        data.postRrFarCloudDistance == null ? 4000
                            : Math.max(0, Math.min(20000, data.postRrFarCloudDistance)));
                if(data.waterWaveVersion == null || data.waterWaveVersion < WATER_WAVE_VERSION)
                {
                    waterWaveSettings = new WaterWaveSettings(
                            0.0f, 1.0f, 0.0f, 4.0f);
                    waterSimWaveSettings = new WaterSimWaveSettings(0.0f, 0.0f, 1.0f, 1.0f);
                    save();
                }
                waterSssSettings = new WaterSssSettings(
                        validCloudSetting(data.waterSssNonlinear, 0.5f, 0, 1, 0.05f),
                        validCloudSetting(data.waterSssBoost, 2.0f, 0, 4, 0.05f),
                        validCloudSetting(data.waterSssPhaseG, 0.8f, 0, 0.95f, 0.01f),
                        validCloudSetting(data.waterSssStrength, 1.0f, 0, 2, 0.05f));
                cloudSettings = new CloudSettings(
                        data.cloudEnabled == null || data.cloudEnabled,
        validCloudSetting(data.cloudBottom, DEFAULT_CLOUD_SETTINGS.bottom(), 96.0f, 4000.0f, 16.0f),
                validCloudSetting(data.cloudThickness, DEFAULT_CLOUD_SETTINGS.thickness(), 128.0f, 5000.0f, 16.0f),
                        validCloudSetting(data.cloudCoverage, DEFAULT_CLOUD_SETTINGS.coverage(), 0.10f, 0.90f, 0.01f),
                        validCloudSetting(data.cloudDensity, DEFAULT_CLOUD_SETTINGS.density(), 0.25f, 5.0f, 0.05f),
                validCloudSetting(data.cloudDetailStrength, DEFAULT_CLOUD_SETTINGS.detailStrength(), 0.0f, 1.0f, 0.01f),
                        validCloudSetting(data.cloudWindSpeed, DEFAULT_CLOUD_SETTINGS.windSpeed(), 0.0f, 32.0f, 0.5f),
                        validCloudSetting(data.cloudEvolution, DEFAULT_CLOUD_SETTINGS.evolution(), 0.0f, 2.0f, 0.05f),
                        validSetting(data.cloudQuality, DEFAULT_CLOUD_SETTINGS.quality(), 0, 2),
                        validCloudSetting(data.cloudSize, DEFAULT_CLOUD_SETTINGS.size(), 0.25f, 2.0f, 0.05f),
                        validCloudSetting(data.cloudDistribution, DEFAULT_CLOUD_SETTINGS.distribution(), 0.0f, 1.0f, 0.05f),
                validCloudSetting(data.cloudBaseFeather, DEFAULT_CLOUD_SETTINGS.baseFeather(), 0.0f, 0.33f, 0.01f),
                        validCloudSetting(data.cloudSunlightStrength, DEFAULT_CLOUD_SETTINGS.sunlightStrength(), 0.0f, 3.0f, 0.01f)
                );
            } else {
                cloudSettings = DEFAULT_CLOUD_SETTINGS;
            }

            final boolean legacyAnalyticFog =
                    data.fogExtinction != null && data.fogExtinction <= 0.5f;

            volumetricFogSettings = legacyAnalyticFog
                    ? DEFAULT_VOLUMETRIC_FOG_SETTINGS
                    : new VolumetricFogSettings(
                    data.fogEnabled == null || data.fogEnabled,
                    validCloudSetting(data.fogExtinction,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.density(), 0.0f, 100.0f, 0.05f),
                    validCloudSetting(data.fogCenterHeight,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.height(), -64.0f, 320.0f, 1.0f),
                    validCloudSetting(data.fogThickness,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.scaleHeight(), -64.0f, 320.0f, 1.0f),
                    validCloudSetting(data.fogAnisotropy,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.anisotropy(), 0.0f, 0.95f, 0.05f),
                    validCloudSetting(data.fogAmount,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.sunStrength(), 0.0f, 4.0f, 0.05f),
                    validCloudSetting(data.fogAmbientStrength,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.ambientStrength(), 0.0f, 4.0f, 0.05f),
                    validCloudSetting(data.fogViewDistance,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.maxDistance(), 16.0f, 4096.0f, 16.0f),
                    validCloudSetting(data.fogStartDistance,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.startDistance(), 0.0f, 256.0f, 2.0f),
                    validSetting(data.fogSteps,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.sunSteps(), 1, 8),
                    validCloudSetting(data.fogRainBoost,
                            DEFAULT_VOLUMETRIC_FOG_SETTINGS.rainBoost(), 0.0f, 32.0f, 0.5f)
            );

            volumetricLightSettings = new VolumetricLightSettings(
                    data.godRayEnabled != null && data.godRayEnabled,
                    validSetting(data.godRaySteps,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.steps(), 4, 48),
                    validSetting(data.godRayOcclusionSamples,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.occlusionSamples(), 1, 6),
                    validCloudSetting(data.godRayIntensity,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.intensity(), 0.0f, 4.0f, 0.05f),
                    validCloudSetting(data.godRayReprojection,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.reprojection(), 0.0f, 0.95f, 0.05f)
            );


            hdrEnabled =
                    data.hdrEnabled;

            autoExposureEnabled =
                    data.autoExposureEnabled != null && data.autoExposureEnabled;

            autoExposureCompensation =
                    validCloudSetting(data.autoExposureCompensation,
                            0.0f, -2.0f, 2.0f, 0.1f);

            fgFrameSync =
                    data.fgFrameSync == null || data.fgFrameSync;

            fgRefreshCap =
                    data.fgRefreshCap == null || data.fgRefreshCap;

            slDevPlugins =
                    data.slDevPlugins != null && data.slDevPlugins;

            serEnabled =
                    data.serEnabled == null || data.serEnabled;

            fgMultiplier =
                    data.fgMultiplier != null
                            && isValidFGMultiplier(data.fgMultiplier)
                            ? data.fgMultiplier
                            : 3;

            fgBaseFrameLimit =
                    data.fgBaseFrameLimit != null
                            && isValidFGBaseFrameLimit(data.fgBaseFrameLimit)
                            ? data.fgBaseFrameLimit
                            : FG_BASE_LIMIT_AUTO;

            entityArmorMetal =
                    data.entityArmorMetal != null && data.entityArmorMetal;

            cloudFadeFollowsRrMode =
                    data.cloudFadeFollowsRrMode == null || data.cloudFadeFollowsRrMode;

            cloudPostRrComposite =
                    data.cloudPostRrComposite == null || data.cloudPostRrComposite;

            cloudResFloorPercent =
                    data.cloudResFloorPercent == null
                            ? 0
                            : Math.max(0, Math.min(100, data.cloudResFloorPercent));

            cloudFarStepDensity =
                    (data.cloudFarStepDensity == null ||
                            !Float.isFinite(data.cloudFarStepDensity))
                            ? 0.45f
                : Math.max(0.0f, Math.min(1.0f, data.cloudFarStepDensity));

            giRoulette =
                    data.giRoulette == null
                            ? 1
                            : (data.giRoulette == 0 ? 0 : 1);

            directLightMode =
                    data.directLightMode == null
                            ? 0
                            : (data.directLightMode == 0 ? 0 : 1);

            indirectMode =
                    data.indirectMode == null
                            ? 0
                            : (data.indirectMode == 0 ? 0 : 1);

            sparseRatePercent =
                    data.sparseRatePercent == null
                            ? 0
                            : Math.max(0, Math.min(100, data.sparseRatePercent));

            sunMarchFarDistance =
                    data.sunMarchFarDistance == null
                            ? 32
                            : Math.max(0, Math.min(256, data.sunMarchFarDistance));

            motionDebugView =
                    data.motionDebugView == null
                            ? 0
                            : Math.max(0, Math.min(2, data.motionDebugView));

            motionVectorsEnabled =
                    data.motionVectorsEnabled == null
                            ? 1
                            : Math.max(0, Math.min(3, data.motionVectorsEnabled));

            performanceSettings =
                    new PerformanceSettings(
                            validSetting(data.aoNearSamples, 1, 0, 4),
                            validSetting(data.aoMidSamples, 1, 0, 2),
                            validSetting(data.giBounces, 1, 0, 4),
                            validSetting(data.sunShadowSamples, 2, 1, 2),
                            validSetting(data.localLightRisCandidates, 4, 1, 32),
                            validSetting(data.localLightAreaSamples, 2, 1, 2),
                            validSetting(data.waterReflectionInterval, 1, 0, 4),
                            validSetting(data.glossyFullDistance, 64, 16, 96),
                            validSetting(data.dynamicRtasInterval, 2, 1, 8),
                            validSetting(data.maxRtParticles, 256, 0, 256),
                            validCloudSetting(data.rtSpp, 1.0f, 0.5f, 4.0f, 0.5f),
                validSetting(data.volumetricRestir, 0, 0, 1)
                    );

            rainSettings =
                    new RainSettings(
                            validCloudSetting(data.rainOpacity,
                                    DEFAULT_RAIN_SETTINGS.opacity(), 0.05f, 1.0f, 0.01f),
                            validCloudSetting(data.rainStreakLength,
                                    DEFAULT_RAIN_SETTINGS.streakLength(), 0.4f, 8.0f, 0.1f),
                            validCloudSetting(data.rainBrightness,
                                    DEFAULT_RAIN_SETTINGS.brightness(), 0.05f, 3.0f, 0.01f),
                            validCloudSetting(data.rainWidth,
                                    DEFAULT_RAIN_SETTINGS.width(), 1.0f, 40.0f, 0.1f),
                            validCloudSetting(data.rainDensityScale,
                                    DEFAULT_RAIN_SETTINGS.densityScale(), 0.1f, 2.0f, 0.05f)
                    );

            rainWallSettings =
                    new RainWallSettings(
                            data.rainWallEnabled == null || data.rainWallEnabled,
                            validCloudSetting(data.rainWallWetness,
                                    DEFAULT_RAIN_WALL_SETTINGS.wetness(), 0.0f, 2.0f, 0.05f),
                            validCloudSetting(data.rainWallDripDensity,
                                    DEFAULT_RAIN_WALL_SETTINGS.dripDensity(), 0.0f, 1.0f, 0.01f),
                            validCloudSetting(data.rainWallDripSpeed,
                                    DEFAULT_RAIN_WALL_SETTINGS.dripSpeed(), 0.1f, 4.0f, 0.05f),
                            validCloudSetting(data.rainWallDripSize,
                                    DEFAULT_RAIN_WALL_SETTINGS.dripSize(), 0.25f, 4.0f, 0.05f),
                            validCloudSetting(data.rainWallDripBump,
                                    DEFAULT_RAIN_WALL_SETTINGS.dripBump(), 0.0f, 2.0f, 0.05f),
                            validCloudSetting(data.rainWallDistance,
                                    DEFAULT_RAIN_WALL_SETTINGS.distance(), 16.0f, 160.0f, 8.0f)
                    );

            snowSettings =
                    new SnowSettings(
                            validCloudSetting(data.snowSizeScale,
                                    DEFAULT_SNOW_SETTINGS.sizeScale(), 0.25f, 4.0f, 0.05f),
                            validCloudSetting(data.snowDensityScale,
                                    DEFAULT_SNOW_SETTINGS.densityScale(), 0.25f, 4.0f, 0.05f),
                            validCloudSetting(data.snowOpacity,
                                    DEFAULT_SNOW_SETTINGS.opacity(), 0.05f, 1.0f, 0.01f),
                            validCloudSetting(data.snowBrightness,
                                    DEFAULT_SNOW_SETTINGS.brightness(), 0.05f, 3.0f, 0.01f),
                            validCloudSetting(data.snowFallSpeedScale,
                                    DEFAULT_SNOW_SETTINGS.fallSpeedScale(), 0.25f, 4.0f, 0.05f),
                            validCloudSetting(data.snowSway,
                                    DEFAULT_SNOW_SETTINGS.swayBlocks(), 0.0f, 1.0f, 0.05f)
                    );

            galaxySettings =
                    new GalaxySettings(
                            validCloudSetting(data.galaxyScale,
                                    DEFAULT_GALAXY_SETTINGS.scale(), 0.2f, 3.0f, 0.05f),
                            validCloudSetting(data.galaxyTiltDegrees,
                                    DEFAULT_GALAXY_SETTINGS.tiltDegrees(), 0.0f, 90.0f, 1.0f),
                            validCloudSetting(data.galaxyYawDegrees,
                                    DEFAULT_GALAXY_SETTINGS.yawDegrees(), -180.0f, 180.0f, 5.0f),
                            validCloudSetting(data.galaxyMix,
                                    DEFAULT_GALAXY_SETTINGS.mix(), 0.0f, 1.0f, 0.05f)
                    );

            waterStyleSettings =
                    new WaterStyleSettings(
                            validCloudSetting(data.waterStyleWaveScale,
                                    DEFAULT_WATER_STYLE_SETTINGS.waveScale(), 0.0f, 2.0f, 0.05f),
                            validCloudSetting(data.waterStyleTintMix,
                                    DEFAULT_WATER_STYLE_SETTINGS.tintMix(), 0.0f, 1.0f, 0.01f),
                            validCloudSetting(data.waterStyleDensityScale,
                                    DEFAULT_WATER_STYLE_SETTINGS.densityScale(), 0.25f, 4.0f, 0.05f),
                            validCloudSetting(data.waterStyleCausticStrength,
                                    DEFAULT_WATER_STYLE_SETTINGS.causticStrength(), 0.0f, 4.0f, 0.05f),
                            validCloudSetting(data.waterStyleWaveSpeed,
                                    DEFAULT_WATER_STYLE_SETTINGS.waveSpeed(), 0.0f, 3.0f, 0.05f),
                            validCloudSetting(data.waterStyleSwellScale,
                                    DEFAULT_WATER_STYLE_SETTINGS.swellScale(), 0.0f, 2.0f, 0.05f),
                            validCloudSetting(data.waterStyleMidScale,
                                    DEFAULT_WATER_STYLE_SETTINGS.midScale(), 0.0f, 2.0f, 0.05f),
                            validCloudSetting(data.waterStyleFineScale,
                                    DEFAULT_WATER_STYLE_SETTINGS.fineScale(), 0.0f, 2.0f, 0.05f)
                    );


        } catch(IOException |
                RuntimeException e) {

            System.err.println(
                    "[RTX CONFIG] Load failed: "
                            + e.getMessage()
            );


            reflexMode =
                    2;

            rrMode =
                    RR_MODE_PERFORMANCE;
            upscalerMode = UPSCALER_MODE_RR;
            srMode = RR_MODE_PERFORMANCE;

            noDirectLightAmbient =
                    0.0001f;

            pomDepth =
                    0.03f;

            pomSharp =
                    false;

            trueSubsurfaceScattering =
                    false;

            localLightMaxDistance =
                    15.0f;

            localLightIntensity =
                    1.0f;

            dynamicLightIntensity =
                    1.0f;

            cloudSettings = DEFAULT_CLOUD_SETTINGS;

            hdrEnabled =
                    false;

            autoExposureEnabled =
                    false;

            autoExposureCompensation =
                    0.0f;

            fgFrameSync =
                    true;

            serEnabled =
                    true;

            entityArmorMetal =
                    false;

            cloudFadeFollowsRrMode =
                    true;

            cloudPostRrComposite =
                    true;

            cloudResFloorPercent =
                    0;

            cloudFarStepDensity =
                    0.45f;
            giRoulette =
                    1;

            directLightMode =
                    0;

            sparseRatePercent =
                    0;

            sunMarchFarDistance =
                    32;

            motionDebugView =
                    0;

            motionVectorsEnabled =
                    1;

            fgMultiplier =
                    3;

            fgBaseFrameLimit =
                    FG_BASE_LIMIT_AUTO;

            performanceSettings =
                    DEFAULT_PERFORMANCE_SETTINGS;

            atmosphereSettings =
                    DEFAULT_ATMOSPHERE_SETTINGS;

            powderStrength =
                    3.0f;

            cloudSunForwardScattering =
                    1.0f;

            volumetricFogSettings =
                    DEFAULT_VOLUMETRIC_FOG_SETTINGS;

            volumetricLightSettings =
                    DEFAULT_VOLUMETRIC_LIGHT_SETTINGS;

            rainSettings =
                    DEFAULT_RAIN_SETTINGS;

            rainWallSettings =
                    DEFAULT_RAIN_WALL_SETTINGS;

            galaxySettings =
                    DEFAULT_GALAXY_SETTINGS;
        }
    }

    private static int validSetting(
            Integer value,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        return value != null && value >= minimum && value <= maximum
                ? value : defaultValue;
    }

    private static float validCloudSetting(
            Float value,
            float defaultValue,
            float minimum,
            float maximum,
            float increment
    ) {
        if(value == null || !Float.isFinite(value) || value < minimum || value > maximum) {
            return defaultValue;
        }
        return Math.min(maximum, Math.max(minimum, Math.round(value / increment) * increment));
    }


    private static boolean settingsDirty = false;

    /** setter 用：只记账，不落盘。 */
    private static synchronized void markDirty() {
        settingsDirty = true;
    }

    /** 把 markDirty() 攒下的改动一次性写盘；没有改动时直接返回。 */
    public static synchronized void flush() {
        if(!settingsDirty) {
            return;
        }
        save();
    }


    private static synchronized void save() {

        try {
            Files.createDirectories(
                    CONFIG_PATH.getParent()
            );


            Data data =
                    new Data();


            data.reflexMode =
                    reflexMode;
            data.rrMode =
                    rrMode;
            data.upscalerMode = upscalerMode;
            data.srMode = srMode;

            data.noDirectLightAmbient =
                    noDirectLightAmbient;

            data.pomDepth =
                    pomDepth;

            data.pomSharp =
                    pomSharp;

            data.ueCloud =
                    ueCloud.clone();

            data.trueSubsurfaceScattering =
                    trueSubsurfaceScattering;

            data.localLightMaxDistance =
                    localLightMaxDistance;

            data.localLightIntensity =
                    localLightIntensity;

            data.dynamicLightIntensity =
                    dynamicLightIntensity;

            data.postBloomStrength =
                    postBloomStrength;

            data.postGainR = postGainR;
            data.postGainG = postGainG;
            data.postGainB = postGainB;

            data.sunDiskLuminance = sunVisualSettings.diskLuminance();
            data.sunHaloStrength = sunVisualSettings.haloStrength();
            data.sunAngularDiameterDegrees = sunVisualSettings.angularDiameterDegrees();

            data.starIntensity = atmosphereSettings.starIntensity();
            data.sunTintStrength = atmosphereSettings.sunTintStrength();
            data.physicalSkyEnabled = atmosphereSettings.physicalSkyEnabled();
            data.scatterIntensity = atmosphereSettings.scatterIntensity();
            data.ozoneStrength = atmosphereSettings.ozoneStrength();
            data.powderStrength = powderStrength;
            data.cloudSunForwardScattering = cloudSunForwardScattering;
            data.auroraBrightness = auroraBrightness;
            data.auroraFlowSpeed = auroraFlowSpeed;

            data.cloudModelVersion = CLOUD_MODEL_VERSION;
            data.waterWaveVersion = WATER_WAVE_VERSION;
            data.planeCloudEnabled = planeCloudSettings.enabled();
            data.planeCloudHeightOffset = planeCloudSettings.heightOffset();
            data.planeCloudScale = planeCloudSettings.scale();
            data.planeCloudSpeed = planeCloudSettings.speed();
            data.planeCloudDensity = planeCloudSettings.density();
            data.planeCloudCoverage = planeCloudSettings.coverage();
            data.planeCloudOctaves = (float) planeCloudSettings.octaves();
            data.planeCloudFadeDistance = planeCloudSettings.fadeDistance();
            data.planeCloudBrightness = planeCloudSettings.brightness();
            data.cloudHp = cloudHpSettings.toParamArray();
            data.colorGrade = colorGradeSettings.toParamArray();
            data.cloudPhiIntensity = cloudMsSettings.phiIntensity();
            data.cloudPhiDepthPow = cloudMsSettings.phiDepthPow();
            data.cloudPhiDepthBias = cloudMsSettings.phiDepthBias();
            data.cloudMsOctaveStrength = cloudMsSettings.octaveStrength();
            data.cloudPhiBoundaryConfidence = cloudPhiSettings.boundaryConfidence();
            data.cloudPhiMsBuildScale = cloudPhiSettings.msBuildScale();
            data.cloudPhiCompress = cloudPhiSettings.compress();
            data.cloudPhiConeRatio = cloudPhiSettings.coneRatio();
            data.cloudSourceOdScale = cloudLightSettings.sourceOdScale();
            data.cloudSourceCurvePow = cloudLightSettings.sourceCurvePow();
            data.cloudAoUpwardScale = cloudLightSettings.aoUpwardScale();
            data.cloudSunsetAmbientTint = cloudLightSettings.sunsetAmbientTint();
            data.cloudBottomSmoothHeight = cloudShapeSettings.bottomSmoothHeight();
            data.cloudBottomSmoothPow = cloudShapeSettings.bottomSmoothPow();
            data.cloudTwoTierThreshold = cloudShapeSettings.twoTierThreshold();
            data.cloudMoonStrength = cloudMoonSettings.moonStrength();
            data.cloudMoonPhase = cloudMoonSettings.moonPhase();
            data.cloudDetailScale = cloudShape2Settings.detailScale();
            data.cloudDetailContrast = cloudShape2Settings.detailContrast();
            data.cloudProfileCurve = cloudShape2Settings.profileCurve();
            data.cloudAmbientTopMul = cloudAmbientSettings.topMultiplier();
            data.cloudAmbientBottomMul = cloudAmbientSettings.bottomMultiplier();
            data.cloudAmbientSunSide = cloudAmbientSettings.sunSideStrength();
            data.cloudAmbientHueFollow = cloudAmbientSettings.hueFollow();
            data.cloudSkyHueCool = cloudSkyHueSettings.skyHueCool();
            data.cloudSkyHueEnergy = cloudSkyHueSettings.skyHueEnergy();
            data.cloudSkyDirStrength = cloudSkyHueSettings.skyDirStrength();
            data.cloudAmbientUnify = cloudSkyHueSettings.ambientUnify();
            data.cloudGroundAlbedo = cloudPhysicsSettings.groundAlbedo();
            data.cloudGroundBounce = cloudPhysicsSettings.groundBounce();
            data.cloudSunlightResidual = cloudPhysicsSettings.sunlightResidual();
            data.cloudPhaseFloor = cloudPhysicsSettings.phaseFloor();
            data.cloudScatterTint = cloudMs2Settings.scatterTint();
            data.cloudMsOctaves = cloudMs2Settings.octaves();
            data.cloudMsAttenuation = cloudMs2Settings.attenuation();
            data.cloudMsEccentricity = cloudMs2Settings.eccentricity();
            data.cloudSunPathReddening = cloudLight2Settings.sunPathReddening();
            data.cloudAtmosphereExtinction = cloudLight2Settings.atmosphereExtinction();
            data.cloudPowderMode = cloudLight2Settings.powderMode();
            data.cloudProfileType = cloudProfileSettings.cloudType();
            data.cloudProfileTypeSpread = cloudProfileSettings.typeSpread();
            data.cloudWispyStrength = cloudDetailSettings.wispyStrength();
            data.cloudWispyEdgeWidth = cloudDetailSettings.wispyEdgeWidth();
            data.cloudWispyReach = cloudDetailSettings.wispyReach();
            data.cloudWispyTopHeight = cloudDetailSettings.wispyTopHeight();
            data.fogDynamicLightStrength = fogExtrasSettings.dynamicLightStrength();
            data.fogGroundAnchorStrength = fogExtrasSettings.groundAnchorStrength();
            data.fogSkyAmbientStrength = fogExtrasSettings.skyAmbientStrength();
            data.fogTemporalJitterStrength = fogExtrasSettings.temporalJitterStrength();
            data.fogWispEnabled = fogWispSettings.enabled();
            data.fogWispStrength = fogWispSettings.strength();
            data.fogWispWavelength = fogWispSettings.wavelength();
            data.fogWispSpeed = fogWispSettings.speed();
            data.cloudSkyVisibilityStrength = cloudTemporalSettings.skyVisibilityStrength();
            data.cloudSkyVisibilitySteps = cloudTemporalSettings.skyVisibilitySteps();
            data.cloudTemporalDampStrength = cloudTemporalSettings.temporalDampStrength();
            data.cloudBottomOcclusion = cloudTemporalSettings.bottomOcclusion();
            data.cloudAerialStrength = cloudAerialSettings.cloudAerialStrength();
            data.groundAerialStrength = cloudAerialSettings.groundAerialStrength();
            data.groundAerialOcclusion = cloudAerialSettings.groundAerialOcclusion();
            data.cloudSunReddening = cloudSunReddening;
            data.cloudTopStretch = cloudTopStretch;
            data.cloudDetileStrength = cloudDetileStrength;
        data.cloudType = cloudType;
        data.cloudErosionScale = cloudErosionScale;
        data.cloudWeatherScale = cloudWeatherScale;
        data.cloudSilver = cloudSilver;
        data.cloudViewDistance = cloudViewDistance;
            data.cloudAdaptiveLightRange = cloudMarchSettings.adaptiveLightRange();
            data.cloudTwoTierStep = cloudMarchSettings.twoTierStep();
            data.cloudMeanDepthReproject = cloudMarchSettings.meanDepthReproject();
            data.cloudFarFadeDistance = cloudMarchSettings.farFadeDistance();
            data.waterCrestBacklit = waterParamsSettings.crestBacklit();
            data.waterCausticDispersion = waterParamsSettings.causticDispersion();
            data.waterCausticDepthDiffusion = waterParamsSettings.causticDepthDiffusion();
            data.waterPhysicalPhase = waterParamsSettings.physicalPhase();
            data.waterWaveCalibration = waterWaveSettings.calibration();
            data.waterWaveStrength = waterWaveSettings.strength();
            data.waterWaveBlend = waterWaveSettings.blend();
            data.waterWaveReflectCausticStrength =
                    waterWaveSettings.reflectCausticStrength();
            data.waterSimSpongeWidth = waterSimWaveSettings.spongeWidth();
            data.waterSimGaussianSource = waterSimWaveSettings.gaussianSource();
            data.waterSimDampingScale = waterSimWaveSettings.dampingScale();
            data.waterSimPropagationSpeed = waterSimWaveSettings.propagationSpeed();
            data.waterWaveLengthScale = waterWaveShapeSettings.lengthScale();
            data.waterWaveDirection = waterWaveShapeSettings.directionDegrees();
            data.waterWaveCrestSharpness = waterWaveShapeSettings.crestSharpness();
            data.waterWaveWarpStrength = waterWaveShapeSettings.warpStrength();
            data.waterWaveAflScale = waterWaveAflSettings.wavelength();
            data.waterWaveAflLayers = waterWaveAflSettings.octaves();
            data.waterFftEnabled = waterFftSettings.enabled();
            data.waterFftAmplitude = waterFftSettings.amplitude();
            data.waterFftChoppiness = waterFftSettings.choppiness();
            data.waterFftTilePeriod = waterFftSettings.tilePeriod();
            data.waterFftDetail = waterFftSettings.detail();
            data.postTonemapMode = postProcessingSettings.tonemapMode();
            data.postExposureStrength = postProcessingSettings.exposureStrength();
            data.postContrast = postProcessingSettings.contrast();
            data.postSaturation = postProcessingSettings.saturation();
            data.postGamma = postProcessingSettings.gamma();
            data.postMirrorBounceCount = postProcessingSettings.mirrorBounceCount();
            data.postTlasCullDistance = postProcessingSettings.tlasCullDistance();
            data.postTlasFrustumCullMargin = postProcessingSettings.tlasFrustumCullMargin();
            data.postRrFarCloudDistance = postProcessingSettings.rrFarCloudDistance();
            data.cloudEnabled = cloudSettings.enabled();
            data.cloudBottom = cloudSettings.bottom();
            data.cloudThickness = cloudSettings.thickness();
            data.cloudCoverage = cloudSettings.coverage();
            data.cloudDensity = cloudSettings.density();
            data.cloudDetailStrength = cloudSettings.detailStrength();
            data.cloudWindSpeed = cloudSettings.windSpeed();
            data.cloudEvolution = cloudSettings.evolution();
            data.cloudQuality = cloudSettings.quality();
            data.cloudSize = cloudSettings.size();
            data.cloudDistribution = cloudSettings.distribution();
            data.cloudBaseFeather = cloudSettings.baseFeather();
            data.cloudSunlightStrength = cloudSettings.sunlightStrength();

            data.fogEnabled = volumetricFogSettings.enabled();
            data.fogExtinction = volumetricFogSettings.density();
            data.fogCenterHeight = volumetricFogSettings.height();
            data.fogThickness = volumetricFogSettings.scaleHeight();
            data.fogAnisotropy = volumetricFogSettings.anisotropy();
            data.fogAmount = volumetricFogSettings.sunStrength();
            data.fogAmbientStrength = volumetricFogSettings.ambientStrength();
            data.fogViewDistance = volumetricFogSettings.maxDistance();
            data.fogStartDistance = volumetricFogSettings.startDistance();
            data.fogSteps = volumetricFogSettings.sunSteps();
            data.fogRainBoost = volumetricFogSettings.rainBoost();

            data.godRayEnabled = volumetricLightSettings.enabled();
            data.godRaySteps = volumetricLightSettings.steps();
            data.godRayOcclusionSamples = volumetricLightSettings.occlusionSamples();
            data.godRayIntensity = volumetricLightSettings.intensity();
            data.godRayReprojection = volumetricLightSettings.reprojection();

            data.hdrEnabled =
                    hdrEnabled;

            data.autoExposureEnabled =
                    autoExposureEnabled;

            data.autoExposureCompensation =
                    autoExposureCompensation;

            data.fgFrameSync =
                    fgFrameSync;

            data.fgRefreshCap =
                    fgRefreshCap;

            data.slDevPlugins =
                    slDevPlugins;

            data.entityArmorMetal =
                    entityArmorMetal;

            data.cloudFadeFollowsRrMode =
                    cloudFadeFollowsRrMode;

            data.cloudPostRrComposite =
                    cloudPostRrComposite;

            data.cloudResFloorPercent =
                    cloudResFloorPercent;

            data.cloudFarStepDensity =
                    cloudFarStepDensity;

            data.giRoulette =
                    giRoulette;

            data.directLightMode =
                    directLightMode;

            data.indirectMode =
                    indirectMode;

            data.sparseRatePercent =
                    sparseRatePercent;

            data.sunMarchFarDistance =
                    sunMarchFarDistance;

            data.motionDebugView =
                    motionDebugView;

            data.motionVectorsEnabled =
                    motionVectorsEnabled;

            data.serEnabled =
                    serEnabled;

            data.fgMultiplier =
                    fgMultiplier;

            data.fgBaseFrameLimit =
                    fgBaseFrameLimit;

            data.aoNearSamples = performanceSettings.aoNearSamples();
            data.aoMidSamples = performanceSettings.aoMidSamples();
            data.giBounces = performanceSettings.giBounces();
            data.sunShadowSamples = performanceSettings.sunShadowSamples();
            data.localLightRisCandidates = performanceSettings.localLightRisCandidates();
            data.localLightAreaSamples = performanceSettings.localLightAreaSamples();
            data.waterReflectionInterval = performanceSettings.waterReflectionInterval();
            data.glossyFullDistance = performanceSettings.glossyFullDistance();
            data.dynamicRtasInterval = performanceSettings.dynamicRtasInterval();
            data.maxRtParticles = performanceSettings.maxRtParticles();
            data.rtSpp = performanceSettings.rtSpp();
        data.volumetricRestir = performanceSettings.volumetricRestir();

            data.rainOpacity = rainSettings.opacity();
            data.rainStreakLength = rainSettings.streakLength();
            data.rainBrightness = rainSettings.brightness();
            data.rainWidth = rainSettings.width();
            data.rainDensityScale = rainSettings.densityScale();
            data.snowSizeScale = snowSettings.sizeScale();
            data.snowDensityScale = snowSettings.densityScale();
            data.snowOpacity = snowSettings.opacity();
            data.snowBrightness = snowSettings.brightness();
            data.snowFallSpeedScale = snowSettings.fallSpeedScale();
            data.snowSway = snowSettings.swayBlocks();
            data.galaxyScale = galaxySettings.scale();
            data.galaxyTiltDegrees = galaxySettings.tiltDegrees();
            data.galaxyYawDegrees = galaxySettings.yawDegrees();
            data.galaxyMix = galaxySettings.mix();

            data.rainWallEnabled = rainWallSettings.enabled();
            data.rainWallWetness = rainWallSettings.wetness();
            data.rainWallDripDensity = rainWallSettings.dripDensity();
            data.rainWallDripSpeed = rainWallSettings.dripSpeed();
            data.rainWallDripSize = rainWallSettings.dripSize();
            data.rainWallDripBump = rainWallSettings.dripBump();
            data.rainWallDistance = rainWallSettings.distance();

            data.waterStyleWaveScale = waterStyleSettings.waveScale();
            data.waterStyleTintMix = waterStyleSettings.tintMix();
            data.waterStyleDensityScale = waterStyleSettings.densityScale();
            data.waterStyleCausticStrength = waterStyleSettings.causticStrength();
            data.waterStyleWaveSpeed = waterStyleSettings.waveSpeed();
            data.waterStyleSwellScale = waterStyleSettings.swellScale();
            data.waterStyleMidScale = waterStyleSettings.midScale();
            data.waterStyleFineScale = waterStyleSettings.fineScale();
            data.waterSssNonlinear = waterSssSettings.nonlinear();
            data.waterSssBoost = waterSssSettings.boost();
            data.waterSssPhaseG = waterSssSettings.phaseG();
            data.waterSssStrength = waterSssSettings.strength();


            try (
                    Writer writer =
                            Files.newBufferedWriter(
                                    CONFIG_PATH,
                                    StandardCharsets.UTF_8
                            )
            ) {
                GSON.toJson(
                        data,
                        writer
                );
            }


            System.out.println(
                    "[RTX CONFIG]"
                            + " Reflex=" + reflexMode
                            + " RR=" + rrMode
                            + " Ambient=" + noDirectLightAmbient
                            + " POMDepth=" + pomDepth
                            + " LocalLightDist=" + localLightMaxDistance
                            + " LocalLightIntensity=" + localLightIntensity
                            + " DynLightIntensity=" + dynamicLightIntensity
                            + " Sun=" + sunVisualSettings
                            + " Atmosphere=" + atmosphereSettings
                            + " Powder=" + powderStrength
                            + " CloudSunForward=" + cloudSunForwardScattering
                            + " CloudMs=" + cloudMsSettings
                            + " CloudMarch=" + cloudMarchSettings
                            + " SunReddening=" + cloudSunReddening
                            + " TopStretch=" + cloudTopStretch
                            + " Detile=" + cloudDetileStrength
                            + " WaterParams=" + waterParamsSettings
                            + " Clouds=" + cloudSettings
                            + " Fog=" + volumetricFogSettings
                            + " GodRays=" + volumetricLightSettings
                            + " Rain=" + rainSettings
                            + " RainWall=" + rainWallSettings
                            + " WaterStyle=" + waterStyleSettings
                            + " HDR=" + hdrEnabled
                            + " AutoExposure=" + autoExposureEnabled
                            + " ExposureComp=" + autoExposureCompensation
                            + " FGFrameSync=" + fgFrameSync
                            + " FGMultiplier=" + fgMultiplier
                            + " FGBaseLimit=" + fgBaseFrameLimit
                            + " ArmorMetal=" + entityArmorMetal
                            + " SER=" + serEnabled
                            + " Perf=" + performanceSettings
            );

            settingsDirty = false;

        } catch(IOException e) {

            System.err.println(
                    "[RTX CONFIG] Save failed: "
                            + e.getMessage()
            );
        }
    }


    private static final class Data {

        int reflexMode =
                2;


        int rrMode =
                RR_MODE_PERFORMANCE;
        Integer upscalerMode;
        Integer srMode;

        float noDirectLightAmbient =
                0.01f;


        Float pomDepth =
                null;

        Boolean pomSharp =
                null;

        float[] ueCloud =
                null;

        Boolean trueSubsurfaceScattering =
                null;

        Float localLightMaxDistance =
                null;

        Float localLightIntensity =
                null;

        Float dynamicLightIntensity =
                null;

        Float postBloomStrength =
                null;

        Float postGainR =
                null;
        Float postGainG =
                null;
        Float postGainB =
                null;

        Float sunDiskLuminance;
        Float sunHaloStrength;
        Float sunAngularDiameterDegrees;

        Float starIntensity;
        Float sunTintStrength;
        Boolean physicalSkyEnabled;
        Float scatterIntensity;
        Float ozoneStrength;
        Float powderStrength;
        Float cloudSunForwardScattering;
        Float auroraBrightness;
        Float auroraFlowSpeed;

        Integer cloudModelVersion;
        Integer waterWaveVersion;
        Float waterSssNonlinear;
        Float waterSssBoost;
        Float waterSssPhaseG;
        Float waterSssStrength;
        Boolean planeCloudEnabled;
        Float planeCloudHeightOffset;
        Float planeCloudScale;
        Float planeCloudSpeed;
        Float planeCloudDensity;
        Float planeCloudCoverage;
        Float planeCloudOctaves;
        Float planeCloudFadeDistance;
        Float planeCloudBrightness;
        Float cloudPhiIntensity;
        Float cloudPhiDepthPow;
        Float cloudPhiDepthBias;
        Float cloudMsOctaveStrength;
        Float cloudPhiBoundaryConfidence;
        Float cloudPhiMsBuildScale;
        Float cloudPhiCompress;
        Float cloudPhiConeRatio;
        Float cloudSourceOdScale;
        Float cloudSourceCurvePow;
        Float cloudAoUpwardScale;
        Float cloudSunsetAmbientTint;
        Float cloudBottomSmoothHeight;
        Float cloudBottomSmoothPow;
        Float cloudTwoTierThreshold;
        Float cloudMoonStrength;
        Float cloudMoonPhase;
        Float cloudDetailScale;
        Float cloudDetailContrast;
        Float cloudProfileCurve;
        Float cloudAmbientTopMul;
        Float cloudAmbientBottomMul;
        Float cloudAmbientSunSide;
        Float cloudAmbientHueFollow;
        Float cloudSkyHueCool;
        Float cloudSkyHueEnergy;
        Float cloudSkyDirStrength;
        Float cloudAmbientUnify;
        Float cloudGroundAlbedo;
        Float cloudGroundBounce;
        Float cloudSunlightResidual;
        Float cloudPhaseFloor;
        Float cloudScatterTint;
        Float cloudMsOctaves;
        Float cloudMsAttenuation;
        Float cloudMsEccentricity;
        Float cloudSunPathReddening;
        Float cloudAtmosphereExtinction;
        Float cloudPowderMode;
        Float cloudProfileType;
        Float cloudProfileTypeSpread;
        Float cloudWispyStrength;
        Float cloudWispyEdgeWidth;
        Float cloudWispyReach;
        Float cloudWispyTopHeight;
        Float fogDynamicLightStrength;
        Float fogGroundAnchorStrength;
        Float fogSkyAmbientStrength;
        Float fogTemporalJitterStrength;
        Boolean fogWispEnabled;
        Float fogWispStrength;
        Float fogWispWavelength;
        Float fogWispSpeed;
        Float cloudSkyVisibilityStrength;
        Float cloudSkyVisibilitySteps;
        Float cloudTemporalDampStrength;
        Float cloudBottomOcclusion;
        Float cloudAerialStrength;
        Float groundAerialStrength;
        Float groundAerialOcclusion;
        Float cloudSunReddening;
        Float cloudAdaptiveLightRange;
        Boolean cloudTwoTierStep;
        Boolean cloudMeanDepthReproject;
        Float cloudFarFadeDistance;
        Float cloudTopStretch;
        Float cloudDetileStrength;
        Float cloudType;
        Float cloudErosionScale;
        Float cloudWeatherScale;
        Float cloudSilver;
        Float cloudViewDistance;
        Float waterCrestBacklit;
        Float waterCausticDispersion;
        Float waterCausticDepthDiffusion;
        Boolean waterPhysicalPhase;
        Float waterWaveCalibration;
        Float waterWaveStrength;
        Float waterWaveBlend;
        Float waterWaveReflectCausticStrength;
        Float waterSimSpongeWidth;
        Float waterSimGaussianSource;
        Float waterSimDampingScale;
        Float waterSimPropagationSpeed;
        Float waterWaveLengthScale;
        Float waterWaveDirection;
        Float waterWaveCrestSharpness;
        Float waterWaveWarpStrength;
        Float waterWaveAflScale;
        Float waterWaveAflLayers;
        Boolean waterFftEnabled;
        Float waterFftAmplitude;
        Float waterFftChoppiness;
        Float waterFftTilePeriod;
        Float waterFftDetail;
        Integer postTonemapMode;
        Float postExposureStrength;
        Float postContrast;
        Float postSaturation;
        Float postGamma;
        Integer postMirrorBounceCount;
        Integer postTlasCullDistance;
        Integer postTlasFrustumCullMargin;
        Integer postRrFarCloudDistance;
        Boolean cloudEnabled;
        Float cloudBottom;
        Float cloudThickness;
        Float cloudCoverage;
        float[] cloudHp;
        float[] colorGrade;
        Float cloudDensity;
        Float cloudDetailStrength;
        Float cloudWindSpeed;
        Float cloudEvolution;
        Integer cloudQuality;
        Float cloudSize;
        Float cloudDistribution;
        Float cloudBaseFeather;
        Float cloudSunlightStrength;

        Boolean fogEnabled;
        Float fogAmount;
        Float fogCenterHeight;
        Float fogThickness;
        Float fogAnisotropy;
        Float fogAmbientStrength;
        Float fogStartDistance;
        Float fogScale;
        Float fogSpeed;
        Integer fogDetailLayers;
        Float fogViewDistance;
        Integer fogSteps;
        Float fogExtinction;
        Float fogRainBoost;
        Float fogDawnBoost;

        Boolean fogGroundMistEnabled;
        Float fogGroundMistHeight;
        Float fogGroundMistExtinction;
        Float fogGroundMistDistance;

        Float fogRayleighStrength;
        Float fogRayleighScaleHeight;
        Float fogRainRayleighBoost;
        Float fogRainMediumMix;

        Boolean godRayEnabled;
        Integer godRaySteps;
        Integer godRayOcclusionSamples;
        Float godRayIntensity;
        Float godRayReprojection;


        boolean hdrEnabled =
                false;

        Boolean autoExposureEnabled =
                null;

        Float autoExposureCompensation =
                null;

        Boolean fgFrameSync =
                null;

        Boolean fgRefreshCap =
                null;

        Boolean slDevPlugins =
                null;

        Boolean entityArmorMetal =
                null;

        Boolean cloudFadeFollowsRrMode =
                null;

        Boolean cloudPostRrComposite =
                null;

        Integer cloudResFloorPercent =
                null;

        Float cloudFarStepDensity =
                null;

        Integer giRoulette =
                null;

        Integer directLightMode =
                null;

        Integer indirectMode =
                null;

        Integer sparseRatePercent =
                null;

        Integer sunMarchFarDistance =
                null;

        Integer motionDebugView =
                null;

        Integer motionVectorsEnabled =
                null;

        Boolean serEnabled =
                null;

        Integer fgMultiplier =
                null;

        Integer fgBaseFrameLimit =
                null;

        Integer aoNearSamples;
        Integer aoMidSamples;
        Integer giBounces;
        Integer sunShadowSamples;
        Integer localLightRisCandidates;
        Integer localLightAreaSamples;
        Integer waterReflectionInterval;
        Integer glossyFullDistance;
        Integer dynamicRtasInterval;
        Integer maxRtParticles;
        Integer volumetricRestir;
        Float rtSpp;

        Float rainOpacity;
        Float rainStreakLength;
        Float rainBrightness;
        Float rainWidth;
        Float rainDensityScale;
        Float snowSizeScale;
        Float snowDensityScale;
        Float snowOpacity;
        Float snowBrightness;
        Float snowFallSpeedScale;
        Float snowSway;
        Float galaxyScale;
        Float galaxyTiltDegrees;
        Float galaxyYawDegrees;
        Float galaxyMix;

        Boolean rainWallEnabled;
        Float rainWallWetness;
        Float rainWallDripDensity;
        Float rainWallDripSpeed;
        Float rainWallDripSize;
        Float rainWallDripBump;
        Float rainWallDistance;

        Float waterStyleWaveScale;
        Float waterStyleTintMix;
        Float waterStyleDensityScale;
        Float waterStyleCausticStrength;
        Float waterStyleWaveSpeed;
        Float waterStyleSwellScale;
        Float waterStyleMidScale;
        Float waterStyleFineScale;
    }

    public record PerformanceSettings(
            int aoNearSamples,
            int aoMidSamples,
            int giBounces,
            int sunShadowSamples,
            int localLightRisCandidates,
            int localLightAreaSamples,
            int waterReflectionInterval,
            int glossyFullDistance,
            int dynamicRtasInterval,
            int maxRtParticles,
            

            float rtSpp,
            

            int volumetricRestir
    ) {
        public PerformanceSettings {
            if(aoNearSamples < 0 || aoNearSamples > 4 ||
                    aoMidSamples < 0 || aoMidSamples > 2 ||
                    giBounces < 0 || giBounces > 4 ||
                    sunShadowSamples < 1 || sunShadowSamples > 2 ||
                    localLightRisCandidates < 1 || localLightRisCandidates > 32 ||
                    localLightAreaSamples < 1 || localLightAreaSamples > 2 ||
                    waterReflectionInterval < 0 || waterReflectionInterval > 4 ||
                    glossyFullDistance < 16 || glossyFullDistance > 96 ||
                    dynamicRtasInterval < 1 || dynamicRtasInterval > 8 ||
                    maxRtParticles < 0 || maxRtParticles > 256 ||
                    !Float.isFinite(rtSpp) || rtSpp < 0.5f || rtSpp > 4.0f ||
                    volumetricRestir < 0 || volumetricRestir > 1) {
                throw new IllegalArgumentException("Invalid RT performance settings");
            }
        }
    }

    /**
     * RT rain appearance.  All three fields are consumed on the Java side by
     * ParticleRtCapture, which rebuilds the weather lattice every frame.
     *
     * @param opacity      share of primary rays that survive the stochastic
     *                     streak alpha test; this is how solid the rain reads
     * @param streakLength height of a streak card in blocks.  The authored
     *                     atlas fills ~68% of that, so the visible streak is
     *                     about 0.68 * streakLength long
     * @param brightness   multiplier on the streak carrier colour before it is
     *                     lit; the carrier is deliberately dark, because an
     *                     unoccluded card lit by the whole sky would otherwise
     *                     outshine the self-shadowed storm deck behind it
     */
    public record RainSettings(
            float opacity,
            float streakLength,
            float brightness,
            float width,
            

            float densityScale
    ) {
        public RainSettings {
            if(!Float.isFinite(opacity) || opacity < 0.05f || opacity > 1.0f ||
                    !Float.isFinite(streakLength) || streakLength < 0.4f || streakLength > 8.0f ||
                    !Float.isFinite(brightness) || brightness < 0.05f || brightness > 3.0f ||
                    !Float.isFinite(width) || width < 1.0f || width > 40.0f ||
                    !Float.isFinite(densityScale) ||
                            densityScale < 0.1f || densityScale > 2.0f) {
                throw new IllegalArgumentException("Invalid RT rain settings");
            }
        }

        /**
         * The card half-width the RT path needs, in world blocks, for the
         * requested visible streak width.  See RT_RAIN_REACHABLE_FRACTION in
         * ParticleRtCapture for why the factor is not 1.
         */
        public float halfWidthBlocks(float reachableFraction) {
            return (width * 0.001f) / (2.0f * reachableFraction);
        }
    }

    /**
     * 雪片观感（2026-10-07）。字段语义见 {@link #DEFAULT_SNOW_SETTINGS} 上的
     * 说明块；全部是 Java 侧旋钮，由 ParticleRtCapture 在重建天气格时直接读。
     * 上限的职责与雨的那组一致：拦住失控配置（例如摆幅拉满把每片甩成横扫
     * 全屏的纸片），而不是规定常用行程。
     */
    public record SnowSettings(
            float sizeScale,
            float densityScale,
            float opacity,
            float brightness,
            float fallSpeedScale,
            float swayBlocks
    ) {
        public SnowSettings {
            if(!Float.isFinite(sizeScale) || sizeScale < 0.25f || sizeScale > 4.0f ||
                    !Float.isFinite(densityScale) || densityScale < 0.25f || densityScale > 4.0f ||
                    !Float.isFinite(opacity) || opacity < 0.05f || opacity > 1.0f ||
                    !Float.isFinite(brightness) || brightness < 0.05f || brightness > 3.0f ||
                    !Float.isFinite(fallSpeedScale) || fallSpeedScale < 0.25f || fallSpeedScale > 4.0f ||
                    !Float.isFinite(swayBlocks) || swayBlocks < 0.0f || swayBlocks > 1.0f) {
                throw new IllegalArgumentException("Invalid RT snow settings");
            }
        }
    }

    /**
     * Wall run-off: the wet film and the droplets that run down vertical faces
     * while it rains.  Each field maps to one lane of {@code CameraUBO
     * .rainWallParams}; the tuning block in {@code rt_test.rgen} documents what
     * each one does to the resulting image.
     * <p>
     * The five scale fields are relative to the shader's own constants, so 1.0
     * is exactly the calibrated look and the travel is mostly one-sided from
     * there.  {@code distance} is the exception: it is the only field that is
     * also a cost control, so its range reaches both above and below the
     * default.  Ranges mirror the C++ side validation exactly -- a value that
     * passes here must not be rejected by the native setter.
     */
    public record RainWallSettings(
            boolean enabled,
            float wetness,
            float dripDensity,
            float dripSpeed,
            float dripSize,
            float dripBump,
            float distance
    ) {
        public RainWallSettings {
            if(!Float.isFinite(wetness) ||
                    !Float.isFinite(dripDensity) ||
                    !Float.isFinite(dripSpeed) ||
                    !Float.isFinite(dripSize) ||
                    !Float.isFinite(dripBump) ||
                    !Float.isFinite(distance) ||
                    wetness < 0.0f || wetness > 2.0f ||
                    dripDensity < 0.0f || dripDensity > 1.0f ||
                    dripSpeed < 0.1f || dripSpeed > 4.0f ||
                    dripSize < 0.25f || dripSize > 4.0f ||
                    dripBump < 0.0f || dripBump > 2.0f ||
                    distance < 16.0f || distance > 160.0f) {
                throw new IllegalArgumentException("Invalid RT rain wall settings");
            }
        }
    }

    /**
     * Water style dials (Waterlight turquoise look).  Each field maps to one
     * lane of {@code CameraUBO.waterStyleParams}; the accessor block in
     * {@code rt_test.rgen} documents what each one does to the image.
     * <p>
     * {@code waveScale}, {@code tintMix} and {@code densityScale} are relative
     * to the shader's own constants (identity: 1.0 / 1.0 / 1.0); {@code
     * causticStrength} is the absolute brightness multiplier.  Ranges mirror
     * the C++ side validation exactly.
     */
    public record WaterStyleSettings(
            float waveScale,
            float tintMix,
            float densityScale,
            float causticStrength,
            float waveSpeed,
            float swellScale,
            float midScale,
            float fineScale
    ) {
        public WaterStyleSettings {
            if(!Float.isFinite(waveScale) ||
                    !Float.isFinite(tintMix) ||
                    !Float.isFinite(densityScale) ||
                    !Float.isFinite(causticStrength) ||
                    !Float.isFinite(waveSpeed) ||
                    !Float.isFinite(swellScale) ||
                    !Float.isFinite(midScale) ||
                    !Float.isFinite(fineScale) ||
                    waveScale < 0.0f || waveScale > 2.0f ||
                    tintMix < 0.0f || tintMix > 1.0f ||
                    densityScale < 0.25f || densityScale > 4.0f ||
                    causticStrength < 0.0f || causticStrength > 4.0f ||
                    waveSpeed < 0.0f || waveSpeed > 3.0f ||
                    swellScale < 0.0f || swellScale > 2.0f ||
                    midScale < 0.0f || midScale > 2.0f ||
                    fineScale < 0.0f || fineScale > 2.0f) {
                throw new IllegalArgumentException("Invalid water style settings");
            }
        }
    }

    /**
     * 2026-10-08 末地银河旋钮。每个字段映射 {@code CameraUBO.galaxyParams}
     * 的一个 lane；区间与 C++ 侧 setGalaxySettings 的校验逐字一致。
     * {@code scale} 是大小倍率（调小 = 银河更大）；{@code tiltDegrees} 是银河
     * 盘面与水平面的夹角；{@code yawDegrees} 是核心方位角（银河随星图自转，
     * 这是相对星空的初始方位）；{@code mix} 是叠加强度 —— 图集星空恒做底，
     * 银河按它加权加上去（0 = 只剩原版图集星空，1 = 两者都满亮）。
     */
    public record GalaxySettings(
            float scale,
            float tiltDegrees,
            float yawDegrees,
            float mix
    ) {
        public GalaxySettings {
            if(!Float.isFinite(scale) ||
                    !Float.isFinite(tiltDegrees) ||
                    !Float.isFinite(yawDegrees) ||
                    !Float.isFinite(mix) ||
                    scale < 0.2f || scale > 3.0f ||
                    tiltDegrees < 0.0f || tiltDegrees > 90.0f ||
                    yawDegrees < -180.0f || yawDegrees > 180.0f ||
                    mix < 0.0f || mix > 1.0f) {
                throw new IllegalArgumentException("Invalid galaxy settings");
            }
        }
    }

    // 2026-10-28：coverage 与 size 已**解耦**（旧版把云量塞进密度阈值，调云量会把每朵云
    // 一起削小）：
    //   coverage → 分布遮罩 presence（整片区域有/无云）⇒ 只影响"多少朵"
    //   size     → base 噪声周期（着色器 noiseScale = 0.55²/size）⇒ 只影响"每朵多大"，
    //              值越大云越大（0.55 = 出厂，逐位等同旧档）
    public record CloudSettings(
            boolean enabled,
            float bottom,
            float thickness,
            float coverage,
            float density,
            float detailStrength,
            float windSpeed,
            float evolution,
            int quality,
            float size,
            float distribution,
            float baseFeather,
            float sunlightStrength
    ) {
        public CloudSettings {
            if(!Float.isFinite(bottom) || !Float.isFinite(thickness) ||
                    !Float.isFinite(coverage) || !Float.isFinite(density) ||
                    !Float.isFinite(detailStrength) || !Float.isFinite(windSpeed) ||
                    !Float.isFinite(evolution) || !Float.isFinite(size) ||
                    !Float.isFinite(distribution) || !Float.isFinite(baseFeather) ||
                    !Float.isFinite(sunlightStrength) ||
                    bottom < 96.0f || bottom > 8000.0f ||
                    thickness < 256.0f || thickness > 16384.0f ||
                    coverage < 0.10f || coverage > 0.90f ||
                    density < 0.25f || density > 5.0f ||
                    detailStrength < 0.0f || detailStrength > 0.50f ||
                    windSpeed < 0.0f || windSpeed > 32.0f ||
                    evolution < 0.0f || evolution > 2.0f ||
                    quality < 0 || quality > 2 ||
                    size < 0.25f || size > 3.0f ||
                    distribution < 0.0f || distribution > 1.0f ||
                    baseFeather < 0.0f || baseFeather > 0.33f ||
                    sunlightStrength < 0.0f || sunlightStrength > 3.0f) {
                throw new IllegalArgumentException("Invalid cloud settings");
            }
        }
    }

    public record SunVisualSettings(
            float diskLuminance,
            float haloStrength,
            float angularDiameterDegrees
    ) {
        public SunVisualSettings {
            if(!Float.isFinite(diskLuminance) || !Float.isFinite(haloStrength) ||
                    !Float.isFinite(angularDiameterDegrees) ||
                    diskLuminance < 0.0f || diskLuminance > 8.0f ||
                    haloStrength < 0.0f || haloStrength > 1.0f ||
                    angularDiameterDegrees < 0.10f || angularDiameterDegrees > 2.00f) {
                throw new IllegalArgumentException("Invalid sun visual settings");
            }
        }
    }

    public record AtmosphereSettings(
            float starIntensity,
            float sunTintStrength,
            boolean physicalSkyEnabled,
            float scatterIntensity,
            float ozoneStrength
    ) {
        public AtmosphereSettings {
            if(!Float.isFinite(starIntensity) || !Float.isFinite(sunTintStrength) ||
                    !Float.isFinite(scatterIntensity) || !Float.isFinite(ozoneStrength) ||
                    starIntensity < 0.0f || starIntensity > 2.0f ||
                    sunTintStrength < 0.0f || sunTintStrength > 1.0f ||
                    scatterIntensity < 0.0f || scatterIntensity > 2.0f ||
                    ozoneStrength < 0.0f || ozoneStrength > 2.0f) {
                throw new IllegalArgumentException("Invalid atmosphere settings");
            }
        }
    }
}
