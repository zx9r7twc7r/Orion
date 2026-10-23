// ===========================================================================
// sky_model_common.glsl —— 太阳方向 / 暮色盘 / 阳光色的唯一共享实现
//
// 2026-09-30 P2 重构：此前 sunDirection / 暮色盘 / sunColor 在五个文件里
// 各持逐字副本（sky_atmosphere / volumetric_clouds / volumetric_light /
// rt_test.rmiss / rt_test.rgen），改一次色相要人肉同步五份，已两次因漏同步
// 产生接缝。现在统一到这里；各消费点保留原函数名薄包装，调用点零改动。
//
// 提供（依赖：include 之前必须已声明 cam；函数自带 2π/纬度字面量，
// 无 PI / SOLAR_LATITUDE 依赖，rgen 那种"PI 在后段才声明"的文件也能用）：
//   vec3 sunDirection() —— 太阳轨道方向（单位向量）
//   TWILIGHT_DAWN/DUSK/PREDAWN_TINT + twilightEveningWeight /
//       twilightPredawnWeight / twilightPalette —— 暮色盘
//   vec3 skyModelSunColor(vec3 lightDirection, float goldenStrength)
//       —— 阳光色核心：物理透射基色 → 暮色混入(0.78) → 日照金山（强度参数：
//          天空/光柱/水面反射 = 1.0 全档；云 = 0.5 半档 —— 全档曾与
//          fireTint 双叠全橘，09-26 定档半档）→ 雨(×0.35)/雷暴(冷移)调制
//
// 刻意不合并：rgen 的 getTimeDrivenSkyLightColor（地形光照色，含地平线下
// 拂晓分支与自己的能量标定）是另一个函数；rmiss 的
// getTimeDrivenSunDirection（不归一化变体，调用方契约不同）同理。
// ===========================================================================
#ifndef SKY_MODEL_COMMON_GLSL
#define SKY_MODEL_COMMON_GLSL

#include "sky_sun_common.glsl"

vec3 sunDirection()
{
    const float TWO_PI = 6.28318530718;
    float angle = fract(cam.historyParams.w) * TWO_PI;
    return normalize(vec3(cos(angle), 0.81915204429 * sin(angle),
        0.57357643635 * sin(angle)));
}

// ===========================================================================
// 2026-10-05 末地天空（月球视角）：地球遮挡 + 地照
//
// 只依赖传入的太阳方向，因此 rgen / rmiss 各自那份直接日轨副本都能用同一组
// 公式。非末地维度**恒返回 1.0 / 0.0**，于是主世界与下界逐字节不变。
// ===========================================================================

// 天极（与 rt_test.rmiss 的星空自转同一条轴：P = (0, sin35°, -cos35°)）。
const vec3 END_SKY_POLE = vec3(0.0, 0.57357643635, -0.81915204429);
// **2:1 自旋-轨道共振**（2026-10-05 用户选定"要看得见公转"）：
//   天空（恒星、银河、地球）按 2 圈/周期扫过，太阳按 1 圈/周期。
//   视运动 1 = 自转 2 − 公转 1 —— 这正是"太阳在固定星空里逆向漂移"的真实成因，
//   于是公转肉眼可见。地球每 4.9 小时过中天一次：**正午那次被太阳照亮的是背面**
//   （新月 + 全食），**午夜那次太阳在我们背后**（满地球）。
//   设 1.0 就退回潮汐锁定（地球钉死在天上，与真实月球一致，但看不出公转）。
const float END_SKY_SPIN_RATE = 2.0;
// 地球方向的相位偏移：把"太阳与地球重合（全食）"钉在相位 0.25 = 当地正午，
// 也就是日食发生在头顶，而不是卡在地平线上半个地球被地形挡住。
const float END_EARTH_PHASE_OFFSET = 0.75;
// 地球轴相对黄道面法线的倾角（23.4°）。
const float END_EARTH_TILT = 0.40840704497;
// 地球视半径（弧度）。**这一个数同时是"地球多大""多清楚""全食多长"的唯一旋钮**：
//   0.01658 (0.95° 半径 / 1.90° 直径)  真实月球视角（太阳的 3.6 倍大）
//   0.3491  (20° / 40°)               第一版影院化
//   1.0472  (60° / 120°)              用户"再大三倍"那版 —— 比 70° FOV 还大，
//                                     盘心 2.5 像素/纹素，糊到被投诉
//   0.4363  (**25° 半径 / 50° 直径**)  2026-10-05 定档：占屏宽约 72%，盘心
//                                     1.05 像素/纹素（锐），全食 82 分钟
// 换算（1920 宽 / 70° FOV、昼面 4096×2048）：盘心 ≈ R × 2.41 像素/纹素；
// 全食时长 ≈ 2R(度) / 0.61 分钟。**尺寸与清晰度就是同一根杠杆的两端。**
const float END_EARTH_ANGULAR_RADIUS = 0.4363;

