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

    // =====================================================
    // 2026-10-17 云模型版本（设置入口）
    //   3 = 参考模型（双瓣 HG 相位 + 参考 powder + 向日透射 remap + 参考覆盖度/剖面 + 参考噪声）
    //   2 = 旧模型（逐位旧行为，随时可切回）
    // 持久化到独立小文件 `run/config/cloud-model.txt`：**刻意不动** SettingsData/load/save
    // 那套 per-field 序列化（那里少一处就会编译不过或读写损坏）。
    // =====================================================
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
            // 读不到/解析失败 ⇒ 用默认值
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
    // =====================================================
// DLSS Ray Reconstruction quality
//
// 0 = Performance
// 1 = Balanced
// 2 = Quality
// 3 = DLAA
// =====================================================

    public static final int RR_MODE_PERFORMANCE = 0;
    public static final int RR_MODE_BALANCED = 1;
    public static final int RR_MODE_QUALITY = 2;
    public static final int RR_MODE_DLAA = 3;
    // Appended instead of inserted so the values already written to
    // rtx-settings.json keep their meaning. This is DLSS "Ultra Performance",
    // the lowest internal render resolution of the set.
    public static final int RR_MODE_ULTRA_PERFORMANCE = 4;
    public static final int UPSCALER_MODE_RR = 0;
    public static final int UPSCALER_MODE_SR = 1;

    private static int rrMode =
            RR_MODE_PERFORMANCE;
    private static int upscalerMode = UPSCALER_MODE_RR;
    private static int srMode = RR_MODE_PERFORMANCE;

// =====================================================
// Ambient brightness when no direct sunlight is present.
//
// Confirmed range:
// 0.00 - 1.00
// step 0.01
// default 0.10
// =====================================================

    private static float noDirectLightAmbient =
            0.0001f;


// =====================================================
// POM parallax depth.
//
// range: 0.00 - 0.20
// step : 0.01
// default: 0.10
// =====================================================

    private static float pomDepth =
            0.03f;

    // 2026-10-18 视差高度场采样：false = 平滑（双线性，默认 / 现状）；
    // true = 锐利（单纹素）。只影响不透明地形 POM。运行期经
    // NativeVulkan.setPOMSharp 下发到 CameraUBO.pomParams.x。
    private static boolean pomSharp =
            false;

    // 2026-10-22 UE 风格体积云的八项参数（设置界面「UE 体积云」分组）。
    // 用 float[] 而不是八个独立字段：每个标量字段在 Config 里要改六处
    // （字段/getter/setter/反序列化/序列化/映射类），八份就是五十多处样板。
    // 索引与 CameraUBO 的 ueCloudA（偏移 1536）/ ueCloudB（偏移 1552）严格一致，
    // 也等于 NativeVulkan.setUeCloudParam 的 index 参数。
    //   0 msOctaves / 1 msContribution / 2 msOcclusion / 3 luminanceScale
    //   4 groundContribution / 5 cloudAO / 6 atmosphereDensity / 7 weatherScaleMul
    public static final int UE_CLOUD_PARAM_COUNT =
            8;

    // 出厂值 = 预览器「UE 出厂档」（cloud_bevy_preview.comp 的同名赋值）。
    private static float[] ueCloud = {
            1.0f,   // msOctaves
            0.5f,   // msContribution
            0.5f,   // msOcclusion
            1.0f,   // luminanceScale
            0.15f,  // groundContribution
            1.0f,   // cloudAO
            0.35f,  // atmosphereDensity
            1.0f    // weatherScaleMul
    };

    // 2026-10-19 树叶次表面散射：false = 太阳背光（仅直射，默认 / 旧档）；
    // true = 真次表面散射（天光透射射线）。运行期经
    // NativeVulkan.setSSSMode 下发到 CameraUBO.sssParams.x。
    private static boolean trueSubsurfaceScattering =
            false;

    // Local light max distance in blocks (Java slider).
    // 15.0 = the shipped default.  The shader's own compiled-in fallback
    // (VPT_LOCAL_MAX_DISTANCE) is still 30.0, but it only applies when an older
    // native runtime writes 0 into the rainWallParams[7] lane, i.e. it is a
    // compatibility floor and not the intended look.
    private static float localLightMaxDistance =
            15.0f;

    // 本地光源照射强度（Java 滑块）。
    // 1.0 = 出厂观感：着色器里的 LOCAL_LIGHT_ENERGY_SCALE 就是它。
    // 0.0 = 关掉本地光源，上限 4.0。C++ 把它写进 CameraUBO.historyPadding1
    // （该车道全仓无读取点），所以既不动 std140 布局也不动描述符绑定。
    private static float localLightIntensity =
            1.0f;

    // 2026-10-07 动态光源（移动光源：手持发光物 + 自带发光的实体）强度。
    // 1.0 = 出厂；0.0 = 关掉移动光源；上限 4.0（与 localLightIntensity 同量程）。
    //
    // 与 localLightIntensity 的关键区别：这个值**不进 CameraUBO** —— 它随每帧的
    // NativeVulkan.uploadDynamicLights(..., intensity) 一起下发，落在动态光源那条
    // SSBO 的头部（见 cpp/shaders/rt_test.rgen 里 binding 61 的声明）。
    // 因此没有 native setter、也不动任何描述符布局或 UBO 车道；
    // DynamicLightCapture 每帧从本配置读，改完立刻生效。
    private static float dynamicLightIntensity =
            1.0f;

    // 2026-10-07 泛光（bloom）强度。0 = 关（**逐位恒等**：合成着色器整屏早退）；
    // 上限 2.0（再高基本就糊成一片，上限也收在 C++ 那一侧）。
    // 走的是**后处理链的第 9 个 lane**（NativeVulkan.setPostProcessingParams 的
    // values[8]），不是 PostProcessingSettings 那个 record —— 它在设置界面里属于
    // 「后处理」页，但参数通道上是个独立的标量（native 侧 push constant 独立推送）。
    private static float postBloomStrength =
            0.0f;

    // 2026-10-23 调色调（后处理页）：线性光 R/G/B 通道增益，1/1/1 = 逐位恒等。
    // 走 setPostProcessingParams 的 values[9..11]（第 10~12 个 lane，泛光在 [8]），
    // 作用点在色彩映射之前 ⇒ 等效相机白平衡。范围与 native 侧一致：0.25 .. 2.5。
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


// =====================================================
// Atmosphere controls
//
// starIntensity      0.00 - 2.00, step 0.05, default 2.00
// sunTintStrength    0.00 - 1.00, step 0.01, default 1.00
// physicalSkyEnabled default true
// scatterIntensity   0.00 - 2.00, step 0.05, default 0.60
// ozoneStrength      0.00 - 2.00, step 0.05, default 0.70
// powderStrength     0.00 - 3.00, step 0.05, default 3.00
// cloudSunForward    0.00 - 1.00, step 0.05, default 0.00
// auroraBrightness   0.00 - 3.00, step 0.05, default 1.00
// auroraFlowSpeed    0.00 - 4.00, step 0.05, default 1.00
// =====================================================

    public static final AtmosphereSettings DEFAULT_ATMOSPHERE_SETTINGS =
            new AtmosphereSettings(2.00f, 1.00f, true, 0.60f, 0.70f);

    private static AtmosphereSettings atmosphereSettings =
            DEFAULT_ATMOSPHERE_SETTINGS;

    private static float powderStrength =
            3.0f;

    // 相位上限过渡 [0,1]。0 = 旧视觉钳制 1.05；1 = 双瓣 HG 自然峰值 2.52。
    // 新配置/缺失键默认 1；显式保留旧 JSON 中的值，以尊重用户已保存的选择。
    // C++ 侧走 weatherParams.w 这条保留 lane（cloudParams 的 40 个 lane 已满）。
    private static float cloudSunForwardScattering =
            1.0f;

    // 极光亮度倍率 [0,3]。极光是否出现由生物群系（ParticleRtCapture 推
    // auroraParams.x）决定，这里只是叠加在它之上的**亮度**倍率：1.0 = 现网观感，
    // 0 = 关（连夜间环境补光一起关），>1 更亮。走 auroraParams.y 这条已有 lane
    //（evaluateAurora 早就在读它，此前恒为 1.0）。
    private static float auroraBrightness =
            1.0f;

    // 极光流动速度倍率 [0,4]。乘在 aurora_common.glsl 的帘幕时间步上
    //（auroraParams.z，此前恒 1.0）：0 = 凝固，1 = 现网，>1 更快。改速度会让
    // time = frameCount * 速率 * 速度 跳相位，但极光是纯天空叠加、不做时域累积，
    // 跳一下不出残影。
    private static float auroraFlowSpeed =
            1.0f;

    // 2026-10-02 清理：原「火烧云强度」（fireRimStrength，走 cloudParams6.x）
    // 已移除。它只被 #if CLOUD_FIRE_HACK 那条退役经验链使用（默认口径下是死项），
    // 现在着色器用编译期常量 CLOUD_FIRE_RIM_STRENGTH = 2.2（旧档值）。

    // 批次1-A（2026-10-02）：夕阳云体红化 [0,1]。太阳色沿视线路径按 Rayleigh
    // 比例 × 穿层弦长红移：斜视长弦蓝端先衰，云体深处偏红暗。0 = 关（逐位
    // 旧档）。走 cloudParams6.y（退役高层云 lane）。
    // 2026-10-19：本项现在是**光线步进**（0 = 跟随质量档，8~160 覆盖）
    private static float cloudSunReddening =
            0.0f;

    // 批次2-T2b（2026-10-02）：云顶随云量 Möbius 拉伸 [0,1]。云量越高云顶
    // 向上生长越多（满云量+满档 ≈ 3× 顶高），底部精确锚定。走 cloudParams6.z
    //（退役高层云 lane）——tyndall_cloud_field.glsl 直读，云影/反射自动一致。
    // 0 = 关（逐位旧档）。
    // 2026-10-19：本项现在是**阴影步进**（0 = 跟随质量档，1~32 覆盖）
    private static float cloudTopStretch =
            0.0f;

 // 2026-10-18：该 lane（cloudParams6.w）现在是 **v4 密度模型的云缘细节尺度**。
    // 语义 = 侵蚀噪声频率，出厂 10 → cfg.detailScale 80（侵蚀周期 ≈278 格，云缘絮状）。
    // 旧值 1.0（域扭曲强度）对应 cfg.detailScale 8，云缘是光滑的，已作废。
    private static float cloudDetileStrength =
            10.0f;

    // 2026-10-18：v4 外形旋钮（走 cloudParams5.x / .y，两个此前是 0 占位的 lane）。
    // cloudType：0=层云（薄板）0.5=层积云 1=积云（塔状），出厂 0.35。
    // cloudErosionScale：云缘侵蚀幅度倍率，1.0 = 出厂标定（remap 里的 1.7 基准）。
    // 2026-10-18 修：出厂 0.35（层积云）会让云只占层高 0.2~0.48 ≈363 格，
    // 仰视时就是一条薄带 + 一圈圈等高层。改 0.8（积云）填满层厚。
    private static float cloudType = 0.8f;
    private static float cloudErosionScale = 1.0f;

    // 2026-10-18：与预览器对齐的两个入口
    // 2026-10-19：本项改为**远处淡出起点**（0~0.99 的云距比例），出厂 0.75
    private static float cloudWeatherScale = 0.75f;
    // 2026-10-19：逆光银边强度（0~1），出厂 0.08 —— 只要"一丁点"，大了夕阳过曝
    private static float cloudSilver = 0.08f;
    private static float cloudViewDistance = 25000.0f;    // 云视距（格）：远处云只剩糊块/碎带

// =====================================================
// 体积雾（2026-10-19 B 方案）
//
// 当前设置项全部落在 cloudParams7/8/9（偏移不变）：
//   enabled, VFOG_DENSITY_BASE, VFOG_HEIGHT, VFOG_HEIGHT_2,
//   anisotropyG, VFOG_SUNLIGHT_DENSITY, VFOG_FOG_DENSITY, maxDistance,
//   startDistance, VFOG_SUNLIGHT_STEPS, VFOG_RAIN_DENSITY_MUL
//
// 密度剖面、指数前密步长、太阳方向自吸收与能量合成见
// cpp/shaders/volumetric_fog.glsl。旧档的噪声尺度 / 漂移速度 / 细节层数 /
// 贴地薄雾 / Rayleigh / 彩虹 / 闪电 / 晨雾字段已随旧实现删除，不要再按那些
// 字段理解当前配置。
// =====================================================

    // 2026-10-19 B 方案：旧档的 amount / 中心高度 / 厚度 / 衰减 / 贴地薄雾等
    // 字段已删除，2026-10-06 的 amount 公式也不再适用；下面只保留参考 VFOG_*
    // 的出厂值。
    // 出厂值 = 参考 Lib/Settings.glsl:522-551：
    //   VFOG_DENSITY_BASE 1.0 / VFOG_HEIGHT 65 / VFOG_HEIGHT_2 50 /
    //   HG g 0.65 / VFOG_SUNLIGHT_DENSITY 1.0 / VFOG_FOG_DENSITY 1.0 /
    //   VFOG_SUNLIGHT_STEPS 4 / VFOG_RAIN_DENSITY_MUL 2.0
    // 与 C++ 侧 g_fog* 的预初始化逐项一致。
    public static final VolumetricFogSettings DEFAULT_VOLUMETRIC_FOG_SETTINGS =
            new VolumetricFogSettings(true, 1.0f, 65.0f, 50.0f, 0.65f,
                    1.0f, 1.0f, 672.0f, 0.0f, 4, 2.0f);

    private static VolumetricFogSettings volumetricFogSettings =
            DEFAULT_VOLUMETRIC_FOG_SETTINGS;