// 黄道（太阳日轨所在的平面）上的单位方向，t 以"圈"为单位。
vec3 endEclipticDirection(float t)
{
    const float TWO_PI = 6.28318530718;
    float a = fract(t) * TWO_PI;
    return normalize(vec3(cos(a), 0.81915204429 * sin(a),
        0.57357643635 * sin(a)));
}

// 地球方向（现在**会动**：2 圈/周期，沿黄道）。
vec3 endEarthDirection()
{
    return endEclipticDirection(
        cam.historyParams.w * END_SKY_SPIN_RATE + END_EARTH_PHASE_OFFSET);
}

// 地球自转轴。两个要求：
//   1) 与地球方向**刚性**同步（都随星空前进），并且**始终垂直**于地球方向
//      ⇒ 视面中心永远落在赤道附近。原实现用常量轴 (0.3978,0.9175,0) 几乎就是
//      世界竖直方向，与地球方向夹角只有 41° ⇒ 视面中心在 48.7°S、最北只看到
//      41°N，玩家永远只能看到"南极 + 南大洋"，认不出是地球。
//   2) 保留 23.4° 轴倾角，看起来像真实行星。
// 构造：黄道上超前 90° 的方向，再朝天极倾斜 23.4°（于是 ⊥ 地球方向，恒成立）。
vec3 endEarthAxis()
{
    float t = cam.historyParams.w * END_SKY_SPIN_RATE +
        END_EARTH_PHASE_OFFSET + 0.25;
    return normalize(cos(END_EARTH_TILT) * endEclipticDirection(t) +
        sin(END_EARTH_TILT) * END_SKY_POLE);
}

// ===========================================================================
// 2026-10-07 末地光源球（替换地球 + 太阳盘）：
//   · 观感 = 奶油金星云球体（域扭曲 fbm 内部流纹 + 星尘点 + 盘缘亮边）
//     + 一圈带亮珠的倾斜金环（前半段压在球前、后半段被球挡住）
//     + 紧贴球体的小范围暖光晕。第一版"白盘 + 整屏高斯晕"被否
//     （2026-10-07 实测截图：整片天被铺成米色，球体无结构）。
//   · 轨道：固定高度角 60°（方向与末地水面恒成 60°），方位角 1 圈/周期
//     公转（与原太阳速率一致 ⇒ 相对 2× 星空仍可见漂移）。
//     末地从此恒为"白天"：原月食 / 地照 / 昼夜翻转随地球一并退役。
//   · 消费方：rgen / rchit×2 / rmiss / water_caustics 的直射方向副本在
//     weatherParams.z==3 时改走同一根向量（各文件内联字面量，见各自注释）。
//   · 已知取舍：sun_shadow.glsl 的高度图（farSlopeOcclusion 用）仍沿旧
//     55° 轨道建造 —— 末地远场坡度遮蔽方向会略偏，属次要环境项，待实测。
//   · 回退：END_ORB_ENABLE 置 0 ⇒ 逐位回到"地球 + 太阳"旧档。
// ===========================================================================
#ifndef END_ORB_ENABLE
#define END_ORB_ENABLE 1
#endif

#if END_ORB_ENABLE
// 视半径（弧度）：2026-10-18 用户要求再放大 1.5× —— 0.3054 × 1.5 = 0.4581 rad
//（26.25° 半径 / 52.5° 直径），70° FOV 下直径约占屏高 3/4。
const float END_ORB_ANGULAR_RADIUS = 0.4581;
// ---- 2026-10-07 v3：按用户提供的 Shadertoy "AI Slop v15"（Buffer A 玻璃球）
// 逐项移植。名字后缀对应原宏。v1（σ0.35/0.7 铺满天）与 v2（星云近似）均被否。
// B/C/D 辉光链的 σ 换算：1080p / 70° FOV 下 stride≈2.4px ⇒ 紧晕 σ5×stride
// ≈12px≈0.014rad，宽晕 σ9×3.5×stride≈76px≈0.087rad；强度用原值 0.9/1.5。
const vec3  END_ORB_GLOW_COLOR = vec3(1.0, 0.62, 0.2);
const float END_ORB_HALO_STRENGTH = 0.9;
const float END_ORB_AURA_STRENGTH = 1.5;
const float END_ORB_HALO_SIGMA = 0.014;
const float END_ORB_AURA_SIGMA = 0.087;
const float END_ORB_IOR = 1.46;          // 玻璃折射率
const float END_ORB_MILK = 0.008;        // 玻璃体的乳白
const float END_ORB_PLASMA = 3.5;        // 等离子体亮度（会顶 2.5 钳制）
const float END_ORB_SMOKE = 0.0;         // 烟密度（原值 0 = 纯光）
const float END_ORB_GLYPH_GLOW = 6.0;    // 符文发光（同样被钳到 2.5）
const float END_ORB_RUNES = 18.0;        // 沿箍带的符文数
const float END_ORB_BAND_R = 1.035;      // 箍带中面半径（球半径=1）
const float END_ORB_BAND_H = 0.17;       // 箍带半宽
// 箍带姿态 = v15 bandSpace 的两档固定旋转（xy×0.85 + yz×0.25）+ 慢旋，
// 由 rmiss 的 orbSdBand 内联，不再用单轴 TILT 常量。
const int   END_ORB_VOLUME_STEPS = 32;   // 弦上体积步数（原 64，慢 GPU 建议 36）
// 时钟倍率：等离子体漂移用日轨相位（一个 MC 天转 END_ORB_ANIM_RATE 圈）；
// 脉动/符文闪烁/星星眨眼直接用 cam.frameCount。
const float END_ORB_ANIM_RATE = 24.0;