// =====================================================
// 体积光（2026-10-19：空气体积光退役，只留水下分支）
//
// 空气散射由体积雾的 rayColor 提供；本组设置主要控制水下方向性光束，
// 以及 VL_AIR_GODRAYS = 1 时的旧空气 A/B 路径。见
// cpp/shaders/volumetric_light.comp。
//
// enabled          总开关（默认 true）
// steps            4 - 48, default 12   视线 march 步数（成本）
// occlusionSamples 1 - 6,  default 1    云影 march 采样数（成本）
// intensity        0 - 4,  default 1    乘在昼夜 strength 上的倍率
// reprojection     0 - 0.95, default 0.85  历史累积权重
//
// 旧档的“解析雾 / 与雾共用密度场 / Cornette-Shanks / 阴影图不实现地形遮挡”
// 等描述均已作废：当前 A/B 空气路径用 fog_density_common.glsl 的单一 FogDensity、
// HG 相位，以及逐样本 ray query 地形影。
// =====================================================

    // 2026-09-30 出厂默认从 false 翻为 true：体积光开关曾在 09-25 设置项
    // 重构（旧键丢弃、下次保存重写为默认值）中被静默重置为 false，水下/空气
    // 光柱随之消失（用户："水下视角又没了"）。true = 光柱属默认观感；
    // 性能敏感的玩家可在设置里关（步数滑杆是独立的成本旋钮）。
    public static final VolumetricLightSettings DEFAULT_VOLUMETRIC_LIGHT_SETTINGS =
            new VolumetricLightSettings(true, 12, 1, 1.0f,
                    // reprojection：出厂 0.85（时间累积开）。想逐位对比旧档把它设 0。
                    0.85f);

    private static VolumetricLightSettings volumetricLightSettings =
            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS;

    private static final int CLOUD_MODEL_VERSION = 2;
    // 2026-10-03 水波面/交互波回退版本：老配置被迁移到旧档等价（见 load）。
    private static final int WATER_WAVE_VERSION = 1;
    // 2026-10-02 清理：高层平面云（HighCloudSettings，20 分量）已整条移除 ——
    // GUI 入口、字段、apply/load、JSON 键、JNI setHighCloudParams、宿主原子量全部删除。
    // 它的渲染实现留在 cpp/shaders/high_cloud_field.glsl 里（外层 #if 0 = 退役块，
    // 按仓库规则保留以支持逐字节回溯），当前不产出任何像素。
    // CameraUBO.highCloudParams[20] 这段 lane 作为**布局占位**保留：std140 不允许
    // 跳过字段继续声明，宿主侧由 populateHighCloudParams 写 0。

    // phi_fwd 多重散射漫射场 + Hillaire 散射级联（2026-10-02，移植自 HanPi
    // Volume Cloud）。4 个分量与 CameraUBO.cloudMsParams[4] / volumetric_clouds.comp
    // 的 cloudMsParams 注释逐字对应；toParamArray() 是唯一下发入口
    // （JNI setCloudMsParams）。出厂即开（1.5/1.0/0.0/0.35）；强度与级联都置 0
    // = 回旧档观感。
    //   cm0 = phi 强度(0=关), 云底深度形状 DepthPow(0=关), 云底偏移, 散射级联 con(0=关)
    // （批次1-B：第四分量原为"透光残量"尾巴，已升格为 Hillaire octave 级联
    //   贡献 con——octave o 贡献 con^o、偏心率逐级退化；JAR 从未发布过旧键，
    //   JSON 键直接改名 cloudMsOctaveStrength。）
    // ------------------------------------------------------------------
    // 2026-10-25 v5（HPVolumeCloud 移植）的 28 项全参数。CameraUBO 尾块
    // hpShapeA..hpMiscA 的东西，顺序与 `setCloudHpParams(float[28])` 严格一致。
    // 用**数组**装：28 个 float 逐个声明会连带 28 个 pending 字段 / 28 个滑条
    // 各写一遍，加一项就要改四处；数组 + 下面两张表让 UI 与校验都能循环生成。
    //   · 记录内部 clone 一份，外部拿到的是副本（record 装数组必须做防御性拷贝）
    //   · sanitized() 负责"旧存档没有这个字段/长度不对"时回落出厂值并逐项钳制
    // ------------------------------------------------------------------
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

    // 2026-10-26 **色彩分级**（设置页「色彩映射与调色」）24 项。
    // 与 CloudHpSettings 同一套写法（数组 + 表驱动），原因也一样：24 个 float
    // 逐个声明会连带 24 个 pending 字段 / 24 条滑条再写一遍。
    //
    // 作用点：rr_sdr_post.comp 里 **tonemap + gamma + 饱和度之后** 的显示域，
    // 顺序 = 黑白场 → 支点对比 → 逐通道 Lift/Gamma/Gain → 色相旋转 →
    // 分离色调 → 自然饱和度 → 褪色 → 绿/品红 → 暗角。
    // 全部取中性值时着色器整块跳过 ⇒ 没调过的人画面逐位不变。
    //
    // 走**独立** JNI：NativeVulkan.setColorGradeParams(float[24])。
    // 范围必须与 cpp/VulkanCore.cpp 的 setColorGradeParams 钳制逐项一致。
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
    /** { 最小值, 最大值, 步进 } —— 必须与 cpp 侧钳制一致。 */
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
    // 2026-10-16 ③ 修正：octaveStrength（= 着色器里的 con，MS 能量权重）
    // 出厂 0.35 → 0.5，与参考 `_HP_MS_Contribution` 默认一致。
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
    // 2026-10-16 ③ 修正：attenuation / eccentricity 出厂 0.0 → 0.5。
    // 0.0 在旧着色器里是「回落编译期宏」的哨兵（实际生效 0.35 / 0.5），现在
    // 哨兵已改成 `>= 0.0`（0 = 字面 0，见 volumetric_clouds.comp 的注释），
    // 所以必须把出厂值写成真正的目标值，否则新装用户会拿到 att=0（三个 octave
    // 全无衰减，MS 过曝）。0.5 与参考 `_HP_MS_Attenuation` / 默认 ecc 一致。
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
            // [1] = 云光路大气消光（2026-10-10 启用）。
            // [3] 原为粉末强度，设置删除后 lane w 由 C++ 侧既有光步粉末喂入。
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
            /** 每格消光的倍率（1.0 = 着色器标定档 FOG_WISP_EXTINCTION）。 */
            float strength,
            /** 水平波长（格）：越大浪越大、层越厚（层厚 = 波长 × 0.035）。 */
            float wavelength,
            /** 漂移速度（格/秒），方向固定为云的风向。 */
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
    // 出厂四项全 0 = 关 ⇒ 逐位旧档（底部遮蔽也不例外：默认画面不变）。
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
            // 2026-10-02：上限 6000 → 12000（配套云层厚度上限 6000 → 16384）。光步是
            // 几何级数（步数固定）⇒ 抬高锥覆盖距离几乎不涨成本，只是近端首步变宽。
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
            // 下界收窄（λmax 2）是有意为之，不只是防呆：旧 ROUTE A 档（Scale 1.0）在
            // 新语义下会给出亚像素噪声或方向条纹。让越界值在校验层回落出厂值，
            // 用户不必清 JSON。
            if(!Float.isFinite(wavelength) || wavelength < 2 || wavelength > 8 ||
               !Float.isFinite(octaves) || octaves < 1 || octaves > 12)
                throw new IllegalArgumentException("Invalid water wave dirrot settings");
        }

        public float[] toParamArray() {
            return new float[] { wavelength, kRETIRED_DRAG, octaves, kRETIRED_BAND };
        }

        /** 已退役的「域扭曲」lane 占位值（ROUTE C 出厂档）。 */
        public static final float kRETIRED_DRAG = 0.90f;
        /** 已退役的「波长带宽」lane 占位值（ROUTE C 出厂档）。 */
        public static final float kRETIRED_BAND = 8.0f;
    }
    // 2026-10-15 出厂档由 CPU 复现定档（cpp/.tmp/watergunnell/{sweep,calibrate,dirdist}.py）：
    // λmax 3.5 格 / drag 0.90 / 12 根独立波 / 频段宽 8× × 逐层黄金角方位旋转
    //（ROUTE C；原 cos⁴ 方向常量表 GUNNELL_DIRS 已随 ROUTE B 一起退役）⇒ 96 格窗
    // 自相关峰值 0.058（均匀方向 0.098、改前 count=8 0.182），成本 ≈ 12×5 SFU。
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
            /** VFOG_DENSITY_BASE：整层厚薄的主旋钮（参考出厂 1.0）。 */
            float density,
            /** VFOG_HEIGHT：密度带的**上界**（世界 Y，格；参考 65）。 */
            float height,
            /** VFOG_HEIGHT_2：密度带的**下界**（世界 Y，格；参考 50）。 */
            float scaleHeight,
            /** Henyey-Greenstein 的 g：0 = 各向同性（参考 0.65）。 */
            float anisotropy,
            /** VFOG_SUNLIGHT_DENSITY：太阳入散射强度（参考 1.0）。 */
            float sunStrength,
            /** VFOG_FOG_DENSITY：环境入散射强度（参考 1.0）。 */
            float ambientStrength,
            /** 雾的最远作用距离（格）。 */
            float maxDistance,
            /** 近处留白距离（格）：0 = 贴着镜头就开始有雾。 */
            float startDistance,
            /** VFOG_SUNLIGHT_STEPS：太阳方向的密度 march 步数（参考 4）。 */
            int sunSteps,
            /** VFOG_RAIN_DENSITY_MUL：雨强把密度乘到这个倍数（参考 2.0）。 */
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
            /**
             * 2026-10-04（P1）：时间重投影的历史权重。
             * <ul>
             *   <li>{@code 0} = 关：每帧独立 march（等价于旧行为，无拖尾）；</li>
             *   <li>{@code 0.85}（出厂）= 强时间累积：用抖动噪声在时间上平均掉，
             *       得到更干净的光柱（当前为全分辨率 march，半分辨率档仅作 A/B）；</li>
             *   <li>上限 0.95：再高会在快速转动/云层快速变化时留下可感知拖尾。</li>
             * </ul>
             * 走 cloudProfileParams.z（保留位）；着色器侧还有"有界历史"钳制兜底。
             */
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
            // 2026-10-18 出厂值改为"参考比例"：云底 1200 / 层厚 400（参考整层 206 格）。
            // 旧的 1600/3936 超出新上限，validCloudSetting 会把它当非法值再返回默认，
            // 等于永远停在 3936 ⇒ 这里必须同步改。
 // 2026-10-18 照抄密度函数后标定的一组：
 // 层厚 1500+ 覆盖 0.2 + 密度 0.6。
 // 云团尺度 = baseSize(0.5) × 层厚(1500) = 750 格。
            // 预览实测 62.4 fps，画面为大团积云 + 地平线云毯（见 shots/cc2.png）。
            // 2026-10-18 **层厚锁 350**：2D 密度模型的横向噪声沿 y 恒定，
            // 层厚 >500 时仰视就是一根根竖柱（1500 实测必竖丝，350 干净）。
            // 薄层还让光程变短 ⇒ 预览 32.8 fps（厚层 16）。
            // 2026-10-19 **层厚 350 → 1800**。
            // 之前锁 350 是因为"2D 噪声沿 y 恒定，厚层会出竖条"，
            // 但基础噪声已经是 **3D**（bevyValueNoise3D），那条限制早就不存在了。
            // 实测 1800 层厚：云是球形团块、无竖条、56.6 fps（预览 shots/sea.png）。
            // 覆盖 0.55 / 密度 1.4 配套（厚层光程长，密度不能太高）。
            // 2026-10-19 **严格对齐预览**（--game-save 的实测值）：
            //   云底 1200 / 层厚 1800 / 云量 0.55 / 密度 2.8（σtBase = 0.0056 每格）
            //   云团大小 0.55 / 云型 0.55 / 侵蚀幅度 1.0
            // 预览实测 24.9 fps（56 步 / 12 阴影步），云块饱满、有明暗层次。
            // detailStrength = 0.25 ⇒ 竖向拉伸 2.0 倍（与预览一致）
            new CloudSettings(true, 1200.0f, 1800.0f, 0.55f, 2.8f,
                    0.25f, 11.5f, 2.0f, 1, 0.55f, 1.0f, 0.33f,
                    1.79f);

    private static CloudSettings cloudSettings =
            DEFAULT_CLOUD_SETTINGS;