// 光源球方向：2026-10-18 用户定档"末地正中央上空，只自转不公转" ⇒ 固定天顶。
// **六个方向副本（rgen/rmiss/rchit×2/water_caustics + 这里）必须同一向量**，
// 否则可见球与直射光方向分家。球内部的自转仍由 tDay/spinA 驱动（见 rmiss）。
vec3 endOrbDirection()
{
#if 0 // 2026-10-18 公转退役（保留原式；恢复时六个消费文件一起改回）。
    const float TWO_PI = 6.28318530718;
    // ⚠ 2026-10-07 公转提速：C++ 侧喂给末地的 historyParams.w 是"29.5 MC 天
    // 一圈"的慢相位（原为地球月相设计，20 分钟才走 12°，球看着不动）。
    // 这里 ×29.5 unwrap 回"每 MC 天一圈"（≈20 分钟/圈）。
    float a = fract(cam.historyParams.w * 29.5) * TWO_PI;
    return vec3(0.5 * cos(a), 0.86602540378, 0.5 * sin(a));
#else
    return vec3(0.0, 1.0, 0.0);
#endif
}
#endif
// 太阳视半径（弧度）：0.265° ⇒ 0.53° 视直径（月球视角，真实值，不要动）。
const float END_SUN_ANGULAR_RADIUS = 0.0046250;
const float END_SUN_DIAMETER_DEGREES = 0.53;
// 图集银河（rt_end_atlas.png 左上格）的总增益。2026-10-09：1.0 -> 1.25
//（用户："末地的银河太暗了"）。程序化银河那一路的增益在 end_sky_common.glsl 的
// END_GALAXY_STRENGTH；两者是叠加关系（图集做底 + 程序化银河按 GUI 权重叠加）。
const float END_SPACE_STRENGTH = 1.25;
const float END_STAR_STRENGTH = 0.15;
// ---- 2026-10-09 末地星空的环境照明（"让星空也进入 LUT 贡献"）----
// 用户要求星空不再只是可见天空的装饰。事实与极光那次一样：**天空 LUT 只有两个
// 消费者**（可见天空 + 低菲涅尔玻璃的解析天空反射）⇒ 光写进 LUT 照不亮世界。
// 所以星空既写进 LUT（sky_atmosphere.comp 的末地分支），又在这里给出一份**环境
// 底光**（rgen 的 getSurfaceAmbientRadiance()）：末地是真空、没有大气散射，这份
// 补光就是"满天星云给地面打的光"。2026-10-18 银河视觉退役后这层底光原样保留。
// **只对末地生效**（其余维度 lift = 0，逐位不变）。
// 量级参照：用户环境底光滑块（noDirectLightAmbient）默认只有 0.01，末地的直射光
// 来自光源球（见 rgen 的 END_ORB_SUN_LIGHT_SCALE）⇒ 0.10 是"能看清阴影里的银河
// 色、但不会把末地变成白天"的档位。想要更强往上调，0 = 银河回到纯装饰。
const vec3 END_GALAXY_LIGHT_COLOR = vec3(0.62, 0.72, 1.00);
const float END_GALAXY_AMBIENT_LIFT = 0.10;
// 地球这一层按**反射率**处理（2026-10-05 "明暗对比明显点"）：
// 图集是 sRGB 编码的 8bit，直接当辐亮度用会把暗端大幅抬亮 —— 夜景字节 0.02
// 经显示端 sRGB 编码变成 17% 灰，整颗球发灰、没有明暗对比。
// rmiss 先把昼/夜子图解成线性 albedo（pow 2.2）：昼面显示与源图一致，夜面
// 落到近纯黑，于是"亮面 / 暗面 + 亮城市灯"的对比就出来了。
const float END_EARTH_SRGB = 2.2;
// 城市灯是**自发光**，处理顺序是"先减掉夜景图的灰蓝底、再增益"：
// 那张图不是"黑底 + 灯"，它整片有一层很淡的大陆/海洋底（线性约 0.003~0.01，
// 占了大半面积），直接乘增益会把整颗夜面抬成亮蓝（实测过）。
// 减 0.006 把底压到近纯黑，再 ×8 把灯（线性 0.01~0.13）拉到 0.1~1.0。
const float END_NIGHT_BASE = 0.006;
const float END_NIGHT_LIGHTS_STRENGTH = 8.0;
// 夜景图里两极是**亮冰盖**（实测子图行均值：极区 32/255、中纬度 6~13）。
// 而视面中心在赤道 ⇒ 两极正好落在盘缘，夜面就会多出一圈亮弧。
// 夜里极区本来就没有城市灯，所以按纬度把 |lat| > 63° 以后逐渐压到 8%。
const float END_NIGHT_POLAR_FADE = 0.92;
// 昼面的"太阳增益"：空间里被正午照亮的行星要比地面更亮一点才像发光的天体
//（同时带一点点暖色）。1.0 = 显示观感与源图一致。
const vec3 END_EARTH_DAYLIGHT = vec3(1.35, 1.32, 1.27);
// 昼面靠晨昏线那一侧的朗伯衰减上限：太阳在当地高度角 0° 时 0、14.5° 以上全亮。
// 原实现整片昼面一个亮度，看着像贴纸；加了它才有球体的转折感。
const float END_DAY_RAMP_TOP = 0.25;
// 云的线性反照率（白云 ≈ 0.8，在解码后的线性空间里跟地球表面同量级）。
// 注意：云的**密度**仍取未解码的原始字节，阈值见下面 0.06/0.71。
const float END_CLOUD_ALBEDO = 0.80;
const float END_EARTHSHINE_STRENGTH = 0.03;
// 末地是真空：日盘/月盘外面**不能有日冕瓣**。主世界那圈 pow64（半宽到 ±8°）在
// 没有大气的天空里会把 0.53° 的小盘糊成一团亮斑（用户："太阳太大了"）。
// 想要一点点微光就把它调到 0.05~0.1。
const float END_SUN_GLOW_SCALE = 0.0;

// ---- 地球云层（2026-10-05 加入；图集右上象限，NASA cloud_combined 灰度图）----
// 云比地面转得快一点 ⇒ 相对漂移；设 1.0 就是与地面刚性同步，看不出流动。
// 1.15 时相对流动约 2.7°/分（盘心约 77 像素/分，一眼能看出"云在走"）。
// ⚠ 倍数 ≠ 1 意味着云与大陆的地理对应关系会随时间漂走（偏移量无界增长，云图
//   是全球场，所以肉眼看不出来，只是别指望"亚马逊上空的云一直对着亚马逊"）。
const float END_CLOUD_SPIN_MULTIPLIER = 1.15;
// 整体不透明度（0=无云，1=源图逐字）。
const float END_CLOUD_OPACITY = 0.90;
// 密度软阈值：源图是灰度"白云/黑底"，亮度分位 25% = 0.06、90% = 0.71，
// 于是中位亮度的像素约 18% 不透明、最亮的 10% 基本盖满。
const float END_CLOUD_THRESHOLD_LO = 0.06;
const float END_CLOUD_THRESHOLD_HI = 0.71;

// 1 = 太阳完全可见；0 = 被地球完全挡住（月食）。只认**真实**太阳方向：
// 夜里 rmiss/rgen 会把方向翻到"月亮"，那不是日食的几何。
// earthDir 必须由调用方传 endEarthDirection() —— 地球现在是动的，不能当常量。
float endEarthEclipseFactor(vec3 sunDir, vec3 earthDir)
{
    if(abs(cam.weatherParams.z - 3.0) >= 0.25)
    {
        return 1.0;
    }
    float angle = acos(clamp(dot(sunDir, earthDir), -1.0, 1.0));
    return smoothstep(END_EARTH_ANGULAR_RADIUS - END_SUN_ANGULAR_RADIUS,
                      END_EARTH_ANGULAR_RADIUS + END_SUN_ANGULAR_RADIUS, angle);
}

// 地球被照亮的比例：0 = 全食（地球只有暗面朝我们）、1 = 满地。
float endEarthPhase(vec3 sunDir, vec3 earthDir)
{
    return 0.5 * (1.0 - dot(sunDir, earthDir));
}

// 暮色盘：拂晓冷紫 / 黎明橘粉 / 黄昏橘红（2026-09-30 用户定档纯橘偏红）。
const vec3 TWILIGHT_DAWN_TINT = vec3(1.00, 0.48, 0.24);
const vec3 TWILIGHT_DUSK_TINT = vec3(1.00, 0.45, 0.08);
const vec3 TWILIGHT_PREDAWN_TINT = vec3(0.62, 0.50, 0.86);