// =====================================================
// Final display HDR.
// RR internal HDR input is NOT controlled by this.
//
// Ships on: HDR10 is the presentation path.  hdrOutputActive() still ANDs this
// with the surface capability, so a non-HDR display falls back to the SDR
// transform rather than presenting a linear frame.
// =====================================================

    private static boolean hdrEnabled =
            true;

// =====================================================
// RR-7 auto exposure.
//
// The scene average is scaled against the frame's own measured luminance
// before the display transform.  This is the switch for that, plus the one knob
// worth exposing.  The strength of that scale is a shader constant
// (AE_EXPOSURE_STRENGTH in rr_sdr_post.comp / rr_hdr_encode.comp), not a
// setting here.
//
// compensation  -2.0 - +2.0 EV, step 0.1, default 0.0
//
// The compensation is an offset on top of the key the shader was calibrated
// against, not an absolute brightness: 0.0 is exactly the tuned look, so the
// slider is a preference dial rather than a required setup step.  It is also
// the only useful in-game control here -- the adaptation rates and the
// histogram trim are judgement calls that were made once and never need
// revisiting, so they stay C++ constants.
// =====================================================

    // Ships off: the shipped exposure is tuned by hand against the RT output,
    // so auto exposure is an opt-in rather than the default look.
    private static boolean autoExposureEnabled =
            false;

    private static float autoExposureCompensation =
            0.0f;

    // Keep DLSS-G work on the presenting client queue so generated frames do
    // not race the next rendered frame.
    private static boolean fgFrameSync =
            true;

    // 2026-10-22（B2）：按显示器刷新率给帧生成倍率设上限（出厂 开）。
    // 官方 DLSS-G 指南 §7.4：倍率越高，pacer 里积压的待呈现帧越多；60Hz 的
    // 安全上限是 4×、75Hz 是 5×，再高只是把渲染时间换成队列延迟。
    // 关掉后只受驱动 numFramesToGenerateMax 限制（多帧解锁后可上到 6×）。
    private static boolean fgRefreshCap =
            true;

    // 2026-10-22（D3）：从 natives\sl_debug_dev\ 加载 Streamline development
    // 构建，从而启用 sl.imgui（实时数据检查 UI）。
    //
    // 为什么是"重启生效"：sl.interposer.dll 一旦 LoadLibrary 就无法换版本，
    // 且 sl.imgui 只在 dev 构建里加载。所以这里只存意愿，启动早期读取一次。
    private static boolean slDevPlugins =
            false;

    private static boolean serEnabled =
            true;

    // DLSS Frame Generation multiplier, in multiplier units: 2 = one generated
    // frame per rendered frame, 6 = five.  Native clamps the request against
    // what the driver reports, so on Ada without the multi-frame unlock a 4x/6x
    // request quietly runs at 2x instead of failing -- the screen shows the
    // ceiling, this stores the wish so it takes effect once the unlock appears.
    //
    // 2026-10-11：新增"关闭"档。此前可选的最低档是 2x，也就是帧生成无法真正关掉，
    // 而 2x 依然每渲染帧多生成一帧、多一次 present —— 凡"按生成帧/按 present 分配"
    // 的开销（NGX 内部资源、present 路径）都照付。0 表示关闭：原生侧把它当作
    // "当前不需要生成"，走既有的 DLSSGMode::eOff 通路（见 VulkanCore.cpp 的
    // fgDesiredOn），所以是运行期即时生效的开关，不需要重启。
    public static final int FG_MULTIPLIER_OFF = 0;

    private static int fgMultiplier =
            3;

    // Base frame-rate cap for frame generation, in rendered frames per second.
    // -1 pairs it with the display (refresh / multiplier), 0 removes it, and a
    // positive value is an explicit target.  Frame generation multiplies what
    // the renderer produces, so on a 165 Hz panel at 6x anything past roughly
    // 27 fps of rendering is generated and then thrown away.
    //
    // 2026-10-03：出厂改为**自动配对**（-1）。旧出厂值 165 是固定的显示刷新率，
    // 与"倍率"这一侧自相矛盾：默认倍率 3 要求 base ≤ 165/3 = 55，而 165 的固定
    // 上限在 2x 以上几乎一定高于"显示刷新 / 倍率"⇒ 生成的帧根本没有刷新窗口可
    // 落，倍率开得越高越看不到效果（"3x/4x 不生效"的配置侧根因）。自动配对需要
    // 能查到刷新率；查不到时后端会退回 0（不设限）并在日志里写明。
    public static final int FG_BASE_LIMIT_AUTO = -1;
    public static final int FG_BASE_LIMIT_OFF = 0;

    private static int fgBaseFrameLimit =
            FG_BASE_LIMIT_AUTO;

    // Metal/specular response for armour and other equipment. Deliberately off
    // by default: a glossy entity hit costs one extra full-resolution reflection
    // ray per pixel, and armour that fills the screen pays that on nearly every
    // pixel, so it is a look-for-frame-time trade rather than a free
    // improvement. With it off, entity hits keep the flat roughness 0.72 / F0 0
    // response they have always had.
    private static boolean entityArmorMetal =
            false;

    // 2026-10-03 远淡出跟随 RR 档位。地平线那条掠射带（弦长可达 200+ km）同时被
    // "云 march 跟着 RR 档位的渲染分辨率降采样"与"步长量化"打薄，在性能/超高性能
    // 档下糊成板条。项目原本的收口手段就是远淡出（cloudFarFadeDistance，出厂
    // 80 km，让那条带平滑溶解进天空），所以默认按上采样倍率把淡出收短：
    // 倍率 >= 2 时 淡出距离 / 倍率；平衡/质量/DLAA 不动。关掉即回到旧行为。
    private static boolean cloudFadeFollowsRrMode =
            true;

    // 2026-10-06 远云剥离 RR。云在 RR 之前合成进 RR 的颜色输入（render 分辨率），
    // 于是远景云的清晰度被 RR 的上采样限死 = RR 档位反查出的 renderWidth/Height。
    // 开着时云层在输出分辨率渲染、合成搬到 RR evaluate 之后 ⇒ 与档位无关。
    // 代价：云 pass 的像素数按 (输出/render)² 增长。
    // 2026-10-06 首次试：实测观感不可接受（云缘像玻璃一样逐帧闪），退役。
    // 2026-10-08：**用户决定重新启用**。云的降噪改由独立的云降噪链承担
    //（volumetric_cloud_resolve 的时域 + volumetric_cloud_atrous 的多尺度空间），
    // 出厂由 false 改为 true。本键置 false 即逐字节回到"云进 RR 颜色输入"的旧档。
    private static boolean cloudPostRrComposite =
            true;

    // =====================================================
    // 2026-10-04 S2：云 march 分辨率下限（**输出分辨率**的百分比）。
    // 0 = 关闭 = 旧行为（云的分辨率与 RR 档位绑定，降采样档下地平线掠射带只剩
    // 几个像素高）。>0 时 cloudPass = clamp(max(RR质量档 optimal, outputRes×pct%),
    // 上界 = outputRes)：只抬云采样，不动 RR 档位 ⇒ 代价只加在云 pass 上
    //（钳位实现在 VulkanCore.cpp 的 recordFrame 里，上界就是输出分辨率）。
    //
    // 2026-10-06：默认 0 → 100。用户报"远处的云还是模糊"：RR 质量档的 optimal
    // 分辨率明显低于输出分辨率，而云只有 `volumetric_cloud_composite.comp` 那一条
    // 普通 unsharp 上采样（没有带 guide 的上采样器）⇒ 远云必然糊。100 = 云直接按
    // 输出分辨率 march，合成时不再需要放大（相对渲染分辨率还多一点超采样，云缘
    // 更干净、也更少 firefly）。像素数约为原来的 2.3 倍，且**只加在云 pass 上**
    //（不动 RR 档位、不动其它 pass）。跑不动就把设置界面「云分辨率下限」往回收。
    // =====================================================
    private static int cloudResFloorPercent =
            100;

    // 2026-10-04 S3：远段步长加密 [1,4]。1 = 旧档（逐位不变）；越大，march 外层
    // 步长越细，削掉地平线掠射带被"2 km/步"量化出来的板条。只加云 pass 迭代数。
    // 2026-10-19：本项现在是**多次散射强度**（Hillaire 的 msOctave 权重），出厂 0.45。
    private static float cloudFarStepDensity =
            0.45f;

    // =====================================================
    // 2026-10-07 Perf-L10 交错 A/B 实测后的两项 RT 优化（见 rt_test.rgen 的 stage 11/12）
    //
    // 实测（57 对逐对相减，sd ≤ 2.5ms）：rt pass 33.8ms 里漫反射 GI 占 23.3ms，
    // 而这两项分别能省：
    //   giRoulette          → −5.9ms（rt）/ −7.8ms（整帧）
    //   sunMarchFarDistance → −4.0ms（rt）/ −6.0ms（整帧）
    // =====================================================

    // GI 循环的俄罗斯轮盘（能量补偿版，unbiased：只改方差，不改期望）。
    // 1 = 开（**出厂默认**，刻意与"0 = 旧行为"的惯例相反：这项是纯性能收益）；
    // 0 = 旧档（关）。代价是 GI 噪声上升，交给 DLSS RR 降噪。
    private static int giRoulette =
            1;

    // =====================================================
    // 2026-10-26 直接光模式 + 稀疏渲染（CameraUBO.sparseParams 尾块）。
    //
    // 两者出厂都是 0/0 = 传统 RIS + 稀疏关 ⇒ 与接这两项之前逐位一致。
    //   directLightMode  : 0 = 传统 RIS / 1 = RTXDI 式时域 reservoir（WRS）
    //   sparseRatePercent: 0 = 关 / 100 = 全采样（白噪声逐像素抽签）
    // 都是运行时原子量，Apply 下一帧生效，无新 spv 变体。
    // =====================================================
    private static int directLightMode =
            0;

    /**
     * 2026-10-28 间接光模式（对齐 Remix 的 IntegrateIndirectMode）。
     * 0 = GI（出厂，逐位等于改动前）/ 1 = SHaRC 空间哈希辐射缓存。
     * ⚠ SHaRC 的缓冲与 RT 管线在 native init 时创建 ⇒ 切换后**需重启客户端**。
     */
    private static int indirectMode =
            0;

    private static int sparseRatePercent =
            0;

    // =====================================================
    // 2026-10-09 运动矢量（DLSS-RR）**调试视图**（0 = 关，**出厂默认**）。
    //
    // 为什么需要它：MV 出错在画面上只表现为"残影/拖尾"，看不出到底是"根本没写"
    // "符号反了"还是"单位错了" —— 三种病长得一样。这个视图把 rgen **真正写进 MV
    // 图的那个值**直接当颜色画出来（不是另算一遍），于是三种情况一眼可分：
    //   1 = 幅值热图：黑 = 零位移。**静态世界与天空必须纯黑**（若出现整屏灰，
    //       说明有相机成分漏进了 MV —— 相机那一份该由 DLSS 自己加）；移动物体是亮斑。
    //   2 = 有向色：中性灰 = 0，红 = 水平位移、绿 = 垂直位移（带 0.5 偏置，
    //       所以正负都能看见）。
    //
    // 传法：写 CameraUBO.perfAblationStage 的**保留 lane .y**（那条注释当时明写
    // "y/z/w 保留"、全仓无读者），与 giRoulette 走 rtSampleParams.y 是同一做法
    // ⇒ **切换下一帧即生效**，不重建资源、不需要重编着色器。
    // ⚠ 2026-10-17 更正：perfAblationStage 的 .y/.z/.w 现在都有读者（.y 本项、
    // .z 运动矢量总开关、.w 表面诊断），camera_ubo.glsl 的声明注释已同步更正。
    // 这项刻意**不做"缺键 = 出厂开"的回退**：老配置没写这个键就是 0 = 关。
    // =====================================================
    private static int motionDebugView =
            0;

    // =====================================================
    // 2026-10-09 运动矢量（DLSS-RR）**总开关**：1 = 开（**出厂**）/ 0 = 关。
    //
    // 关掉时 rgen 把 MV 图写 0（不是不写）⇒ 与接 MV 之前**逐位一致**：
    // 静态世界与相机运动由 DLSS 自己算对，只有"移动物体"的残影会回来（那是接 MV
    // 之前的既有表现）。
    //
    // 为什么保留这个开关而不是直接删代码：
    //   ① 出问题的即时退路 —— 不用回滚构建、不用碰着色器；
    //   ② 它是最干净的一次**二分**：关掉后异常消失 ⇒ 问题在 MV；仍在 ⇒ 与 MV 无关。
    // 传法同调试图：CameraUBO.perfAblationStage.z（保留 lane），下一帧生效。
    // =====================================================
    private static int motionVectorsEnabled =
            1;

    // 太阳透射 march（traceSunTransmittanceThroughWater，6 步）的"远距简化"距离阈值（格）。
    // 着色点离相机超过该距离时只用 1 步 —— 那个 march 是沿太阳方向穿水/玻璃累积吸收
    // 与染色，远距水体的细节本来就被雾/降噪吃掉，6 步→1 步在实测里省 4ms。
    // 0 = 关（固定 6 步 = 旧档）；出厂 32 格 = 与 traceSunTransmittanceThroughWater 自己的
    // WATER_EXTINCTION_DISTANCE(16) 同量级的保守取值。
    private static int sunMarchFarDistance =
            32;

    public static final PerformanceSettings DEFAULT_PERFORMANCE_SETTINGS =
            new PerformanceSettings(1, 1, 1, 2, 2, 2, 1, 64, 2, 256, 1.0f, 0);

    private static PerformanceSettings performanceSettings =
            DEFAULT_PERFORMANCE_SETTINGS;

    // The RT rain lattice is built entirely on the Java side, so these knobs
    // need no native plumbing at all -- they are read straight out of
    // ParticleRtCapture while it emits the per-frame weather quads.
    //
    // They exist because the streak look is a pure judgement call that has to
    // be made in game: the cards are fixed-size billboards, so the same
    // settings read very differently depending on how close the nearest drop
    // happens to be.
    //
    //   width         visible streak width, in millimetres
    //   streakLength  card height, in blocks
    //   opacity       vertex alpha, i.e. how many rays survive the test
    //   brightness    scales the carrier colour before it is lit
    //
    // `width` is the only knob that changes how *thick* the rain looks, and it
    // is deliberately independent of `streakLength`.  It used to be derived
    // from the length -- the card was `2 * RT_RAIN_HALF_WIDTH * scale` with
    // `scale = streakLength / 2`, so wanting long streaks forced wide ones and
    // the only way to thin the rain out was to make it short.  That coupling
    // is gone: the card's world half-width now comes straight from this
    // setting, and the length only stretches it vertically.
    //
    // `opacity` does move the width a little, because the shader's `0.025`
    // cutoff means a lower vertex alpha also drops the atlas' anti-aliased
    // edges: measured over the shipped atlas, the reachable fraction of the
    // card goes from 8.1% at opacity 1.0 down to 4.4% at opacity 0.05.  So it
    // is a real but secondary width control.  NOTE: the conversion below is
    // calibrated against opacity 0.78 (7.7% reachable); it is a fixed
    // reference point for that maths, not a claim about the current default.
    //
    // Shipped defaults are a thin, high-survival drizzle:
    //   opacity 1.00  the survival test sits at its ceiling, so every texel
    //                 that passes draws at full albedo and the streaks stay
    //                 crisp rather than dissolving into the mist.
    //   length  0.40  shortest card the atlas still reads as a streak instead
    //                 of a dot, so a drop two blocks from the eye no longer
    //                 draws a near-full-height line.
    //   bright  0.05  bottom of the range, so the crisper alpha does not punch
    //                 through the storm deck behind it.
    //   width   1.00 mm  thinnest carrier.
    // At opacity 1.0 the measured reachable fraction is ~8.1% rather than the
    // 7.7% the conversion below assumes, so the drawn streak lands a few
    // percent *over* the nominal millimetre value -- which at a 1.0 mm default
    // is the intended direction, since a strictly sub-pixel streak would
    // vanish entirely at range.
    //
    // Historical note: an earlier pass shipped 0.45 / 0.6 / 0.15 / 1.5 because
    // an undimmed opacity 1.0 had read as hard white scratches ("扎眼").  That
    // softer set is still a legitimate alternative look; the low brightness
    // and narrow width above are what keep the crisp version readable.
    public static final RainSettings DEFAULT_RAIN_SETTINGS =
            new RainSettings(1.0f, 0.4f, 0.05f, 1.0f, 1.0f);

    private static RainSettings rainSettings =
            DEFAULT_RAIN_SETTINGS;

    // =====================================================
    // 2026-10-07 雪（SnowSettings）。与雨的那组同一条管线约定：RT 雪片格子
    // 完全在 Java 侧（ParticleRtCapture.buildRtWeather），这些旋钮不需要任何
    // native 通道，改了下一轮 20 Hz 重建立即生效。
    //
    // 六个旋钮分两类——观感（大小 / 浓度 / 亮度 / 下落速度 / 摇摆）和唯一的
    // "铺多远铺多密"（密度）。1.0 的 scale 档就是本次手工调校的出厂观感，
    // 而不是区间的中点；有用的行程基本都是单向的（同 RainWallSettings 的
    // 哲学）。
    //
    //   sizeScale      雪片尺寸倍率（作用于 0.06~0.19 的半边长带，全宽 = 2x）
    //   densityScale   生成格数倍率（(64+192*rain)*7*scale，顶格 1764 格）
    //   opacity        顶点 alpha = 射线存活率（同"雨线浓度"语义）
    //   brightness     冷白着色缩放（同"雨线亮度"语义）
    //   fallSpeedScale 下落速度倍率（作用于 0.085~0.145 的柱高分数/秒带）
    //   swayBlocks     水平摇摆幅度（绝对格；每片落点的遮挡探测已按实际
    //                  位置进行，摆幅超过半格距也不会穿屋檐）
    // =====================================================
    public static final SnowSettings DEFAULT_SNOW_SETTINGS =
            new SnowSettings(1.0f, 1.0f, 0.85f, 1.0f, 1.0f, 0.30f);

    private static SnowSettings snowSettings =
            DEFAULT_SNOW_SETTINGS;

    // =====================================================
    // Rain run-off on vertical faces.
    //
    // Kept separate from RainSettings above: that block tunes the falling
    // weather lattice that ParticleRtCapture rebuilds every frame, this one
    // tunes the wet-wall model in rt_test.rgen.  The difference that matters is
    // the plumbing -- these values have to round-trip through the native side,
    // because the shader reads them from CameraUBO.rainWallParams, so unlike the
    // streak settings a push here can fail and has to be reported.
    //
    // The scale fields are relative to the shader's own constants, where 1.0 is
    // the hand-tuned identity.  The shipped set pushes the film and the run-off
    // past that identity -- wetness 2.0 (the strongest film the range allows),
    // dripSpeed 2.25 and dripBump 1.1 -- while dripSize and dripDensity stay
    // at identity (dripSize replaced the retired trailLength in the same slot).
    // Distance is absolute rather than a factor, because "this
    // far" has no natural identity, and it ships at 24 blocks; it is also the
    // only field here that doubles as a cost control.
    // =====================================================
    public static final RainWallSettings DEFAULT_RAIN_WALL_SETTINGS =
            new RainWallSettings(true, 2.0f, 1.0f, 2.25f, 1.0f, 1.1f, 24.0f);

    private static RainWallSettings rainWallSettings =
            DEFAULT_RAIN_WALL_SETTINGS;

    // =====================================================
    // 2026-10-08 末地银河旋钮。原生侧应用（着色器从 CameraUBO.galaxyParams
    // 每帧读取），规则同雨痕/水色：先推 JNI，接受才落盘。出厂值 = 2026-10-08
    // 银河初版的编译期常量（大小 0.85 / 盘面倾斜 30° / 核心方位 -45°），叠加强度
    // 0.5 = 同一天由"替换式"改成叠加时取量程中点。
    // =====================================================
    public static final GalaxySettings DEFAULT_GALAXY_SETTINGS =
            new GalaxySettings(0.85f, 30.0f, -45.0f, 0.5f);

    private static GalaxySettings galaxySettings =
            DEFAULT_GALAXY_SETTINGS;

    // =====================================================
    // Water style (Waterlight turquoise look).
    //
    // Native-backed like the wall block above: the shaders read these from
    // CameraUBO.waterStyleParams, so a push can fail and is reported.  Three of
    // the four are dials over the shader's own hand-tuned constants, where 1.0
    // is the calibrated identity: tintMix and densityScale ship at identity and
    // waveScale at 1.15.  causticStrength is the one absolute value -- the
    // shader has no natural identity for a brightness multiplier -- and it
    // ships at 4.0, the top of its range.
    // =====================================================
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

    // The multipliers the settings screen offers. 0 (FG_MULTIPLIER_OFF) is the
    // off switch, 2x is the stock Ada ceiling, 3x/4x/6x are what the multi-frame
    // unlock exposes.
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

    // -1 automatic, 0 off, otherwise an explicit frame rate. The lower bound is
    // deliberately above a token 1 fps so a stale config cannot pin the renderer
    // to a slideshow; the upper bound leaves room above the 300 fps the screen
    // offers for anyone who edits the config by hand.
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

    // Read by the entity capture on the render thread, so a flip takes effect on
    // the next capture -- no restart, and no native or shader plumbing: the
    // captured material class is what the closest-hit reads.
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

    // 2026-10-06 远云剥离 RR（见 NativeVulkan.setCloudPostRrComposite 的注释）：
    // 云层改在输出分辨率渲染、合成挪到 RR evaluate 之后，远云清晰度不再被 RR 档位
    // 的上采样限死。代价是云 pass 在输出分辨率跑。关掉回到旧档。
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
        // 上限 256 格：再远已经超出体素/水面细节的有效尺度，1 步与 6 步的差异
        // 只剩噪声（见字段注释）。
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
        // 2026-10-19：逆光银边强度 0~1（原来 1.0~4.0 是远段步长加密的范围）
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


        // 2026-10-27：步进从 0.01 改为 0.0001（用户要求环境底光可设到 0.0001）。
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


        // 两位小数，与 C++ 侧 setLocalLightIntensity 的 snap 一致，
        // 避免配置里出现浮点噪声导致每次读盘都判定成"变了"。
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


        // 两位小数，与 localLightIntensity 同款 snap，避免浮点噪声。
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


        // 两位小数：与本地光/动态光强度同款 snap，避免浮点噪声。
        float rounded =
                Math.round(value * 100.0f) / 100.0f;

        if (Float.compare(postBloomStrength, rounded) != 0) {
            postBloomStrength = rounded;
            markDirty();
        }
    }

    // 2026-10-23 调色调增益（线性光 R/G/B；1 = 恒等）
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

    // 两位小数 + 夹到 [0.25, 2.5]（native 侧同样夹一次，防两边跑偏）
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
        // 2026-10-19：本项现在是**光线步进**（0 = 跟随质量档，8~160 覆盖）
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
        // 2026-10-18：改为云缘细节尺度，范围 0.1~12（×8 → cfg.detailScale）
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
        // 2026-10-19：改为**远处淡出起点**（0~0.99 的云距比例）
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
        // 2026-10-19：本项现在是**阴影步进**（0 = 跟随质量档，1~32 覆盖）
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


        // 固定为界面上的 0.1 EV 步进。
        float rounded = Math.round(value * 10.0f) / 10.0f;
        if (Float.compare(autoExposureCompensation, rounded) != 0) {
            autoExposureCompensation = rounded;
            markDirty();
        }
    }


    // =====================================================
    // 2026-10-03 出厂默认基座（随包 rtx-settings.default.json）
    //
    // 「所有玩家装上包以后的默认观感」不再散落在本文件上百个字段初值 /
    // DEFAULT_* 常量 / load() 的缺键回落里，而是一份 JSON 快照：
    //   src/main/resources/rtx-settings.default.json
    // 由 processResources 打进 jar 根，与 natives 一起分发。
    //
    // 行为：
    //   · 首次安装（config/rtx-settings.json 不存在）→ 快照落盘成玩家配置，
    //     随后走既有 load() 路径 ⇒ 开局即这套调校；
    //   · 已有配置的玩家 → 一个字节都不动（各自调校优先，不覆盖）；
    //   · 快照缺席（旧运行时 / 打包遗漏）→ 静默跳过，回落字段初值（旧行为）。
    //
    // 想改出厂观感：只改那个 JSON 即可，不必碰本文件的字段初值 ——
    // 字段初值从此退化为"快照缺席"时的兜底。
    // =====================================================
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
            // 出厂默认只是"更好的起点"：任何异常都不该影响加载。
        }
    }


    private static synchronized void load() {

        // 首次安装：先把随包的出厂默认落盘，下面两条既有路径无需任何改动。
        ensureConfigFromShippedDefaults();

        // 首次没有配置文件：
        // 保持所有已确认默认值。
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
                        0.45f;   // 多次散射强度（Hillaire 标定）

                giRoulette =
                        1;

                directLightMode =
                        0;

                sparseRatePercent =
                        0;

                sunMarchFarDistance =
                        32;

                // 2026-10-09：调试图的出厂值 = 关（恢复默认必须能关掉它）。
                motionDebugView =
                        0;

                // 总开关出厂 = 开。
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


            // Reflex
            if(data.reflexMode >= 0 &&
                    data.reflexMode <= 2) {

                reflexMode =
                        data.reflexMode;

            } else {

                reflexMode =
                        2;
            }


            // Ray Reconstruction quality
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


            // No-direct-light ambient brightness
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


            // POM parallax depth.
            // Boxed Float is intentional: old config files do not
            // contain this field, so null means use the new default.
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

            // 2026-10-18 POM height-field sampling (smooth/sharp). Boxed
            // Boolean is intentional: old config files do not contain this
            // field, so null means the smooth default.
            if(data.pomSharp != null) {

                pomSharp =
                        data.pomSharp;

            } else {

                pomSharp =
                        false;
            }

            // 2026-10-22 UE 体积云八项。旧配置文件没有这个数组 => null => 保留出厂值。
            // 长度不对（被手改成 7 项之类）也保留出厂值，避免数组越界。
            if(data.ueCloud != null &&
                    data.ueCloud.length == UE_CLOUD_PARAM_COUNT) {

                ueCloud =
                        data.ueCloud.clone();

            }

            // 2026-10-19 foliage subsurface-scattering mode. Boxed Boolean is
            // intentional: old config files do not contain this field, so null
            // means the legacy sun-only mode.
            if(data.trueSubsurfaceScattering != null) {

                trueSubsurfaceScattering =
                        data.trueSubsurfaceScattering;

            } else {

                trueSubsurfaceScattering =
                        false;
            }

            // Local light max distance (blocks).
            // Boxed Float is intentional: old config files do not
            // contain this field, so null means use the new default.
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

            // Local light intensity (slider).
            // Boxed Float is intentional for the same reason as above: config
            // files written before this field existed carry null, and null must
            // mean "use the new default" rather than "local lights off".
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

            // 2026-10-07 动态光源（移动光源）强度。null 同样是故意的：写这份配置时
            // 字段还不存在的旧文件解出 null ⇒ 用新默认 1.0，而不是"关掉移动光源"（0.0）。
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

            // 2026-10-07 泛光强度。null = 旧配置里没有这个字段 ⇒ 用默认 0（关），
            // 也就是"升级上来不会有意外泛光"。
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

            // 2026-10-23 调色调增益：缺键 ⇒ 1/1/1（旧配置逐位不变）。
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
 // 2026-10-09 平面云。旧的高层平面云 20 分量
                // 读取已于 2026-10-02 随该层退役移除；这里是**新接口**，键名
                // planeCloud*，与已删的 hc* 无继承关系。缺键一律回落出厂值
                // （enabled 缺键 = true，与 record 初值一致）。
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
                // phi_fwd + 散射级联（2026-10-02）。缺键回落出厂默认（同 record
                // 字段初值），与 highCloudSettings 的缺键策略一致。
                cloudHpSettings = CloudHpSettings.sanitized(data.cloudHp);
                // 2026-10-26 色彩分级 24 项：缺键/长度不符 ⇒ 逐项回落中性值。
                colorGradeSettings = ColorGradeSettings.sanitized(data.colorGrade);
                cloudMsSettings = new CloudMsSettings(
                        validCloudSetting(data.cloudPhiIntensity, 1.5f, 0, 6, 0.05f),
                        validCloudSetting(data.cloudPhiDepthPow, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudPhiDepthBias, 0.0f, -0.5f, 0.5f, 0.05f),
                        // 2026-10-16 ③ 修正：缺键回落 0.35 → 0.5（= 参考 _HP_MS_Contribution）。
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
                // 2026-10-16 哨兵修正：这三项的**滑杆下限**已从 -1 收到字面值区
                //（见 RTXSettingsScreen），这里把加载下界同步收紧，让越界的旧配置
                // 直接回落到默认，而不是留下一个「滑杆显示 0、实际生效宏值」的幽灵值。
                // ⚠ cloudBottomSmoothHeight 的下界 -1 → 0 就是**配置迁移**：
                // 旧档里的负值（= 回落编译期 0.22，但滑杆显示负值）现在被判越界 ⇒
                // 回落到 0.22 ⇒ 画面逐位不变、滑杆与实际值一致。
                // 另两项（pow / 阈值）的 0 是退化点（pow(x,0)≡1 / 阈值 0 等于不筛），
                // 所以它们的字面值区从滑杆第一个步进 0.05 开始。
                cloudShapeSettings = new CloudShapeSettings(
                        validCloudSetting(data.cloudBottomSmoothHeight, 0.22f, 0, 1, 0.02f),
                        validCloudSetting(data.cloudBottomSmoothPow, 1.0f, 0.05f, 8, 0.05f),
                        validCloudSetting(data.cloudTwoTierThreshold, 0.004f, 0, 0.5f, 0.001f));
                cloudMoonSettings = new CloudMoonSettings(
                        validCloudSetting(data.cloudMoonStrength, 1.0f, -1, 4, 0.05f),
                        validCloudSetting(data.cloudMoonPhase, 1.0f, -1, 1, 0.05f));
                // 2026-10-16 哨兵修正：同 cloudShapeSettings —— 这三项在着色器里是
                // `> 0.0 ? lane : 编译期宏`，0 是退化点（频率倍率 0 / 对比 0 / 曲线 0
                // 都会把细节层压成常数），所以字面值区从 0.05 起，加载下界同步收紧
                //（此前 -1..0 在滑杆上是死行程，且会留下「显示 0.05、实际宏值」的幽灵值）。
                cloudShape2Settings = new CloudShape2Settings(
                        validCloudSetting(data.cloudDetailScale, 1.0f, 0.05f, 8, 0.05f),
                        validCloudSetting(data.cloudDetailContrast, 1.0f, 0.05f, 8, 0.05f),
                        validCloudSetting(data.cloudProfileCurve, 1.0f, 0.05f, 8, 0.05f));
                // 2026-10-02 参考重构 #2~#7：缺键一律回落各组 record 的出厂值。
                cloudAmbientSettings = new CloudAmbientSettings(
                        validCloudSetting(data.cloudAmbientTopMul, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.cloudAmbientBottomMul, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.cloudAmbientSunSide, 0.35f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudAmbientHueFollow, 1.0f, 0, 1, 0.05f));
                // 2026-10-14 日落天光修正（出厂 0.65 / 0.50；缺键回落同值）。
                cloudSkyHueSettings = new CloudSkyHueSettings(
                        validCloudSetting(data.cloudSkyHueCool, 0.65f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSkyHueEnergy, 0.50f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSkyDirStrength, 0.0f, 0, 2, 0.05f),
                        validCloudSetting(data.cloudAmbientUnify, 0.0f, 0, 1, 0.05f));
                // 2026-10-15 云地面反射与视觉回退项；旧版未部署的 face/ambient 旋钮不再读取。
                cloudPhysicsSettings = new CloudPhysicsSettings(
                        validCloudSetting(data.cloudGroundAlbedo, 0.20f, 0, 0.8f, 0.05f),
                        validCloudSetting(data.cloudGroundBounce, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSunlightResidual, 0.0f, 0, 0.25f, 0.01f),
                        validCloudSetting(data.cloudPhaseFloor, 0.0f, 0, 1, 0.05f));
                // 2026-10-16 ③ 修正 + 哨兵修正：attenuation / eccentricity 的缺键回落
                // 0.0 → 0.5。0.0 原本是「回落编译期宏」的哨兵，但着色器已改成
                // `>= 0.0`（0 = 字面 0），所以缺键必须给真正的目标值；否则新装用户
                // 会拿到 att=0 ⇒ 三个 octave 全无衰减（MS 过曝）与 ecc=0 ⇒ 全各向同性。
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
                // 2026-10-23 体积雾扩展四项（出厂 1.0 = 全开；0 = 逐位旧档）。
                fogExtrasSettings = new FogExtrasSettings(
                        validCloudSetting(data.fogDynamicLightStrength, 1.0f, 0, 4, 0.05f),
                        validCloudSetting(data.fogGroundAnchorStrength, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.fogSkyAmbientStrength, 1.0f, 0, 1, 0.05f),
                        validCloudSetting(data.fogTemporalJitterStrength, 1.0f, 0, 1, 0.05f));
                // 2026-10-23 雨天地面雾浪（出厂 开 / 1.2 / 180 格 / 0.55 格每秒）。
                fogWispSettings = new FogWispSettings(
                        data.fogWispEnabled == null || data.fogWispEnabled,
                        validCloudSetting(data.fogWispStrength, 1.2f, 0, 4, 0.05f),
                        validCloudSetting(data.fogWispWavelength, 180.0f, 8, 1024, 1.0f),
                        validCloudSetting(data.fogWispSpeed, 0.55f, 0, 8, 0.05f));
                cloudTemporalSettings = new CloudTemporalSettings(
                        validCloudSetting(data.cloudSkyVisibilityStrength, 0.0f, 0, 1, 0.05f),
                        validCloudSetting(data.cloudSkyVisibilitySteps, 0.0f, 0, 32, 1.0f),
                        validCloudSetting(data.cloudTemporalDampStrength, 0.0f, 0, 1, 0.05f),
                        // 2026-10-16 云底遮蔽：缺键 / 越界一律 0 = 关（旧配置逐位不变）。
                        validCloudSetting(data.cloudBottomOcclusion, 0.0f, 0, 1, 0.05f));
                cloudAerialSettings = new CloudAerialSettings(
                        validCloudSetting(data.cloudAerialStrength, 0.0f, 0, 2, 0.05f),
                        validCloudSetting(data.groundAerialStrength, 0.0f, 0, 2, 0.05f),
                        validCloudSetting(data.groundAerialOcclusion, 0.0f, 0, 1, 0.05f));
                // 2026-10-19：这两项现在是步进覆盖（0 = 跟随质量档）
                cloudSunReddening = validCloudSetting(data.cloudSunReddening, 0.0f, 0, 160, 1f);
                cloudTopStretch = validCloudSetting(data.cloudTopStretch, 0.0f, 0, 32, 1f);
                // 范围 0.1~12：×8 后 = cfg.detailScale 0.8~96（与预览器滑条一致）
                cloudDetileStrength = validCloudSetting(data.cloudDetileStrength, 10.0f, 0.1f, 12f, 0.1f);
                // 一次性迁移：旧版该字段是「云形抗重复域扭曲」（0~2，出厂 1.0），
                // 落在新语义里等于 detailScale 8 ⇒ 云缘光滑。凡读到 ≤2 的旧值一律提升到 10。
                if(cloudDetileStrength <= 2.0f) {
                    cloudDetileStrength = 10.0f;
                }
                cloudType = validCloudSetting(data.cloudType, 0.8f, 0f, 1f, 0.01f);
                // 一次性迁移：0.35 是本轮引入的错误出厂值（层积云），见到就纠正
                if(Math.abs(cloudType - 0.35f) < 0.005f) {
                    cloudType = 0.8f;
                }
                cloudErosionScale = validCloudSetting(data.cloudErosionScale, 1.0f, 0f, 2f, 0.05f);
                // 2026-10-19：远处淡出起点（0~0.99 的云距比例）
                cloudWeatherScale = validCloudSetting(data.cloudWeatherScale, 0.75f, 0.0f, 0.99f, 0.01f);
                cloudSilver = validCloudSetting(data.cloudSilver, 0.08f, 0.0f, 1.0f, 0.01f);
                cloudViewDistance = validCloudSetting(data.cloudViewDistance, 120000f, 2000f, 200000f, 1000f);
                cloudMarchSettings = new CloudMarchSettings(
                        // 自阴影锥形覆盖距离：上限 6000 → 12000（配套厚度上限的提高）。
                        // 厚云柱在低太阳下的光程 ≈ 厚度/|sin(仰角)|，6000 的锥会在云柱
                        // 中段就截断 ⇒ 云顶被照亮、云柱中下部却没有自阴影。成本几乎为零：
                        // 光步是**几何级数**（步数固定），只是近端首步变宽。
                        validCloudSetting(data.cloudAdaptiveLightRange, 2000.0f, 0, 12000, 100.0f),
                        data.cloudTwoTierStep == null || data.cloudTwoTierStep,
                        data.cloudMeanDepthReproject == null || data.cloudMeanDepthReproject,
                        validCloudSetting(data.cloudFarFadeDistance, 80000.0f, 0, 420000, 1000.0f));
                waterParamsSettings = new WaterParamsSettings(
                        validCloudSetting(data.waterCrestBacklit, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.waterCausticDispersion, 0.4f, 0, 1, 0.01f),
                        validCloudSetting(data.waterCausticDepthDiffusion, 0.5f, 0, 1, 0.01f),
                        data.waterPhysicalPhase != null && data.waterPhysicalPhase);
                // 2026-10-02 水面波面参考重构：缺键一律回落各组 record 的出厂值。
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
                // 2026-10-02 波形本体：缺键一律回落出厂值。
                waterWaveShapeSettings = new WaterWaveShapeSettings(
                        validCloudSetting(data.waterWaveLengthScale, 1.0f, 0.35f, 4.0f, 0.05f),
                        validCloudSetting(data.waterWaveDirection, 0.0f, -3600.0f, 3600.0f, 5.0f),
                        validCloudSetting(data.waterWaveCrestSharpness, 1.0f, 0, 2, 0.05f),
                        validCloudSetting(data.waterWaveWarpStrength, 1.0f, 0, 2, 0.05f));
                // 2026-10-15 ROUTE B 波面后端：缺键/越界一律回落出厂值（3.5 / 12）。
                // 2026-10-20：域扭曲 / 波长带宽 两键随 GUI 入口一起删除（见
                // WaterWaveAflSettings 的注释）；剩余两键的**语义**已换（λmax / count），
                // 范围随之收窄：ROUTE A 时代的旧档值（Scale 1.0）在新语义下会给出
                // 亚像素噪声或方向条纹，因此被范围校验挡下、回落到出厂值 —— 无需清 JSON。
                waterWaveAflSettings = new WaterWaveAflSettings(
                        validCloudSetting(data.waterWaveAflScale, 3.5f, 2, 8, 0.1f),
                        validCloudSetting(data.waterWaveAflLayers, 12.0f, 1, 12, 1.0f));
                // 2026-10-03 FFT 频谱海面：缺键一律回落出厂值（enabled 缺键 = 开）。
                waterFftSettings = new WaterFftSettings(
                        data.waterFftEnabled == null || data.waterFftEnabled,
                        validCloudSetting(data.waterFftAmplitude, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.waterFftChoppiness, 1.0f, 0, 3, 0.05f),
                        validCloudSetting(data.waterFftTilePeriod, 10.0f, 2, 128, 1.0f),
                        validCloudSetting(data.waterFftDetail, 1.0f, 0, 3, 0.05f));
                // 2026-10-03 后处理：缺键一律回落出厂值。
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
                // 2026-10-03 一次性回退：交互波系统 / 波面退回旧档等价（用户要求）。
                // 只重写上一版写进去的 4 个出厂值，不动 waterStyleSwell/Mid/Fine
                //（用户的观感选择）与主波向（用户自己调过）。
                if(data.waterWaveVersion == null || data.waterWaveVersion < WATER_WAVE_VERSION)
                {
                    waterWaveSettings = new WaterWaveSettings(
                            0.0f, 1.0f, 0.0f, 4.0f);
                    waterSimWaveSettings = new WaterSimWaveSettings(0.0f, 0.0f, 1.0f, 1.0f);
                    save();
                }
                // 2026-10-03 薄层 SSS：缺键回落参考口径。
                waterSssSettings = new WaterSssSettings(
                        validCloudSetting(data.waterSssNonlinear, 0.5f, 0, 1, 0.05f),
                        validCloudSetting(data.waterSssBoost, 2.0f, 0, 4, 0.05f),
                        validCloudSetting(data.waterSssPhaseG, 0.8f, 0, 0.95f, 0.01f),
                        validCloudSetting(data.waterSssStrength, 1.0f, 0, 2, 0.05f));
                cloudSettings = new CloudSettings(
                        data.cloudEnabled == null || data.cloudEnabled,
 // 2026-10-18 范围校正：与 GUI 滑条一致。v3按层厚归一化，
        // 16 km 的层会把噪声拉平成一整片，上限收到 2000（参考整层 206 格）。
        validCloudSetting(data.cloudBottom, DEFAULT_CLOUD_SETTINGS.bottom(), 96.0f, 4000.0f, 16.0f),
                        // 上限与 CloudSettings 记录、GUI 滑块、JNI setCloudSettings 三处
                        // 必须一致（2026-10-02：6000 → 16384），否则会出现"滑块能拉到
                        // 16000、但确认时被静默判非法"的死角。
                        // 2026-10-19：厚度上限 2000 → 5000（CloudSettings 的硬上限是 16384）
                validCloudSetting(data.cloudThickness, DEFAULT_CLOUD_SETTINGS.thickness(), 128.0f, 5000.0f, 16.0f),
                        validCloudSetting(data.cloudCoverage, DEFAULT_CLOUD_SETTINGS.coverage(), 0.10f, 0.90f, 0.01f),
                        validCloudSetting(data.cloudDensity, DEFAULT_CLOUD_SETTINGS.density(), 0.25f, 5.0f, 0.05f),
                        // 2026-10-19：本项现在是**竖向拉伸**（0=扁片/1=高塔），范围 0~1
                validCloudSetting(data.cloudDetailStrength, DEFAULT_CLOUD_SETTINGS.detailStrength(), 0.0f, 1.0f, 0.01f),
                        validCloudSetting(data.cloudWindSpeed, DEFAULT_CLOUD_SETTINGS.windSpeed(), 0.0f, 32.0f, 0.5f),
                        validCloudSetting(data.cloudEvolution, DEFAULT_CLOUD_SETTINGS.evolution(), 0.0f, 2.0f, 0.05f),
                        validSetting(data.cloudQuality, DEFAULT_CLOUD_SETTINGS.quality(), 0, 2),
                        validCloudSetting(data.cloudSize, DEFAULT_CLOUD_SETTINGS.size(), 0.25f, 2.0f, 0.05f),
                        validCloudSetting(data.cloudDistribution, DEFAULT_CLOUD_SETTINGS.distribution(), 0.0f, 1.0f, 0.05f),
                        // 2026-10-18：本项现在是 2D 模型的「云层上下分布」
                // （fade 从线性到二次插值），范围 0~0.33
                validCloudSetting(data.cloudBaseFeather, DEFAULT_CLOUD_SETTINGS.baseFeather(), 0.0f, 0.33f, 0.01f),
                        validCloudSetting(data.cloudSunlightStrength, DEFAULT_CLOUD_SETTINGS.sunlightStrength(), 0.0f, 3.0f, 0.01f)
                );
            } else {
                // The previous cloud implementation exposed a different and
                // incompatible parameter set. Do not reinterpret those values.
                cloudSettings = DEFAULT_CLOUD_SETTINGS;
            }

            // 2026-10-19 第二次重做（B 方案）：字段语义
            // 与参考的 VFOG_* 对齐，**与 10-14 那版不兼容**（density 从"每格消光"
            // 变成 VFOG_DENSITY_BASE，height/scaleHeight 从"基准高度/标高"变成
            // VFOG_HEIGHT / VFOG_HEIGHT_2 两个绝对高度）。
            //
            // 旧档判定（自创，仅用于一次性迁移）：旧档的 density 落在 0..1 的
            // "每格消光"标定（出厂 0.008），新档出厂是 1.0。读到 <= 0.5 就当作旧
            // 档、整体回落新默认；用户下次保存后写成新值，不会再触发。
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

            // Older configs have no god-ray keys at all, so every field falls
            // back to the shipped default, which ships the pass switched off.
            // 重构后只读三个 key：旧配置里剩下的六个 godRay* 键会被静默忽略，
            // 并在下一次保存时自然消失。
            // 2026-10-03 追加 godRayIntensity（旧配置缺失时回落
            // 默认 1.0 = 标定档，与无此 key 的旧档逐位一致）。
            // 2026-10-06 设置清理：godRaySelfShadow 删除（出厂 0.08 时也几乎 no-op）。
            volumetricLightSettings = new VolumetricLightSettings(
                    data.godRayEnabled != null && data.godRayEnabled,
                    validSetting(data.godRaySteps,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.steps(), 4, 48),
                    validSetting(data.godRayOcclusionSamples,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.occlusionSamples(), 1, 6),
                    validCloudSetting(data.godRayIntensity,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.intensity(), 0.0f, 4.0f, 0.05f),
                    // 时间重投影历史权重（2026-10-04 P1）。缺键回落出厂 0.85。
                    validCloudSetting(data.godRayReprojection,
                            DEFAULT_VOLUMETRIC_LIGHT_SETTINGS.reprojection(), 0.0f, 0.95f, 0.05f)
            );


            // Final display HDR
            hdrEnabled =
                    data.hdrEnabled;

            // Auto exposure.  Both keys are boxed, so a config written before
            // the feature existed falls back to the shipped defaults, which
            // ship the pass switched off: the shipped look is exposed by hand,
            // so a missing key must not silently re-tone the frame.
            autoExposureEnabled =
                    data.autoExposureEnabled != null && data.autoExposureEnabled;

            autoExposureCompensation =
                    validCloudSetting(data.autoExposureCompensation,
                            0.0f, -2.0f, 2.0f, 0.1f);

            fgFrameSync =
                    data.fgFrameSync == null || data.fgFrameSync;

            // 缺键 = 出厂 开（老配置也应享受到这条保护）。
            fgRefreshCap =
                    data.fgRefreshCap == null || data.fgRefreshCap;

            // 缺键 = 出厂 关（调试功能，默认不启用）。
            slDevPlugins =
                    data.slDevPlugins != null && data.slDevPlugins;

            serEnabled =
                    data.serEnabled == null || data.serEnabled;

            // A config written before the multiplier existed carries no key, and
            // an out-of-range value (the multiplier list is not contiguous, so a
            // hand edit can land on 5) falls back to the shipped 3x rather than
            // pinning frame generation at an unsupported ratio.
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

            // Absent key means a config written before the armour material
            // existed, and the shipped default is off.
            entityArmorMetal =
                    data.entityArmorMetal != null && data.entityArmorMetal;

            // 2026-10-03 远淡出跟随 RR 档位：缺键 = 出厂（开）。
            cloudFadeFollowsRrMode =
                    data.cloudFadeFollowsRrMode == null || data.cloudFadeFollowsRrMode;

            // 2026-10-06 远云剥离 RR：2026-10-08 起缺键 = 出厂（**开**，见字段默认）。
            cloudPostRrComposite =
                    data.cloudPostRrComposite == null || data.cloudPostRrComposite;

            // 2026-10-04 S2/S3：缺键 = 出厂（0 = 关 / 1.0 = 旧档）。两者都是
            // "不改即旧行为"，所以老配置读进来后画面逐位不变，直到玩家自己去调。
            cloudResFloorPercent =
                    data.cloudResFloorPercent == null
                            ? 0
                            : Math.max(0, Math.min(100, data.cloudResFloorPercent));

            cloudFarStepDensity =
                    (data.cloudFarStepDensity == null ||
                            !Float.isFinite(data.cloudFarStepDensity))
                            ? 0.45f
                            // 2026-10-19：逆光银边强度 0~1（旧档存的 1.0~4.0 会被夹到 1.0，
                // 略偏亮但不会出错；想恢复出厂请在面板里拖到 0.10）
                : Math.max(0.0f, Math.min(1.0f, data.cloudFarStepDensity));

            // 2026-10-07：缺键 = 出厂（giRoulette 开 = 1 / march 远距简化 32 格）。
            // 与上面两条不同，这里刻意"不"回落成旧行为 —— 两项都是实测出来的纯性能
            // 收益（−7.8ms / −6.0ms 整帧），老配置没写这两键时应直接吃到收益。
            giRoulette =
                    data.giRoulette == null
                            ? 1
                            : (data.giRoulette == 0 ? 0 : 1);

            // 2026-10-26 直接光模式 / 稀疏渲染：缺键 = 出厂 0/0（旧画面）。
            directLightMode =
                    data.directLightMode == null
                            ? 0
                            : (data.directLightMode == 0 ? 0 : 1);

            // 2026-10-28 间接光模式：缺键 = 出厂 0（GI），旧存档不会突然变成 SHaRC。
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

            // 2026-10-09：运动矢量调试视图。缺键 = 0 = 关（刻意不给"出厂开"，
            // 因为它是诊断工具而不是收益项，老配置不该莫名进调试图）。
            motionDebugView =
                    data.motionDebugView == null
                            ? 0
                            : Math.max(0, Math.min(2, data.motionDebugView));

            // 2026-10-09：运动矢量档位。缺键 = 1（与出厂一致）；超出 0..3 夹紧。
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
                            // 2026-10-06 每像素采样数（spp）：0.5 = 棋盘交错。
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
                            // A config written before `width` existed has no
                            // `rainWidth` key, so this falls back to the
                            // default rather than to 0.  The increment is 0.1
                            // now that the useful range is 1-3 mm; the old 0.5
                            // step could not express anything between the
                            // minimum and the drizzle the defaults target.
                            validCloudSetting(data.rainWidth,
                                    DEFAULT_RAIN_SETTINGS.width(), 1.0f, 40.0f, 0.1f),
                            // 2026-10-23 卡片数量倍率。缺键（老配置）⇒ 1.0 = 旧档。
                            validCloudSetting(data.rainDensityScale,
                                    DEFAULT_RAIN_SETTINGS.densityScale(), 0.1f, 2.0f, 0.05f)
                    );

            rainWallSettings =
                    new RainWallSettings(
                            // No key at all means a config predating the wall
                            // model, and the model ships on, so the fallback is
                            // true rather than the field's own default of false.
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

            // 2026-10-07 雪：缺键 = 出厂观感（1.0 倍率组 + 0.85 浓度 + 0.30 摆幅）。
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

            // 2026-10-08 末地银河：缺键 = 出厂观感（0.85 / 30° / -45° / 叠加强度 0.5）。
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
                    0.45f;   // 多次散射强度

            // 2026-10-07：缺键 = 出厂 giRoulette 开 / march 远距简化 32 格。
            // giRoulette 与"缺键 = 旧行为"的惯例相反是刻意的：它是纯性能收益
            //（unbiased，只改方差），实测 −7.8ms 整帧。
            giRoulette =
                    1;

            directLightMode =
                    0;

            sparseRatePercent =
                    0;

            sunMarchFarDistance =
                    32;

            // 2026-10-09：运动矢量调试图出厂值 = 关；总开关出厂 = 开。
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
        // Snapping to the increment can land *just outside* the range the
        // caller just checked: `Math.round(0.05f / 0.01f) * 0.01f` is
        // 0.049999997f, a hair under the 0.05f minimum, because 0.01f is not
        // exactly one hundredth.  A value the user parked on a slider's
        // endpoint therefore used to come back out of range and -- since the
        // record constructors reject their own boundaries -- threw, aborting
        // the whole load() and resetting every group in the config.  Clamp the
        // snapped value back into the range so an endpoint survives the round
        // trip; interior values are already exact and pass through untouched.
        return Math.min(maximum, Math.max(minimum, Math.round(value / increment) * increment));
    }


    // =====================================================================
    // 2026-10-23 写盘合并：一次「确认/应用」不再产生 80+ 次整文件重写
    //
    // 病灶：83 个 setXxx 里绝大多数都直接 save()，而 RTXSettingsScreen
    // applyPendingSettings() 一次会调用 81 个 setter（UE 云八项还各调一次）⇒ 一次
    // 点击 = 约 80 次 new Data() + Gson 全序列化 + Files.write 覆盖 + 一行 20+ 字段的
    // println，而且全在渲染线程上。表现是日志被 [RTX CONFIG] 刷屏、SSD 突发小写入。
    //
    // 改法：setter 只置脏（markDirty），由调用方在一批改动结束时 flush() 一次。
    // 全仓 setter 的调用点只有 RTXSettingsScreen（81 处），所以 flush 点也只有它的
    // applyPendingSettings() 末尾。**新增调用方必须同样在结束时 flush()**，
    // 否则改动只留在内存里：不会丢（同进程内仍生效），但重启后回退。
    // =====================================================================
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

            // 2026-10-22 UE 体积云八项（clone：别把内部数组直接交给序列化器）
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
 // 2026-10-09 平面云。旧高层平面云 20 分量的写盘
            // 已于 2026-10-02 随该层退役移除。
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
            // 2026-10-26 色彩分级 24 项（见 ColorGradeSettings）。
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
            // 2026-10-02 参考重构 #2~#7。
            data.cloudAmbientTopMul = cloudAmbientSettings.topMultiplier();
            data.cloudAmbientBottomMul = cloudAmbientSettings.bottomMultiplier();
            data.cloudAmbientSunSide = cloudAmbientSettings.sunSideStrength();
            data.cloudAmbientHueFollow = cloudAmbientSettings.hueFollow();
            // 2026-10-14 日落天光修正。
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
            // 2026-10-02 水面波面参考重构。
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
            // 2026-10-14 ROUTE A 波面后端（键名沿用 "Afl"，见 record 注释）。
            data.waterWaveAflScale = waterWaveAflSettings.wavelength();
            data.waterWaveAflLayers = waterWaveAflSettings.octaves();
            // 2026-10-03 FFT 频谱海面。
            data.waterFftEnabled = waterFftSettings.enabled();
            data.waterFftAmplitude = waterFftSettings.amplitude();
            data.waterFftChoppiness = waterFftSettings.choppiness();
            data.waterFftTilePeriod = waterFftSettings.tilePeriod();
            data.waterFftDetail = waterFftSettings.detail();
            // 2026-10-03 后处理（新「后处理」设置页）。
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
            // 2026-10-14：键名沿用（迁移说明见 Loader 里那一段），只新增三个新键。
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
            // 2026-10-03 薄层 SSS 四件套。
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

            // 写盘成功才清脏：IOException 时保持 true，下一次 flush() 会重试。
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

        // 2026-10-22 UE volumetric-cloud parameters (8 floats, see the index table
        // above). null = config predates this block => keep the factory values.
        float[] ueCloud =
                null;

        // 2026-10-19 foliage subsurface-scattering mode. null = config predates
        // the switch => legacy sun-only mode.
        Boolean trueSubsurfaceScattering =
                null;

        Float localLightMaxDistance =
                null;

        Float localLightIntensity =
                null;

        // 2026-10-07 动态光源（移动光源）强度。null = 旧配置文件没有该字段 ⇒ 用默认 1.0
        // （而不是"关掉"，见 load 里的说明）。
        Float dynamicLightIntensity =
                null;

        // 2026-10-07 泛光强度（后处理链第 9 个 lane）。null = 旧配置没有该字段
        // ⇒ 默认 0（关），升级上来不会有意外泛光。
        Float postBloomStrength =
                null;

        // 2026-10-23 调色调增益（后处理链第 10~12 个 lane）。
        // null = 旧配置没有该字段 ⇒ 默认 1（恒等）。
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
        // 2026-10-02 清理：高层平面云的 21 个 JSON 键（highCloudEnabled + hc*）
        // 已移除 —— 它们在配置里残留时会被忽略（Gson 只映射已知字段）。
 // 2026-10-09 平面云重新占用这段 lane，但键名**全新**
        // （planeCloud*），与已删的 hc* 无继承关系。全部 boxed：缺键必须回落成
        // 出厂值（开着）而不是 0/关。
        Boolean planeCloudEnabled;
        Float planeCloudHeightOffset;
        Float planeCloudScale;
        Float planeCloudSpeed;
        Float planeCloudDensity;
        Float planeCloudCoverage;
        Float planeCloudOctaves;
        Float planeCloudFadeDistance;
        Float planeCloudBrightness;
        // phi_fwd 多重散射漫射场 + MS 尾巴（2026-10-02）。同样全部 boxed：
        // 没有这些键的配置是这一项存在之前写的，null 必须回落成出厂值
        // （开着）而不是 0/关。
        Float cloudPhiIntensity;
        Float cloudPhiDepthPow;
        Float cloudPhiDepthBias;
        Float cloudMsOctaveStrength;
        // 参考重构（2026-10-02）phi_fwd 四件套（CameraUBO.cloudPhiParams，偏移
        // 1072 尾块）。同样全部 boxed：缺键回落出厂值，绝不能落成 0
        //（0 对建立速率/比率都意味着"关"）。
        Float cloudPhiBoundaryConfidence;
        Float cloudPhiMsBuildScale;
        Float cloudPhiCompress;
        Float cloudPhiConeRatio;
        // 参考重构（2026-10-02）受光逻辑三个尺度量（CameraUBO.cloudLightParams）。
        Float cloudSourceOdScale;
        Float cloudSourceCurvePow;
        Float cloudAoUpwardScale;
        // 2026-10-02：夕阳环境染色（0=关，曙暮窗口内环境色相换成太阳色）。
        Float cloudSunsetAmbientTint;
        // 参考重构（2026-10-02）云底软化（形状，密度场共享）+ 物理月光项。
        Float cloudBottomSmoothHeight;
        Float cloudBottomSmoothPow;
        Float cloudTwoTierThreshold;
        Float cloudMoonStrength;
        Float cloudMoonPhase;
        // 第四阶段（2026-10-02）细节/形状旋钮（CameraUBO.cloudShape2Params）。
        Float cloudDetailScale;
        Float cloudDetailContrast;
        Float cloudProfileCurve;
        // 2026-10-02 参考重构 #2~#7（CameraUBO 1152/1168/1184/1200/1216 尾块）。
        // 同样全部 boxed：缺键回落出厂值（绝不能落成 0 —— 0 对染色/倍率/云型都
        // 意味着"关"或"旧档"，与出厂默认不是同一档）。
        Float cloudAmbientTopMul;
        Float cloudAmbientBottomMul;
        Float cloudAmbientSunSide;
        Float cloudAmbientHueFollow;
        // 2026-10-14 日落天光修正（CameraUBO.cloudSkyHueParams，偏移 1472）。
        // boxed 缺键回落出厂值 0.65 / 0.50（不能落成 0 —— 0 是"关"）。
        Float cloudSkyHueCool;
        Float cloudSkyHueEnergy;
        // 2026-10-17（同属 cloudSkyHueParams 的 .z/.w）
        Float cloudSkyDirStrength;
        Float cloudAmbientUnify;
        // 2026-10-15 云地面反射与视觉回退参数（cloudPhysicsParams，偏移 1488）。
        Float cloudGroundAlbedo;
        Float cloudGroundBounce;
        Float cloudSunlightResidual;
        Float cloudPhaseFloor;
        Float cloudScatterTint;
        Float cloudMsOctaves;
        Float cloudMsAttenuation;
        Float cloudMsEccentricity;
        Float cloudSunPathReddening;
        // 2026-10-10 云光路大气消光（Chapman 斜程高度增益强度 [0,1]，出厂 1.0）。
        Float cloudAtmosphereExtinction;
        Float cloudPowderMode;
        // 2026-10-03 删除 cloudShadingPowderStrength（死设置：C++ 侧从未读取，
        // cloudLightParams2.w 实际由 cloudPowderStrength 喂入）。
        Float cloudProfileType;
        Float cloudProfileTypeSpread;
        Float cloudWispyStrength;
        Float cloudWispyEdgeWidth;
        Float cloudWispyReach;
        Float cloudWispyTopHeight;
        // 2026-10-23 体积雾扩展四项（全部出厂 1.0 = 开，0 = 逐位旧档）。
        Float fogDynamicLightStrength;
        Float fogGroundAnchorStrength;
        Float fogSkyAmbientStrength;
        Float fogTemporalJitterStrength;
        // 2026-10-23 雨天地面雾浪（缺键 = 出厂开；只在雨天生效）。
        Boolean fogWispEnabled;
        Float fogWispStrength;
        Float fogWispWavelength;
        Float fogWispSpeed;
        Float cloudSkyVisibilityStrength;
        Float cloudSkyVisibilitySteps;
        Float cloudTemporalDampStrength;
        // 2026-10-16 云底遮蔽强度（0 = 关 = 逐位旧档）。
        Float cloudBottomOcclusion;
        // 2026-10-10 借鉴（同样出厂 0 = 关）。
        Float cloudAerialStrength;
        Float groundAerialStrength;
        Float groundAerialOcclusion;
        // 批次1（2026-10-02）：火烧云总闸（旧常量 CLOUD_FIRE_RIM_STRENGTH，
        // 默认 2.2）与夕阳云体红化（0=关）。
        // 2026-10-02 清理：火烧云强度键已移除（旧的 cloudFireRimStrength 会被忽略）。
        Float cloudSunReddening;
        // 批次2（2026-10-02）：步进旋钮 + 云顶拉伸。同样 boxed 缺键回落默认。
        Float cloudAdaptiveLightRange;
        Boolean cloudTwoTierStep;
        Boolean cloudMeanDepthReproject;
        // 2026-10-02：远景衰减距离（格=米，0=关闭）。
        Float cloudFarFadeDistance;
        Float cloudTopStretch;
        // 批次4（2026-10-02）：云形抗重复域扭曲。boxed 缺键回落默认（开着）。
        Float cloudDetileStrength;
        // 2026-10-18：v4 外形旋钮（boxed 缺键回落默认）
        Float cloudType;
        Float cloudErosionScale;
        Float cloudWeatherScale;
        Float cloudSilver;
        Float cloudViewDistance;
        // 批次3（2026-10-02）：水体观感四件套。boxed 缺键回落默认。
        Float waterCrestBacklit;
        Float waterCausticDispersion;
        Float waterCausticDepthDiffusion;
        Boolean waterPhysicalPhase;
        // 2026-10-02 水面波面参考重构（HPWater）。同样全部 boxed：缺键回落出厂值
        //（绝不能落成 0 —— 0 对"标定/波面混合/障碍反射"都意味着旧档或关闭）。
        Float waterWaveCalibration;
        Float waterWaveStrength;
        Float waterWaveBlend;
        // 2026-10-20 「反射焦散强度」（0..8，出厂 4）。落在 waterWaveParams 的原保留
        // lane 上，但作为独立设置项持久化 —— 缺键回落出厂值，旧 JSON 不用清。
        Float waterWaveReflectCausticStrength;
        Float waterSimSpongeWidth;
        Float waterSimGaussianSource;
        Float waterSimDampingScale;
        Float waterSimPropagationSpeed;
        // 2026-10-02 波形本体四件套（波长 / 风向 / 陡度 / 扭曲）。
        Float waterWaveLengthScale;
        Float waterWaveDirection;
        Float waterWaveCrestSharpness;
        Float waterWaveWarpStrength;
        // 2026-10-14 ROUTE A 波面后端（键名沿用 "Afl"）。全部 boxed：缺键回落出厂值。
        // 2026-10-20：waterWaveAflDrag / waterWaveAflWaves 两键随死设置入口删除
        //（旧 JSON 里的这两个键读出后被忽略，保存时不再写出）。
        Float waterWaveAflScale;    // ROUTE B：最长波长 λmax（格，2..8）
        Float waterWaveAflLayers;   // ROUTE B：波数 count（1..12，成本旋钮）
        // 2026-10-03 FFT 频谱海面。全部 boxed：缺键回落出厂值。
        // 2026-10-17：waterFftWindSpeed / waterFftWindDirectionDeg / waterFftSwellGain
        // 键已废弃（旧档读出后忽略，保存时不再写出）。
        Boolean waterFftEnabled;
        Float waterFftAmplitude;
        Float waterFftChoppiness;
        Float waterFftTilePeriod;
        // 2026-10-19 波面细分细节层倍率（0 = 关；缺键回落 1.0）。
        Float waterFftDetail;
        // 2026-10-03 后处理（新「后处理」设置页）。全部 boxed：缺键回落出厂值。
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
        // 2026-10-25 v5（HP）28 项全参数，见 CloudHpSettings。
        float[] cloudHp;
        // 2026-10-26 色彩分级 24 项，见 ColorGradeSettings。
        float[] colorGrade;
        Float cloudDensity;
        Float cloudDetailStrength;
        Float cloudWindSpeed;
        Float cloudEvolution;
        Integer cloudQuality;
        Float cloudSize;
        Float cloudDistribution;
        Float cloudBaseFeather;
        // 2026-10-16 移除 cloudTopBrightness / cloudBottomBrightness（云顶/底亮度手动倍率）
        // —— 残留键在旧配置里会被忽略（Gson 只映射已知字段）；云顶/底明暗改由着色器里的
        // upwardAO 遮蔽与 (1-height) 权重物理决定。
        Float cloudSunlightStrength;

        // Volumetric fog.  All boxed: a config written before the port has none
        // of these keys, and null must mean "use the new default" rather than
        // "fog is off".
        Boolean fogEnabled;
        Float fogAmount;
        Float fogCenterHeight;
        Float fogThickness;
        // 2026-10-14 新增、B 方案沿用的三个键（其余键名沿用，见 Loader 的迁移说明）。
        // 注：旧字段（fogScale / fogSpeed / fogDetailLayers / fogDawnBoost /
        // fogGroundMist* / fogRayleigh* / fogRainMediumMix）的声明保留但已不再读写；
        // Gson 默认不写 null ⇒ 它们会从新保存的文件里自然消失。
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
        // 雾太阳项自阴影 march（2026-10-02）。boxed：没有这些键的配置是重构前
        // 2026-10-06 设置清理：fogSelfShadowStepLength / fogSelfShadowSteps /
        // fogBlobs 三个键删除 —— 旧 JSON 里的残留读取时被静默忽略、下次保存消失。

        // 贴地薄雾（2026-09-30 旧档，已退役并停止读写）。声明保留只为兼容旧 JSON；
        // 当前 B 方案不读这些键，Gson 也不会再写出它们。
        Boolean fogGroundMistEnabled;
        Float fogGroundMistHeight;
        Float fogGroundMistExtinction;
        Float fogGroundMistDistance;

        // Rayleigh 薄空气通道（2026-10-03 旧档，已退役并停止读写）。声明保留只为
        // 兼容旧 JSON；当前 B 方案不读这些键，Gson 也不会再写出它们。
        Float fogRayleighStrength;
        Float fogRayleighScaleHeight;
        Float fogRainRayleighBoost;
        Float fogRainMediumMix;

        // Volumetric light (god rays).  Boxed for the same reason as the fog
        // keys: a config written before this pass existed has none of them, and
        // null has to mean "use the shipped default", not "off".
        // 2026-09-25 重构删掉了 godRayIntensity / Anisotropy / Scattering /
        // MaxDistance / HazeScaleHeight / RainBoost 六个键：观感改由着色器推导；
        // 2026-10-19 空气光柱退役后，这些旧键更只对水下分支有间接意义。
        // 旧 JSON 里残留的六个键读的时候被忽略、写的时候不再产出。
        // 2026-10-03 godRayIntensity 回归（新语义：乘在昼夜 strength 上的强度
        // 倍率，godRayParams[3]），与被删的旧同名键无关；旧 JSON 里 09-25 之前
        // 残留的同名键会被本档默认 1.0 覆盖语义（旧键的量纲不同，读进来也会被
        // 0..4 校验拦住回落默认）。
        // 2026-10-06 设置清理：godRaySelfShadow 键删除。
        Boolean godRayEnabled;
        Integer godRaySteps;
        Integer godRayOcclusionSamples;
        Float godRayIntensity;
        // 2026-10-04（P1）时间重投影历史权重。boxed：缺键回落出厂 0.85。
        Float godRayReprojection;


        boolean hdrEnabled =
                false;

        // Boxed for the same reason as every other toggle in this class: a
        // config written before auto exposure existed has no key, and null has
        // to mean "leave it on" rather than "off".
        Boolean autoExposureEnabled =
                null;

        Float autoExposureCompensation =
                null;

        Boolean fgFrameSync =
                null;

        // 2026-10-22（B2）：缺键 = 出厂 开。
        Boolean fgRefreshCap =
                null;

        // 2026-10-22（D3）：缺键 = 出厂 关。
        Boolean slDevPlugins =
                null;

        // Boxed for the same reason as the toggles above: a config written before
        // the armour material existed has no key, and null must mean off.
        Boolean entityArmorMetal =
                null;

        // 2026-10-03 远淡出跟随 RR 档位（缺键 = 出厂 开）。
        Boolean cloudFadeFollowsRrMode =
                null;

        // 2026-10-06 远云剥离 RR（缺键 = 出厂 开）。开 = 云以输出分辨率渲染并在
        // RR 之后合成；关 = 旧档（云进 RR 颜色输入，清晰度由档位决定）。
        Boolean cloudPostRrComposite =
                null;

        // 2026-10-04 S2/S3（缺键 = 出厂 0 / 1.0：关 / 旧档）。Boxed，与上面的
        // 开关同理：老配置没这两个键时必须区分"没写"与"写了 0"。
        Integer cloudResFloorPercent =
                null;

        Float cloudFarStepDensity =
                null;

        // 2026-10-07 Perf-L10 实测后的两项 RT 优化（缺键 = 出厂：轮盘开 / 远距简化 32 格）。
        Integer giRoulette =
                null;

        /** 2026-10-26 直接光模式（缺键 = 0 = 传统 RIS）。 */
        Integer directLightMode =
                null;

        /** 2026-10-28 间接光模式（缺键 = 0 = GI；1 = SHaRC）。 */
        Integer indirectMode =
                null;

        /** 2026-10-26 稀疏渲染采样率 %（缺键 = 0 = 关）。 */
        Integer sparseRatePercent =
                null;

        Integer sunMarchFarDistance =
                null;

        /** 2026-10-09 运动矢量调试视图（缺键 = 0 = 关）。 */
        Integer motionDebugView =
                null;

        /** 2026-10-09 运动矢量总开关（缺键 = 1 = 开）。 */
        Integer motionVectorsEnabled =
                null;

        Boolean serEnabled =
                null;

        // Boxed for the same reason as the toggles above: a config written
        // before the multiplier entry existed has no key, and null must fall
        // back to 2x rather than to zero.
        Integer fgMultiplier =
                null;

        // Boxed for the same reason: a config written before the cap existed has
        // no key, and null must mean the automatic pairing rather than 0 (which
        // would remove the cap) or -0 as a frame rate.
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
        // 2026-10-26 体积光 froxel + ReSTIR 总开关（0 = 关，1 = 开）。
        // 缺键回落 0：老配置打开本版时不会突然多出一层雾里的光。
        Integer volumetricRestir;
        // 2026-10-06 每像素采样数（spp）：0.5 / 1 / 2 / 3 / 4。boxed：缺键回落 1.0。
        Float rtSpp;

        // Boxed, and deliberately so: configs written before the rain knobs
        // existed have no such keys, and a null must fall back to the new
        // default rather than to zero (which would hide the rain entirely).
        Float rainOpacity;
        Float rainStreakLength;
        Float rainBrightness;
        Float rainWidth;
        // 2026-10-23 雨丝卡片数量倍率（缺键 = 1.0 = 出厂）。
        Float rainDensityScale;
        // 2026-10-07 雪。boxed 同理：老配置没有这些键时必须回落默认，而不是 0。
        Float snowSizeScale;
        Float snowDensityScale;
        Float snowOpacity;
        Float snowBrightness;
        Float snowFallSpeedScale;
        Float snowSway;
        // 2026-10-08 末地银河旋钮（CameraUBO.galaxyParams）。galaxyMix 是同年
        // 改成叠加时新加的键：老配置没有它 ⇒ 回落出厂 0.5，而不是 0（那会关银河）。
        Float galaxyScale;
        Float galaxyTiltDegrees;
        Float galaxyYawDegrees;
        Float galaxyMix;

        // Same boxed reason as the streak knobs above, plus one more for the
        // enable flag: a config written before the wall model existed has no
        // key at all, and `enabled` must come back as true, not false.
        Boolean rainWallEnabled;
        Float rainWallWetness;
        Float rainWallDripDensity;
        Float rainWallDripSpeed;
        // 2026-10-07：原 rainWallTrailLength（旧"水柱"档的拖尾长度）→ 雨滴大小。
        // 老配置里那个键自然失效，回落到出厂 1.0（= 观感不变），与 native 侧的
        // g_rainWallDripSize 一一对应。
        Float rainWallDripSize;
        Float rainWallDripBump;
        Float rainWallDistance;

        // Water style dials.  All boxed: a config written before the water
        // style block existed has none of these keys, and null must mean "use
        // the shipped look" rather than zero.
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
            /**
             * 2026-10-06：每像素采样数（spp）。离散档 0.5 / 1 / 2 / 3 / 4。
             * <ul>
             *   <li>{@code >= 1}：每像素每帧打 N 条主射线，平均掉随机次级项
             *       （GI / 反射 / 局部光）的方差；</li>
             *   <li>{@code 0.5}：棋盘交错 —— 每帧只渲染棋盘格的一半像素，另一半
             *       不写任何输出、保留上一帧的值（guide 因此陈旧一帧）。</li>
             * </ul>
             * 写 CameraUBO.rtSampleParams.x，由 rt_test.rgen 的 main() 消费。
             */
            float rtSpp,
            /**
             * 2026-10-26：体积光 froxel + ReSTIR 开关。
             * 打开后主 trace 之后的 recordVolumeRestir 会跑五个 compute 阶段
             * （initial/temporal/spatial/integrate/composite），并在水下让位给
             * 旧的光柱 pass。首次打开的那一帧会建资源与管线，可能有一帧卡顿。
             */
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
            /**
             * 2026-10-23 卡片数量倍率（1.0 = 出厂）。雨丝是**每根一张交叉双片**
             * 的 RT 几何，直接进粒子层 BLAS 并被主射线 any-hit 命中 —— 数量
             * 是这条链路上唯一能线性换性能的旋钮。0.25 = 只有 1/4 的卡片。
             */
            float densityScale
    ) {
        public RainSettings {
            // The lower bounds were relaxed when the drizzle defaults landed:
            // the lattice can address a sub-millimetre streak (the card is
            // ~13x the streak width, so 1.0 mm still spans a couple of screen
            // pixels at ten blocks) and a 0.4-block card still reads as a
            // streak rather than a dot.  Keep the *upper* bounds where they
            // are -- they are what stops a runaway config from turning every
            // drop into a full-screen bar.
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
            /** 云量 / 分布密度：只决定哪些区域有云（整朵出现或消失）。 */
            float coverage,
            float density,
            float detailStrength,
            float windSpeed,
            float evolution,
            int quality,
            /** 云的大小：base 噪声周期的倒数倍率（越大云越大），0.55 = 出厂。 */
            float size,
            float distribution,
            float baseFeather,
            // 2026-10-16：原 topBrightness / bottomBrightness 已移除 —— 云顶/底明暗改由
            // 着色器里的 upwardAO 遮蔽与 (1-height) 权重物理决定，不再手动倍率。
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
                    // 2026-10-02：厚度上限 6000 → 16384（默认 3936 的 4.2 倍）。旧上限只比
                    // 默认高 1.5 倍 ⇒ 滑块几乎没有余量。16384 的取舍：厚云柱的垂直发展
                    //（塔状/Cb 感）足量，而自阴影锥（见 CloudMarchSettings.adaptiveLightRange
                    // 的新上限 12000）仍能覆盖大部分云柱；再往上是纯烧帧（同一像素里被云
                    // 占用的样本数随厚度线性上升）。
                    thickness < 256.0f || thickness > 16384.0f ||
                    coverage < 0.10f || coverage > 0.90f ||
                    density < 0.25f || density > 5.0f ||
                    detailStrength < 0.0f || detailStrength > 0.50f ||
                    windSpeed < 0.0f || windSpeed > 32.0f ||
                    evolution < 0.0f || evolution > 2.0f ||
                    quality < 0 || quality > 2 ||
                    // 2026-10-18：滑条是 0.25~2.0，校验下限必须跟滑条一致，
                    // 否则调到 0.25 会抛 "Invalid cloud settings" 让游戏直接退出。
                    size < 0.25f || size > 3.0f ||
                    distribution < 0.0f || distribution > 1.0f ||
                    // 2026-10-18：本项现在是 2D 模型的「云层上下分布」（0~0.33）。
                    // 旧范围 0.03~0.30 会把新的默认值 0.33 判为非法 ⇒ 构造抛异常 ⇒ 游戏起不来。
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