float twilightEveningWeight()
{
    const float TWO_PI = 6.28318530718;
    return smoothstep(0.20, -0.20, cos(fract(cam.historyParams.w) * TWO_PI));
}

// 拂晓窗口：上升侧、太阳高度角 -0.30..-0.02。
float twilightPredawnWeight(float sunElevation)
{
    return (1.0 - twilightEveningWeight()) *
        smoothstep(-0.30, -0.10, sunElevation) *
        (1.0 - smoothstep(-0.10, -0.02, sunElevation));
}

// 曙暮光色相：黄昏橘红、黎明橘粉、拂晓冷紫。
vec3 twilightPalette(float sunElevation)
{
    return mix(
        mix(TWILIGHT_DAWN_TINT, TWILIGHT_DUSK_TINT, twilightEveningWeight()),
        TWILIGHT_PREDAWN_TINT,
        twilightPredawnWeight(sunElevation)
    );
}

// 阳光色核心。goldenStrength：日照金山混入强度（1.0 全档 / 0.5 云半档）。
// goldenStrength=1.0 时 mix(x,y,1.0) 逐位等于 y（0·x+y，IEEE 精确），与
// 旧直写 skySunGoldenTint(...) 语义一致。
vec3 skyModelSunColor(vec3 lightDirection, float goldenStrength)
{
#if SKY_SUN_TRANSMITTANCE_COLOR
    // 物理闭式基色（sky_sun_common.glsl 单一真源）。
    vec3 color = skySunPhysicalBaseColor(lightDirection.y);
#else
    float highSun = smoothstep(0.08, 0.68, max(lightDirection.y, 0.0));
    vec3 color = mix(vec3(1.0, 0.45, 0.18), vec3(1.0, 0.96, 0.90), highSun);
#endif
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);
    float thunder = clamp(cam.weatherParams.y, 0.0, 1.0);
    float sunset = 1.0 - smoothstep(0.04, 0.22, lightDirection.y);
    vec3 twilightTint = twilightPalette(lightDirection.y);
    color = mix(color, twilightTint, sunset * 0.78);
#if SKY_DAY_PHASE
    color = mix(color, skySunGoldenTint(color, lightDirection.y), goldenStrength);
#endif
    return color * mix(1.0, 0.35, rain) *
        mix(vec3(1.0), vec3(0.72, 0.78, 0.86), thunder);
}

// ---------------------------------------------------------------------------
// 2026-10-21 雷暴频闪（整场脉冲）
//
// weatherParams.y（原版 thunder，[0,1]）门控：每个 ~0.6 s 间隔按哈希决定要不要
// 打一次闪，主闪 ~0.08 s 指数衰减，0.14 s 后一次较弱的回声。返回 [0,1]，乘上
// 各消费点自己的颜色/强度就是一次天光闪。
//
// 时钟用 cam.frameCount：没有新增 uniform，且同一帧里天空 LUT / 云 / 视线天空
// 读的是同一个值 ⇒ 三处同步同一次闪。按 60 Hz 折算（帧率翻了闪频跟着翻，可接受）。
// 非雷暴（thunder <= 0）逐位返回 0 ⇒ 晴天/普通雨天画面的每一步都不受影响。
// ---------------------------------------------------------------------------
float lightningFlashEnvelope()
{
    float thunder = clamp(cam.weatherParams.y, 0.0, 1.0);
    if(thunder <= 0.001)
    {
        return 0.0;
    }
    float t = float(cam.frameCount) * (1.0 / 60.0);
    float scaled = t * 1.6;
    float cell = floor(scaled);
    float f = fract(scaled);
    float h = fract(sin(cell * 12.9898 + 4.1414) * 43758.5453);
    float gate = step(0.62, h);
    float a = exp(-f * 42.0);
    float b = 0.55 * exp(-max(f - 0.14, 0.0) * 55.0);
    return gate * (a + b) * thunder;
}

#endif // SKY_MODEL_COMMON_GLSL
