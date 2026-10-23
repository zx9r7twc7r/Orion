// =============================================================================
// Material resolution body (命中着色器内就地求值).
//
// 2026-10-20: 从 rt_test.rchit::main() 抽出来的共享体。
// 2026-10-23: 延迟材质（RT_DEFERRED_MATERIAL）整段删除后，这里是唯一的材质
// 求值路径 —— 由 rt_test.rchit 的命中着色器调用，ray generation 不再参与。
//
// The body below is the *verbatim* former body of rt_test.rchit::main(), only
// with the ray builtins replaced by parameters.  Do not restyle it: 它的调用点
// 假设这段与抽出来之前逐位等价。
//
// Contract for the includer:
//   * a variable named `payload` of type HitPayload (rayPayloadInEXT in the hit
//     shaders, plain global in rt_test.rgen);
//   * bindings 2/3/4/5/21/36/37/40/45/49/63/64, the Vertices/Vertex/
//     VertexMotions buffer references, glass_tint_common.glsl and
//     lod_coverage.glsl already included;
//   * every material helper used below (traceParallax, tracePOMSelfShadow,
//     evaluateWaterWaveSurface, ...) declared above this include.
//
// Identity packing used by the deferred hit shader (no ABI change: the payload
// struct stays byte-identical, so rt_test.rahit / rt_test.rmiss /
// rt_test_translucent.* need no edit):
//     payload.hitDistance = gl_HitTEXT
//     payload.normal      = vec3(instanceCustomIndex, primitiveID, attribs.x)
//     payload.roughness   = attribs.y
// instanceCustomIndex is 24-bit by spec and primitiveID is a section-local
// triangle index, so both round-trip exactly through float32.
// =============================================================================
#ifndef MATERIAL_RESOLVE_GLSL
#define MATERIAL_RESOLVE_GLSL

void resolveHitMaterial(vec3 rayOrigin, vec3 rayDirection)
{
    // 未命中：rt_test.rmiss 已经把 hitDistance 置 -1 并把天空辐亮度写进 albedo，
    // 这里必须原样放行，绝不能拿上一次命中的身份去解材质。
    if(payload.hitDistance < 0.0)
    {
        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 1u;
        }
        #endif
        return;
    }

    const uint instanceIndex =
        uint(payload.normal.x + 0.5);

    const uint primitiveIndex =
        uint(payload.normal.y + 0.5);

    const vec3 barycentrics =
        vec3(
            1.0 - payload.normal.z - payload.roughness,
            payload.normal.z,
            payload.roughness
        );

    const float hitT =
        payload.hitDistance;

    // 共享 helper（POM / 水波 / POM 自阴影）读这三个全局量而不是命中着色器的内建：
    //   materialHitT         <- gl_HitTEXT
    //   materialRayOrigin    <- gl_WorldRayOriginEXT
    //   materialRayDirection <- gl_WorldRayDirectionEXT
    // 漏写后两个 ⇒ POM 用世界 -Z 当视线 march ⇒ 图集 UV 随机 ⇒ 满屏彩色噪点。
    materialHitT = hitT;
    materialRayOrigin = rayOrigin;
    materialRayDirection = rayDirection;

    const uvec4 sectionAddressEntry =
        sectionVertexAddressBuffer.vertexAddresses[instanceIndex];

    uint64_t rawAddr =
        packUint2x32(sectionAddressEntry.xy);

    if(rawAddr == 0ul)
    {
        payload.hitDistance = -1.0;
        payload.state = makeHitState(
            getRayPurpose(payload.state), RT_HIT_OPAQUE,
            RT_WATER_NONE, false, false, 0u
        );
        payload.parallaxRayTravel = 0.0;
        payload.pomSelfShadow = 1.0;
        payload.pomMicroOcclusion = 1.0;
        payload.glassAlpha = 1.0;
        payload.prevOffsetX = 0.0;
        payload.prevOffsetY = 0.0;
        payload.prevOffsetZ = 0.0;
        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 2u;
        }
        #endif
        return;
    }

    // 2026-10-21 地形 32B：按表项 .w 分派取顶点（真实步长 = .w & ~1u）。
    // 2026-10-22 索引化地形：.w 的最低位 = "本段是 4 顶点/quad 索引布局"。
    //   图案 0,1,2 / 0,2,3（见 VulkanCore.cpp:sectionQuadIndexAddress）；
    //   三角形 t 的三个角：t 偶 ⇒ 4q+0,4q+1,4q+2；t 奇 ⇒ 4q+0,4q+2,4q+3（q = t>>1）。
    //   与非索引展开的 (v0,v1,v2) / (v0,v2,v3)**逐角一一对应** ⇒ 重心坐标语义不变。
    //   借最低位是安全的：步长只可能是 32/48，都是 4 的倍数。
    const bool indexedQuads =
        (sectionAddressEntry.w & 1u) != 0u;

    const uint sectionStride =
        sectionAddressEntry.w & ~1u;

    uint i0;
    uint i1;
    uint i2;

    if(indexedQuads)
    {
        const uint quadBase =
            (primitiveIndex >> 1u) * 4u;

        if((primitiveIndex & 1u) == 0u)
        {
            i0 = quadBase;
            i1 = quadBase + 1u;
            i2 = quadBase + 2u;
        }
        else
        {
            i0 = quadBase;
            i1 = quadBase + 2u;
            i2 = quadBase + 3u;
        }
    }
    else
    {
        i0 = primitiveIndex * 3u;
        i1 = i0 + 1u;
        i2 = i0 + 2u;
    }

    Vertex v0 = loadSectionVertex(rawAddr, sectionStride, i0);
    Vertex v1 = loadSectionVertex(rawAddr, sectionStride, i1);
    Vertex v2 = loadSectionVertex(rawAddr, sectionStride, i2);

    // 2026-10-09 motion vectors: previous-frame world offset of this hit.
    // Moved verbatim out of rt_test.rchit::main(); same section table entry,
    // same barycentric weights, same delta-camera correction.
    //
    // 2026-10-21（延迟化收益点 #2）：**只有主相机射线需要它**。
    // 唯一消费者是 rt_test.rgen 里 primary trace 之后那一次读取
    //（payload.prevOffset* → primaryHitWorldOffset），之后的每次 traceRay 都会把它
    // 覆盖 —— 也就是说所有次级命中（反射/AO/GI/探针/水面/玻璃贯穿）算出来的值是
    // **零读者**。代价却是每条命中 3 次 VertexMotions 采样（rawAddr + z 的 SSBO 访问）
    // 加一次重心插值与 Δcam 加法，属于纯浪费。
    // 主射线 purpose 固定是 RT_PURPOSE_PRIMARY_SKY（见 rgen 的"步骤 1"），两模式通用：
    // 延迟档由派发器把 purpose 搬进 payload.state，生产档直接读 ray payload。
    if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
    {
        vec3 hitWorldOffset =
            vec3(0.0);

        if(sectionAddressEntry.z != 0u)
        {
            VertexMotions sectionMotions =
                VertexMotions(
                    rawAddr + uint64_t(sectionAddressEntry.z)
                );

            hitWorldOffset =
                readVertexMotion(sectionMotions, i0) * barycentrics.x +
                readVertexMotion(sectionMotions, i1) * barycentrics.y +
                readVertexMotion(sectionMotions, i2) * barycentrics.z;

            // Delta camera: the stored segment is camera-relative, world motion
            // needs +Δcam.  Static sections (offset == 0) must stay exactly 0.
            //
            // 2026-10-22：**只在真的有位移数据时**才做这次换算。
            // Java 侧 resolveBatchMotion 在"没配对上上一帧"（首帧 / 匹配超距 /
            // 记录过期）时写的是 motion = 0，语义是"这一批不动/未知"，而不是
            // "相机相对位移恰好为 0"。旧代码无条件 += Δcam，于是这些像素会凭空
            // 得到一个**等于相机平移**的 MV ⇒ 一走动整片实体/粒子双倍位移（鬼影），
            // 而只转视角时 Δcam 的平移列恒为 0，所以看不出来。
            if(any(notEqual(hitWorldOffset, vec3(0.0))))
            {
                hitWorldOffset +=
                    cam.prevViewInverse[3].xyz -
                    cam.viewInverse[3].xyz;
            }
        }

        payload.prevOffsetX = hitWorldOffset.x;
        payload.prevOffsetY = hitWorldOffset.y;
        payload.prevOffsetZ = hitWorldOffset.z;
    }

float interpU =
    v0.u * barycentrics.x +
    v1.u * barycentrics.y +
    v2.u * barycentrics.z;


float interpV =
    v0.v * barycentrics.x +
    v1.v * barycentrics.y +
    v2.v * barycentrics.z;


vec2 texCoord =
    vec2(
        interpU,
        interpV
    );


float materialId =
    v0.materialId * barycentrics.x +
    v1.materialId * barycentrics.y +
    v2.materialId * barycentrics.z;

// Negative pad1 is reserved by the native Voxy LOD decoder. Its colour is
// already texture- and biome-resolved, so it must never sample blockAtlas at
// the placeholder UV (0, 0). -2 additionally marks the coarse lava/light
// material class as emissive.
float voxyLodMarker =
    v0.pad1 * barycentrics.x +
    v1.pad1 * barycentrics.y +
    v2.pad1 * barycentrics.z;

// ⭐ DH 远场几何的识别现在由角色表（binding 19）回答，不再靠 pad1 的数值暗号：
//     pad1 = -(3 + EDhApiBlockMaterial 编号)，值域 [-18, -3]（见 VulkanCore.cpp
//     的 appendDhLodQuads）——它现在只承担"材质编号"这一件事，下面 dhLodMaterial
//     仍由它还原成 0..15。于是两条 LOD 通路能共存：isDhLod 只认角色表里的 DH_FAR，
//     voxy 的两个判据在原区间维持原样（下面显式排除 DH 才敢这么说）。
//     这就是"DH 几何有自己的命中着色器"相对"借 voxy 的单 bit 水/发光"的全部差别：
//     材质编号随顶点进了 TLAS，所以水面能拿波浪法线、岩浆与光源方块能自发光、
//     金属能给对 F0。
const uint instanceRole = materialLodRole(instanceIndex);
bool isDhLod = instanceRole == LOD_ROLE_DH_FAR;
float dhLodMaterial =
    isDhLod
        ? clamp(round(-voxyLodMarker) - 3.0, 0.0, 15.0)
        : 0.0;
// 2026-10-03 **修复"雨条整片隐形"**：pad1 上的标记会撞车 ——
//   雨条 = RAIN marker (-2)，而 voxy 的"岩浆/光源方块"发光标记**也是 -2**
//   （见上面 1577 行注释），两者数值区间完全重叠；pad2 也没法区分（voxy 无纹理
//   quad 同样是 -1，等于粒子的"-槽位1"）。于是雨条被 `voxyLodMarker < -0.5` 吃掉
//   ⇒ isVoxyLod = true ⇒ encodedTextureSlot 被清零 ⇒ isParticleMaterial /
//   isRtWeatherRain 全 false ⇒ 走了 voxy LOD 通路（"已解析纹理"），方块路径里
//   被 alpha 剪裁 ⇒ **一滴都看不见**（几何明明在 TLAS 里）。
// 判据改为**认角色表**（与 isDhLod 同源，见上面 1584-1591 的说明）：
//   voxy 实例在角色表里是 LOD_ROLE_VOXY_LOD；雨/粒子/实体实例是 TERRAIN ⇒
//   数值暗号不再参与区分。voxy 的岩浆/光源发光标记照旧生效（角色对了才判）。
// 2026-10-03 实机验证后的**硬判据**：角色表这条不够（粒子实例的角色槽也是
// VOXY_LOD，等于没拦住）。改用宿主侧**确定的契约**：`uploadParticleBatch` 给每个
// 粒子顶点显式写 `materialId = 0`，而地形 / voxy LOD quad 都带真实材质号。
// 于是 "marker < -0.5 **且** materialId > 0.5" 才是真正的 voxy 几何；
// 雨/粒子（materialId = 0）不再被吞。
// ★★ 2026-10-03 决定性开关：**voxy LOD 分类默认关闭**。
//
// 依据：实体 / 粒子 / 雨滴的顶点 pad1 都是负的"发光标记"（雨 = -2），而 voxy 的
// 判据恰好是 `pad1 < -0.5` ⇒ 它们被误判成 voxy ⇒ 下面 1625 行把
// encodedTextureSlot 清零 ⇒ 既不是 entity 也不是 particle ⇒ 整条动态几何
// （**所有实体 + 粒子 + 雨滴**）在方块路径里被 alpha 剪裁掉 ⇒ 什么都不显示。
// 这与用户实测"所有实体都看不见、雨滴也看不见、且是老问题"完全一致。
//
// 用户不使用 voxy，因此这里把 voxy 分类整体关掉（默认 0）；要用 voxy 时把
// VOXY_LOD_ENABLE 改回 1（或 -DVOXY_LOD_ENABLE=1 重编），voxy 判定随即恢复。
#ifndef VOXY_LOD_ENABLE
#define VOXY_LOD_ENABLE 0
#endif

bool isVoxyLod =
    VOXY_LOD_ENABLE != 0 && !isDhLod && voxyLodMarker < -0.5 && materialId > 0.5;
bool isVoxyEmissive =
    VOXY_LOD_ENABLE != 0 && !isDhLod && voxyLodMarker < -1.5 && materialId > 0.5;

// VulkanCore expands byte-27 marker 23 into pad1=0.25.  Preserve this
// terrain-only tag through the existing opaque payload field; 0.25 cannot
// collide with normal opaque (1.0) or glass alpha handling.
float rainPuddleCandidate =
    isVoxyLod ? 0.0 : clamp(
        (v0.pad1 * barycentrics.x +
         v1.pad1 * barycentrics.y +
         v2.pad1 * barycentrics.z) * 4.0,
        0.0,
        1.0
    );

// P2/B: the bridge encodes "mip + shade/256" into pad2 for textured LOD
// quads (shade < 1 keeps floor() stable) and -1 for quads without a texture
// table entry, which fall back to the pre-modulated vertex colour.
float voxyMipShade =
    v0.pad2 * barycentrics.x +
    v1.pad2 * barycentrics.y +
    v2.pad2 * barycentrics.z;

// ⚠ DH 也必须排除：它的 pad2 是 0.996 / -1（图集 mip+shade 编码，见 VulkanCore.cpp
// 的 appendDhLodQuads）。漏掉这一项，round(0.996) = 1 会让每个远场 quad 都被
// 当成"实体纹理槽 1"，整片远场去采 entityAtlases。
float encodedTextureSlot =
    (isVoxyLod || isDhLod) ? 0.0 :
    (v0.pad2 * barycentrics.x +
     v1.pad2 * barycentrics.y +
     v2.pad2 * barycentrics.z);
uint entityTextureSlot = encodedTextureSlot > 0.5
    ? uint(round(encodedTextureSlot)) : 0u;
uint particleTextureSlot = encodedTextureSlot < -0.5
    ? uint(round(-encodedTextureSlot)) : 0u;
bool isEntityMaterial = entityTextureSlot != 0u;
bool isParticleMaterial = particleTextureSlot != 0u;

// Armour and other equipment carry a material class in the texture slot's
// fractional part (the capture side writes slot + class / 32). round() above
// still recovers the slot because every class stays well below 0.5, and slot 0
// keeps meaning "not an entity texture". Class 0 is the flat response the entity
// path has always used, so mob skins, particles and weather are unaffected.
float entityMaterialClass =
    isEntityMaterial
        ? floor(fract(encodedTextureSlot) * 32.0 + 0.5)
        : 0.0;
float particleEmissionMarker =
    v0.pad1 * barycentrics.x +
    v1.pad1 * barycentrics.y +
    v2.pad1 * barycentrics.z;
float entityEmissionMarker = particleEmissionMarker;
bool isRtWeatherRain =
    isParticleMaterial &&
    particleEmissionMarker < -1.5;
    vec4 c0 = vec4(v0.r, v0.g, v0.b, v0.a);
    vec4 c1 = vec4(v1.r, v1.g, v1.b, v1.a);
    vec4 c2 = vec4(v2.r, v2.g, v2.b, v2.a);
    vec4 interpColor = c0 * barycentrics.x + c1 * barycentrics.y + c2 * barycentrics.z;
    
    vec3 pos0 = vec3(v0.x, v0.y, v0.z);
    vec3 pos1 = vec3(v1.x, v1.y, v1.z);
    vec3 pos2 = vec3(v2.x, v2.y, v2.z);

    vec3 geoNormal = normalize(cross(pos1 - pos0, pos2 - pos0));

    // =====================================================
    // DISTANCE_ONLY FAST PATH
    //
    // 2026-10-06 近水掉帧降本：RR-6.2 主水反射 guide 射线（rgen 反射 Walker 的
    // 镜像距离探针，RT_PURPOSE_DISTANCE_ONLY）只消费 payload.hitDistance，却
    // 一直落进完整 rchit 着色 —— 命中水时 evaluateWaterWaveSurface（每水像素
    // 最贵的 ALU 块之一）、命中地形时 POM/图集/PBR 全部白跑，且近水时该射线
    // 几乎必然命中地形或水面。与 CLASSIFY 同一契约：返回几何稳定的最小
    // payload 后立即返回（miss 侧 rmiss 照常写 -1，rgen 的 >=0 判定不变）。
    // =====================================================
    if(getRayPurpose(payload.state) == RT_PURPOSE_DISTANCE_ONLY)
    {
        payload.hitDistance = hitT;

        payload.state = makeHitState(
            RT_PURPOSE_DISTANCE_ONLY,
            RT_HIT_OPAQUE,
            RT_WATER_NONE,
            false,
            false,
            0u
        );

        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 3u;
        }
        #endif
        return;
    }

    // =====================================================
    // CLASSIFY FAST PATH
    //
    // RT_PURPOSE_CLASSIFY is used only to identify the first
    // material/interface along the ray for water sun transport
    // and related queries.  Running the full POM/normal/PBR
    // path here introduces triangle-edge instability.
    //
    // Therefore classify rays return a minimal, geometry-stable
    // payload and skip all later shading work.
    // =====================================================
    if(getRayPurpose(payload.state) == RT_PURPOSE_CLASSIFY)
    {
        bool classifyIsWater =
            materialId > 0.5 &&
            materialId < 1.5;

        bool classifyIsGlass =
            materialId > 1.5 &&
            materialId < 2.5;

        bool hitBackFace =
            dot(
                geoNormal,
                rayDirection
            ) > 0.0;

        vec3 fixedNormal =
            geoNormal;

        if(classifyIsWater && abs(geoNormal.y) > 0.5)
        {
            vec3 geometricHitPosition =
                rayOrigin +
                rayDirection *
                hitT;

            // 2026-10-06：classify 只需要能量 march 的界面法线，走降本波面
            //（Seascape 封顶 2 八度）。可见水面着色（完整路径）不受影响。
            WaterWaveSurface waterWave =
                evaluateWaterWaveSurfaceClassify(
                    geometricHitPosition.xz,
                    geometricHitPosition.y
                );

            fixedNormal =
                dot(waterWave.normal, geoNormal) < 0.0
                    ? -waterWave.normal
                    : waterWave.normal;
        }

        if(dot(fixedNormal, rayDirection) > 0.0)
        {
            fixedNormal = -fixedNormal;
        }

        // =====================================================
        // 2026-10-06 近水掉帧降本：classify 的 albedo 只有一个消费者 ——
        // traceSunTransmittanceThroughWater 的**玻璃染色**分支
        //（rgen `glassSheetTransmission(payload.albedo, ...)`，仅
        // hitMaterialId ∈ [2.5,3.5]）。另一个消费者 traceWaterReflectedSun
        // 根本不读 albedo。所以水命中 / 不透明地形命中 / LOD 命中时，下面这次
        // 图集采样是**死算**（每水像素有 5~15 次 classify 命中）。
        // ⇒ 仅在玻璃命中时取样；非玻璃时给 (1,1,1,1)，值不可达。
        // =====================================================
        vec4 classifyAlbedo =
            vec4(1.0);

        if(classifyIsGlass)
        {
            classifyAlbedo =
                (isVoxyLod || isDhLod)
                    ? vec4(
                        voxyLodAlbedo(
                            voxyMipShade,
                            texCoord,
                            vec2(v0.u, v0.v),
                            vec2(v1.u, v1.v),
                            vec2(v2.u, v2.v),
                            voxyLodMip(pos0, pos1, geoNormal),
                            interpColor.rgb
                        ),
                        interpColor.a
                      )
                    : (isParticleMaterial
                        ? sampleParticleAtlas(particleTextureSlot, texCoord)
                        : sampleRTAlbedo(entityTextureSlot, texCoord)) *
                      interpColor;
        }

        payload.hitDistance =
            hitT;

        payload.parallaxRayTravel =
            0.0;

        payload.pomSelfShadow =
            1.0;
        payload.pomMicroOcclusion =
            1.0;

        payload.normal =
            normalize(fixedNormal);

        payload.albedo =
            classifyAlbedo.rgb;

        payload.glassAlpha =
            classifyIsGlass
                ? clamp(
                    classifyAlbedo.a,
                    0.0,
                    1.0
                )
                : 1.0;

        if(classifyIsGlass)
        {
            bool glassBottomFace =
                geoNormal.y < -0.5;

            payload.state = makeHitState(
                getRayPurpose(payload.state),
                RT_HIT_TRANSLUCENT,
                RT_WATER_NONE,
                true,
                glassBottomFace,
                uint(getTransparentProfileFromRawMaterialId(materialId))
            );
        }
        else if(classifyIsWater)
        {
            payload.state = makeHitState(
                getRayPurpose(payload.state),
                RT_HIT_WATER,
                hitBackFace ? RT_WATER_WATER_TO_AIR : RT_WATER_AIR_TO_WATER,
                false,
                false,
                0u
            );
        }
        else
        {
            payload.state = makeHitState(
                getRayPurpose(payload.state),
                RT_HIT_OPAQUE,
                RT_WATER_NONE,
                false,
                false,
                0u
            );
        }

        payload.roughness =
            1.0;

        payload.f0Metal =
            0.0;

        payload.emission =
            vec3(0.0);

        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 4u;
        }
        #endif
        return;
    }

    if(isVoxyLod)
    {
        // LOD 水的标记是 3.0，不是 1.0 —— 1.0 是**近场水**的共用契约，会被
        // section 重建前的分桶挪进 split.water 从而继承真正的地形 WATER
        // hit group（rt_test.rahit 从未处理过 LOD 顶点，实测进世界即崩）。
        // 独立值的选择依据：materialId 只有 (0.5,1.5)=水、(1.5,2.5)=玻璃
        // 两档判断，(2.5,3.5) 全工程无人使用。详见 VulkanCore.cpp:29899 附近。
        bool isVoxyWater = materialId > 2.5 && materialId < 3.5;
        bool isVoxyGlass = materialId > 1.5 && materialId < 2.5;
        // 只有**近水平**的水面才走水体折射通路：LOD 水面画在"装水那一格的顶边界"
        // 上（emitFace 的 +Y 面在 y+s，面落在 cell 顶），粗 cell 的
        // 水位天生比细 cell 高 1~3 格 ⇒ 级别交界/岸线渐变处出现**竖直台阶**，台阶
        // 侧面属于水本身。按水折射着色时视线会折进水体内、打到没有光照的内部薄片
        // ⇒ 实测"很明显的黑色分界面"（这是纯几何问题，与 shade 无关）。
        // 竖直台阶改按不透明的水面颜色着色：阶梯几何仍在（LOD 量化的固有结果），
        // 但不再读成黑洞。详见 rt_test_translucent.rchit 里同一处的长注释。
        bool voxyWaterSurface = isVoxyWater && abs(geoNormal.y) > 0.5;
        // 树叶：C++ 解码器按桥的官方材质字节（1 = LEAVES）打上 4.0 标记，见
        // VulkanCore.cpp 的 appendVoxyLodQuads。4.0 不落在任何既有材质区间 ⇒
        // 自动不是水、不是玻璃、glassAlpha = 1.0（按实心不透明着色）。粗糙度
        // 与下面 DH 分支的 dhIsLeaves 同源：远场 LOD 没有次表面通道，用高粗糙
        // 近似树叶的孔隙感。
        bool isVoxyLeaves = materialId > 3.5 && materialId < 4.5;
        vec3 fixedNormal = dot(geoNormal, rayDirection) > 0.0
            ? -geoNormal : geoNormal;
        vec3 voxyAlbedo = voxyLodAlbedo(
            voxyMipShade,
            texCoord,
            vec2(v0.u, v0.v),
            vec2(v1.u, v1.v),
            vec2(v2.u, v2.v),
            voxyLodMip(pos0, pos1, geoNormal),
            interpColor.rgb
        );
        payload.hitDistance = hitT;
        payload.parallaxRayTravel = 0.0;
        payload.pomSelfShadow = 1.0;
        payload.pomMicroOcclusion = 1.0;
        payload.normal = normalize(fixedNormal);
        payload.albedo = voxyAlbedo;
        payload.glassAlpha = isVoxyGlass ? clamp(interpColor.a, 0.06, 0.72) : 1.0;
        // LOD 水是**稀疏代理边界**，不拥有闭合介质 —— 用水的界面着色
        // （低粗糙度 + 介质 IOR），但**不声明介质进出**（RT_WATER_NONE 而非
        // AIR_TO_WATER / WATER_TO_AIR）。参考注释原文："把细交接环当体积会让它
        // 在生成的底/侧面之间累积吸收、变成黑灰相交薄片"；而我们这边若声明介质，
        // 每个 LOD 格都会被当成一个独立水体积，逐格累积 Beer-Lambert 吸收。
        payload.state = voxyWaterSurface
            ? makeHitState(getRayPurpose(payload.state), RT_HIT_WATER,
                RT_WATER_NONE,
                false, false, 0u)
            : makeHitState(getRayPurpose(payload.state),
                isVoxyGlass ? RT_HIT_TRANSLUCENT : RT_HIT_OPAQUE,
                RT_WATER_NONE, isVoxyGlass, isVoxyGlass && geoNormal.y < -0.5, 0u);
        // 竖直台阶（isVoxyWater 但非近水平）落到 0.78 的哑光档，按水面颜色着色。
        payload.roughness = voxyWaterSurface ? 0.08 : (isVoxyLeaves ? 0.92 : 0.78);
        payload.f0Metal = 0.0;
        payload.emission = isVoxyEmissive ? voxyAlbedo * 4.0 : vec3(0.0);
        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 5u;
        }
        #endif
        return;
    }

    // =====================================================
    // DH FAR-FIELD LOD —— DH 远场几何的专属命中着色器
    //
    // 这是"读 DH 数据 → 自己网格化 → 进 TLAS"这条统一路径的入口：远场几何命中后
    // 写出与近场完全相同的 payload 契约，因此它自动参与 GI / 阴影 / 反射 / 体积光，
    // rgen 里不再需要一套远场专用的合成与"重打光"。
    //
    // 与上面 voxy LOD 分支的唯一区别是"材质"：DH 把每个 quad 的真实方块材质
    // （EDhApiBlockMaterial 0..15）随顶点带进 TLAS（pad1 = -(3 + 编号)），于是水面
    // 能拿波浪法线、岩浆与光源方块能自发光、金属能给对 F0。voxy 那条路只有
    // "是不是水"这一个 bit，所以两边合并不了。
    //
    // 条件里的 !isParticleMaterial / !isEntityMaterial 是护栏：粒子与实体的 pad1
    // 也是负值（雨条用 < -1.5），万一带到 -3 以下不该被解释成地形。
    // =====================================================
    if(isDhLod && !isParticleMaterial && !isEntityMaterial)
    {
        // EDhApiBlockMaterial：0 UNKNOWN / 1 LEAVES / 4 METAL / 6 LAVA /
        // 8 SNOW / 12 WATER / 15 ILLUMINATED，其余（石、木、沙…）走默认档。
        bool dhIsWater  = abs(dhLodMaterial - 12.0) < 0.5;
        bool dhIsLava   = abs(dhLodMaterial - 6.0) < 0.5;
        bool dhIsLit    = abs(dhLodMaterial - 15.0) < 0.5;
        bool dhIsMetal  = abs(dhLodMaterial - 4.0) < 0.5;
        bool dhIsLeaves = abs(dhLodMaterial - 1.0) < 0.5;
        bool dhIsSnow   = abs(dhLodMaterial - 8.0) < 0.5;

        vec3 dhHitPosition =
            rayOrigin +
            rayDirection * hitT;
        // ⭐ 按方块取纹理（见 dhLodAlbedo / dhFaceUv）：顶点字节 6-9 带的是
        //   (textureTileId, normalIndex)，在 DH 自己的 blockRatioAtlas 里定位 tile。
        //   用几何法线算 mip（波浪法线会把 texel 密度搅乱）。
        vec3 dhAlbedo = dhLodAlbedo(
            floor(texCoord.x + 0.5),
            floor(texCoord.y + 0.5),
            dhHitPosition,
            geoNormal,
            interpColor.rgb
        );

        bool dhBackFace =
            dot(geoNormal, rayDirection) > 0.0;
        vec3 dhNormal = dhBackFace ? -geoNormal : geoNormal;

        // ---- 水面：与近场共用同一个波浪函数 ----
        // 近场水面在 classify 分支里调 evaluateWaterWaveSurface；远场几何的顶面
        // 同样是世界水平面，用同一个函数 ⇒ 远近水面波峰不会错相，交接处也不会
        // 出现"近处有浪、远处是镜子"的断裂。
        // 只在近水平面（|n.y| > 0.5）上做 —— 水体侧壁不该起浪。
        if(dhIsWater && abs(geoNormal.y) > 0.5)
        {
            WaterWaveSurface dhWave =
                evaluateWaterWaveSurface(
                    dhHitPosition.xz,
                    dhHitPosition.y
                );

            dhNormal =
                dot(dhWave.normal, geoNormal) < 0.0
                    ? -dhWave.normal
                    : dhWave.normal;

            if(dot(dhNormal, rayDirection) > 0.0)
            {
                dhNormal = -dhNormal;
            }
        }

        payload.hitDistance = hitT;
        payload.parallaxRayTravel = 0.0;
        payload.pomSelfShadow = 1.0;
        payload.pomMicroOcclusion = 1.0;
        payload.normal = normalize(dhNormal);
        payload.albedo = dhAlbedo;
        payload.glassAlpha = 1.0;
        payload.state = dhIsWater
            ? makeHitState(
                getRayPurpose(payload.state),
                RT_HIT_WATER,
                dhBackFace ? RT_WATER_WATER_TO_AIR : RT_WATER_AIR_TO_WATER,
                false,
                false,
                0u
            )
            : makeHitState(
                getRayPurpose(payload.state),
                RT_HIT_OPAQUE,
                RT_WATER_NONE,
                false,
                false,
                0u
            );

        // ---- 材质 → BSDF ----
        // 树叶在远场是实心面（我们的 LOD 没有次表面通道，payload 也没有 porosity
 // 字段），所以用"高粗糙"近似 porosity = 0.7 观感；金属沿用近场
        // LabPBR 的金属码 230/255，让远场的铁/金反光与近场一致；岩浆与光源方块
        // 自发光 —— 因为这是真正的命中辐射，它会参与 GI 弹射（夜间远场城市会真的
        // 照亮周围，而不只是"图片上亮着"）。
        float dhRoughness = 0.78;
        float dhF0 = 0.0;
        vec3 dhEmission = vec3(0.0);
        if(dhIsWater)
        {
            dhRoughness = 0.08;
        }
        else if(dhIsLava)
        {
            dhRoughness = 0.92;
            dhEmission = dhAlbedo * 4.0;
        }
        else if(dhIsLit)
        {
            dhRoughness = 0.86;
            dhEmission = dhAlbedo * 4.0;
        }
        else if(dhIsMetal)
        {
            dhRoughness = 0.35;
            dhF0 = 230.0 / 255.0;
        }
        else if(dhIsLeaves)
        {
            dhRoughness = 0.92;
        }
        else if(dhIsSnow)
        {
            dhRoughness = 0.45;
        }

        payload.roughness = dhRoughness;
        payload.f0Metal = dhF0;
        // ⭐ 加性烘焙方块光（顶点字节 13，见 dhBakedBlockLight）：夜里让远场
        //   村庄/街灯那一片亮起来，且作为命中辐射参与 GI。岩浆/光源方块本就以
        //   albedo*4 发光、blockLight 通常已是 15，这里再加一份占比很小（0.25 对 4.0）。
        payload.emission = dhEmission + dhBakedBlockLight(dhAlbedo, interpColor.a);
        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 6u;
        }
        #endif
        return;
    }

    // Entity model batches are already fully animated by Minecraft's 26.2
    // ModelFeatureRenderer.  They intentionally use their own Sampler0
    // texture and geometric normal only: terrain LabPBR/POM atlases cannot
    // be indexed with an entity skin UV.
    if(isEntityMaterial || isParticleMaterial)
    {
        vec3 fixedEntityNormal = geoNormal;
        if(dot(fixedEntityNormal, rayDirection) > 0.0)
        {
            fixedEntityNormal = -fixedEntityNormal;
        }

        vec4 entityAlbedo =
            (isRtWeatherRain
                ? vec4(
                    mix(
                        vec3(0.58, 0.68, 0.78),
                        sampleRainStreakAtlas(texCoord).rgb,
                        0.35
                    ) * RAIN_VISIBILITY_GAIN,   // 2026-10-03 雨条可见度 ×2
                    1.0
                )
                : isParticleMaterial
                ? sampleParticleAtlas(particleTextureSlot, texCoord)
                : sampleRTAlbedo(entityTextureSlot, texCoord)) *
            interpColor;

        payload.hitDistance = hitT;
        payload.parallaxRayTravel = 0.0;
        payload.pomSelfShadow = 1.0;
        payload.pomMicroOcclusion = 1.0;
        payload.glassAlpha = 1.0;
        // 2026-10-09 entity PBR 第二步：真 `_n`/`_s` 贴图（Java 按"同一 Identifier
        // 加 `_n`/`_s`"查表、C++ 与 binding 36 同构上传，见 binding 63/64）。
        // 先取一对 PBR 纹素；**没有贴图的槽**由 C++ 的 1×1 中性兜底填
        // normal=(0,0,0,1) / specular=(0,0,0,1) ⇒ 下面的"内容判据"（rgb 全 0）
        // 自动判成"无贴图"，因此不需要额外的标志位或 UBO 字段。
        vec4 entityNormalTexel;
        vec4 entitySpecularTexel;
        sampleEntityPbr(entityTextureSlot, texCoord, entityNormalTexel, entitySpecularTexel);
        bool hasEntityNormalMap =
            dot(entityNormalTexel.rgb, entityNormalTexel.rgb) >= 1.0e-4;
        bool hasEntitySpecularMap =
            any(greaterThan(entitySpecularTexel.rgb, vec3(0.0)));

        // 2026-10-16 LabPBR `_n.B` 材质 AO 接线（实体）：与方块路径同口径
        // （写进 pomMicroOcclusion，消费链 = 环境光/GI/间接反射）。1×1 中性
        // 兜底 (0,0,0,1) 走"无贴图"判据 ⇒ AO 保持 1.0，逐位旧档。
        if(hasEntityNormalMap)
        {
            payload.pomMicroOcclusion =
                clamp(entityNormalTexel.b, 0.0, 1.0);
        }

        // 实体没有切线属性：切线基由几何法线 + 世界 up 构造（最省且稳定）。
        // 2026-10-09：从"仅装备类"分支里**提出到外面** —— 真 `_n` 对怪物皮肤
        // （class 0）同样有效，不该再被类别门槛挡住。
        vec3 entityNormal = normalize(fixedEntityNormal);
        vec3 referenceUp = abs(entityNormal.y) < 0.9
            ? vec3(0.0, 1.0, 0.0)
            : vec3(1.0, 0.0, 0.0);
        vec3 entityTangent = normalize(cross(referenceUp, entityNormal));
        vec3 entityBitangent = cross(entityNormal, entityTangent);

        if(hasEntityNormalMap)
        {
            // LabPBR `_n`：RG = 切线空间 XY（0..1 → [-1,1]），Z 由单位长度重建。
            // B 是材质 AO，已在上面折进 payload.pomMicroOcclusion（2026-10-16）。
            vec2 tangentNormalXY = entityNormalTexel.rg * 2.0 - 1.0;
            float tangentNormalZ =
                sqrt(max(0.0, 1.0 - dot(tangentNormalXY, tangentNormalXY)));
            entityNormal = normalize(
                entityTangent * tangentNormalXY.x +
                entityBitangent * tangentNormalXY.y +
                entityNormal * tangentNormalZ);
        }
        else if(entityMaterialClass > 0.5 && entityMaterialClass < 6.5)
        {
            // 2026-10-06 entity PBR 第一步（纯着色器回退）：从 albedo 的**纹素梯度**
            // 重建细节法线。**只在没有真 `_n` 的槽上生效**（本包盔甲类都带 `_n`，
            // 这里留给第三方面板/低配包）。
            //
            // 只对**装备类**（1..6：铁/金/钻石/下界合金/铜/软质）生效：
            //   · class 0（怪物皮肤、粒子、雨）保持原样，不会被搅出噪点；
            //   · class 7（末地门星幕）也不参与。
            // 步长用 1/64（实体贴图常见尺寸），只做前向差分 ⇒ 中心值复用已采到的
            // entityAlbedo，只多两次采样。
            const float ENTITY_DETAIL_STEP = 1.0 / 64.0;
            const float ENTITY_DETAIL_GAIN = 3.5;
            float hCenter = dot(entityAlbedo.rgb, vec3(0.2126, 0.7152, 0.0722));
            vec3 albedoU = sampleRTAlbedo(
                entityTextureSlot, texCoord + vec2(ENTITY_DETAIL_STEP, 0.0)).rgb;
            vec3 albedoV = sampleRTAlbedo(
                entityTextureSlot, texCoord + vec2(0.0, ENTITY_DETAIL_STEP)).rgb;
            float hU = dot(albedoU, vec3(0.2126, 0.7152, 0.0722));
            float hV = dot(albedoV, vec3(0.2126, 0.7152, 0.0722));
            // 梯度限幅：纹理上的大色块不该把法线整片掀翻。
            vec2 gradient = clamp(
                vec2(hU - hCenter, hV - hCenter) * ENTITY_DETAIL_GAIN,
                vec2(-0.45), vec2(0.45));
            entityNormal = normalize(
                entityNormal - (entityTangent * gradient.x +
                    entityBitangent * gradient.y));
        }
        payload.normal = entityNormal;
        payload.albedo = entityAlbedo.rgb;
        payload.state = makeHitState(
            getRayPurpose(payload.state), RT_HIT_OPAQUE,
            RT_WATER_NONE, false, false, 0u
        );
        // Materials: a metal takes its F0 from the albedo, exactly like the block
        // path's LabPBR decode does for codes >= 230, so the armour texture's own
        // colour tints the reflection (grey iron, yellow gold, orange copper).
        // Class 0 keeps the flat roughness 0.72 / F0 0 response that mob skins,
        // particles and weather have always had.
        float entityRoughness = 0.72;
        float entityF0 = 0.0;
        if(entityMaterialClass > 0.5)
        {
            if(entityMaterialClass < 1.5)
            {
                // iron and chainmail
                entityRoughness = 0.35;
                entityF0 = 230.0 / 255.0;
            }
            else if(entityMaterialClass < 2.5)
            {
                // gold
                entityRoughness = 0.25;
                entityF0 = 230.0 / 255.0;
            }
            else if(entityMaterialClass < 3.5)
            {
                // diamond
                entityRoughness = 0.22;
                entityF0 = 230.0 / 255.0;
            }
            else if(entityMaterialClass < 4.5)
            {
                // netherite
                entityRoughness = 0.42;
                entityF0 = 230.0 / 255.0;
            }
            else if(entityMaterialClass < 5.5)
            {
                // copper
                entityRoughness = 0.30;
                entityF0 = 230.0 / 255.0;
            }
            else
            {
                // leather, turtle shell and elytra: a plain dielectric
                entityRoughness = 0.62;
                entityF0 = 10.0 / 255.0;
            }
        }
        // 2026-10-09 entity PBR 第二步：`_s` 按方块路径同口径解码
        // （见本文件 POM 段的 specular decode）：R = 感知光滑度 →
        // roughness = pow(1-r, 2)；G = LabPBR F0/金属码（原样交给 rgen 解码，
        // 与方块路径一致）；A = 自发光，**255 = 不发光**。
        // 有 `_s` 的槽直接覆盖上面的类别表值；没贴图的槽走中性兜底 ⇒ 保持类别值。
        float entityLabPbrEmissive = 0.0;
        if(hasEntitySpecularMap)
        {
            entityRoughness = pow(
                1.0 - clamp(entitySpecularTexel.r, 0.0, 1.0), 2.0);
            entityF0 = clamp(entitySpecularTexel.g, 0.0, 1.0);
            int entityEmissiveCode =
                int(round(clamp(entitySpecularTexel.a, 0.0, 1.0) * 255.0));
            entityLabPbrEmissive = entityEmissiveCode >= 255
                ? 0.0
                : clamp(float(entityEmissiveCode) / 254.0, 0.0, 1.0);
        }
        payload.roughness = entityRoughness;
        payload.f0Metal = entityF0;
        // Java marks only explicit fire, lava, spark, experience and note
        // particle registry types. pad1 is otherwise unused by particle ABI.
        payload.emission =
            (isParticleMaterial && particleEmissionMarker > 0.5) ||
            (isEntityMaterial && entityEmissionMarker < -0.5)
                ? entityAlbedo.rgb * 10.0
                : vec3(0.0);
        // LabPBR 自发光与上面标记式自发光**相加**（灼热的眼睛/魔法纹样画在贴图里，
        // 与 Java 整块标记的发光粒子互不冲突）。
        payload.emission += entityAlbedo.rgb * (entityLabPbrEmissive * 10.0);
        #ifdef RT_DEBUG_ALBEDO
        if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
        {
            g_debugBranch = 7u;
        }
        #endif
        return;
    }

    // =====================================================
    // POM-C5
    // Stable orthonormal TBN.
    //
    // Preserve UV handedness, but force tangent/bitangent to be
    // exactly orthogonal to the geometric normal.  This prevents
    // tiny triangle-basis differences from becoming large POM UV
    // changes at grazing angles.
    // =====================================================

    vec2 duv1 = vec2(v1.u - v0.u, v1.v - v0.v);
    vec2 duv2 = vec2(v2.u - v0.u, v2.v - v0.v);
    vec3 dp1 = pos1 - pos0;
    vec3 dp2 = pos2 - pos0;

    float determinant =
        duv1.x * duv2.y -
        duv1.y * duv2.x;

    vec3 tangent =
        vec3(1.0, 0.0, 0.0);

    vec3 bitangent =
        vec3(0.0, 1.0, 0.0);


    if(abs(determinant) > 1.0e-6)
    {
        float invDet =
            1.0 /
            determinant;


        vec3 rawTangent =
            (
                dp1 * duv2.y -
                dp2 * duv1.y
            ) *
            invDet;


        // Gram-Schmidt against the geometric normal.
        rawTangent -=
            geoNormal *
            dot(
                rawTangent,
                geoNormal
            );


        if(dot(rawTangent, rawTangent) > 1.0e-12)
        {
            tangent =
                normalize(
                    rawTangent
                );


            float handedness =
                determinant < 0.0
                    ? -1.0
                    : 1.0;


            bitangent =
                normalize(
                    cross(
                        geoNormal,
                        tangent
                    )
                ) *
                handedness;
        }
        else
        {
            vec3 c1 =
                cross(
                    geoNormal,
                    vec3(0.0, 0.0, 1.0)
                );

            vec3 c2 =
                cross(
                    geoNormal,
                    vec3(0.0, 1.0, 0.0)
                );

            tangent =
                length(c1) > length(c2)
                    ? c1
                    : c2;

            tangent =
                normalize(
                    tangent
                );

            bitangent =
                normalize(
                    cross(
                        geoNormal,
                        tangent
                    )
                );
        }
    }
    else
    {
        vec3 c1 =
            cross(
                geoNormal,
                vec3(0.0, 0.0, 1.0)
            );

        vec3 c2 =
            cross(
                geoNormal,
                vec3(0.0, 1.0, 0.0)
            );

        tangent =
            length(c1) > length(c2)
                ? c1
                : c2;

        tangent =
            normalize(
                tangent
            );

        bitangent =
            normalize(
                cross(
                    geoNormal,
                    tangent
                )
            );
    }


    mat3 tbn =
        mat3(
            tangent,
            bitangent,
            geoNormal
        );
    // =====================================================
// Current atlas sprite bounds
//
// Minecraft block UVs already point into the atlas.
// Triangle UV min/max gives this primitive's sprite
// rectangle, preventing POM from entering neighbours.
// =====================================================

vec2 uv0 =
    vec2(
        v0.u,
        v0.v
    );

vec2 uv1 =
    vec2(
        v1.u,
        v1.v
    );

vec2 uv2 =
    vec2(
        v2.u,
        v2.v
    );


vec2 spriteMin =
    min(
        uv0,
        min(
            uv1,
            uv2
        )
    );


vec2 spriteMax =
    max(
        uv0,
        max(
            uv1,
            uv2
        )
    );


vec2 atlasSize =
    vec2(
        textureSize(
            blockAtlas,
            0
        )
    );


vec2 atlasTexel =
    1.0 /
    max(
        atlasSize,
        vec2(1.0)
    );


vec2 spriteSpan =
    max(
        spriteMax -
        spriteMin,
        atlasTexel
    );


// Half-texel inset prevents bilinear filtering from
// reading the neighbouring atlas sprite during POM.

vec2 safeMin =
    spriteMin +
    atlasTexel * 0.5;


vec2 safeMax =
    spriteMax -
    atlasTexel * 0.5;


// Protect extremely small atlas regions.
vec2 spriteCenter =
    (
        spriteMin +
        spriteMax
    ) * 0.5;


safeMin =
    min(
        safeMin,
        spriteCenter
    );


safeMax =
    max(
        safeMax,
        spriteCenter
    );


// =====================================================
// PBR 图集（_n / _s）的显式 mip 选择
//
// 2026-10-19：太阳 GGX 高光只对法线/粗糙度的高频变化敏感。mip0 在"一个像素
// 覆盖多个纹素"时会让高光碎成点，屏幕上呈摩尔纹状的弧线与方块。RT 里
// texture() 的隐式 LOD 未定义（同 waterFftSlopeLodFromProj 的结论），所以按
// 屏幕足迹显式算：
//   worldPerPixel = 命中距离 / (projScale * NoV)
//   lod           = log2(worldPerPixel / 每个纹素的世界尺寸)
// 上限 = log2(sprite 的纹素数) - 1：越往下的 mip 足迹会跨进相邻 sprite。
// POM 高度（_n.a）与 emissive（_s.a）仍走 texelFetch，不受这里影响。
// =====================================================
float pbrSpriteTexels =
    max(spriteSpan.x * atlasSize.x, 1.0);

float pbrTexelWorld =
    1.0 / pbrSpriteTexels;

float pbrProjScale =
    float(gl_LaunchSizeEXT.y)
    / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));

float pbrNoV =
    max(
        abs(
            dot(
                normalize(geoNormal),
                normalize(rayDirection)
            )
        ),
        0.05
    );

float pbrWorldPerPixel =
    max(hitT, 1.0e-3)
    / (pbrProjScale * pbrNoV);

float pbrMaxLod =
    max(0.0, min(4.0, log2(pbrSpriteTexels) - 1.0));

float pbrLod =
    clamp(
        log2(max(pbrWorldPerPixel / pbrTexelWorld, 1.0e-4)),
        0.0,
        pbrMaxLod
    );

// =====================================================
// POM eligibility
//
// Water materialId = 1:
//     explicitly bypass POM.
//
// Missing normal texture:
//     bypass POM.
//
// Every other closest-hit ray uses the same path:
// primary / reflection / GI.
// =====================================================


vec2 basePbrTexCoord =
    clamp(
        texCoord,
        safeMin,
        safeMax
    );


// Water owns a separate interface/refraction pipeline in rgen.  It must
// never consume LabPBR normal, height, specular, AO, or emissive data here.
// Keep this test on the original vertex material ID, before payload ID 1/2
// is assigned to the two water interface directions below.
bool isWaterMaterial =
    materialId > 0.5 &&
    materialId < 1.5;

// -6 is emitted by rgen only while the transport segment is inside water.
// Keep the real triangle plane and base colour, but bypass POM for the
// underwater receiver so separate quad triangles cannot create solar-angle
// dependent lighting artifacts.
bool isWaterMediumTransport =
    getRayPurpose(payload.state) == RT_PURPOSE_WATER_MEDIUM;

// -4 is emitted only by the straight transparent-continuation ray after
// generic glass.  It reaches the same opaque receiver class as water, but
// does not alter its POM/normal path.
bool isTransparentReceiverTransport =
    getRayPurpose(payload.state) == RT_PURPOSE_PASSTHROUGH;


vec4 baseNormalSample =
    vec4(0.0);


if(!isWaterMaterial)
{
    baseNormalSample =
        textureLod(
            normalAtlas,
            basePbrTexCoord,
            pbrLod
        );
}
bool hasPBRNormalData =
    !isWaterMaterial &&
    !isWaterMediumTransport &&
    length(baseNormalSample.rgb) >= 0.01;

ParallaxHit parallaxHit;


parallaxHit.uv =
    texCoord;

parallaxHit.depth01 =
    0.0;

parallaxHit.borderScale =
    1.0;

parallaxHit.depthWorld =
    0.0;

parallaxHit.rayTravelWorld =
    0.0;

parallaxHit.worldPosition =
    rayOrigin +
    rayDirection *
    hitT;

parallaxHit.valid =
    false;


// Glass and water both keep their true triangle plane.  Transparent
// transport is a straight colour filter, so POM belongs only to opaque
// terrain and cannot distort a glass face or its underside.
bool isPOMEligibleMaterial =
    materialId <= 0.5;

// 2026-10-21：**POM 在次级命中不可砍**（用户决定）。原来的
// `-DPOM_DEFERRED_ONLY` 宏与"档 15 = 间接/反射命中免 POM"的诊断档已一并删除：
// 它等价于"按射线用途关掉视差质感"，与"同一份材质在所有命中上一致"冲突。
bool isPOMSurface =
    isPOMEligibleMaterial &&
    hasPBRNormalData;


if(
    isPOMSurface
)
{
    parallaxHit =
        traceParallax(
            basePbrTexCoord,
            spriteMin,
            safeMin,
            safeMax,
            spriteSpan,
            tbn,
            rayDirection
        );

    texCoord =
        parallaxHit.uv;
}


// =====================================================
// IMPORTANT:
// From this point onward ALL material textures use
// the SAME displaced material texel.
// =====================================================

// Keep parallaxHit.uv/texCoord continuous for POM traversal, displacement and
// edge classification.  Only material lookup returns to the centre of the
// selected atlas texel so Minecraft pixel art is not blurred by linear
// filtering at an arbitrary continuous POM coordinate.
vec2 materialSampleUV =
    (
        isPOMSurface &&
        parallaxHit.valid
    )
        ? snapPOMSpriteUVToTexelCenter(
            texCoord,
            safeMin,
            safeMax
        )
        : texCoord;

// The exact-fetch probe removed the reported water-receiver blur/misalignment.
// Apply that confirmed sampling rule to opaque receivers reached through water
// or generic glass.  In addition, the no-PBR fallback must agree with Any-Hit:
// Any-Hit already uses floor(texCoord) + texelFetch for the vanilla block
// atlas, while the old Closest-Hit path used linearly filtered continuous UVs.
// That mismatch blurred vanilla pixels and could blend a neighbouring sprite.
bool useExactTransparentReceiverAlbedo =
    !isWaterMaterial &&
    (
        isWaterMediumTransport ||
        isTransparentReceiverTransport
    );

bool useExactVanillaFallbackAlbedo =
    isPOMEligibleMaterial &&
    !hasPBRNormalData;

bool useExactAlbedo =
    useExactTransparentReceiverAlbedo ||
    useExactVanillaFallbackAlbedo;

vec4 albedoColor;

if(useExactAlbedo)
{
    vec2 exactAlbedoUV =
        clamp(
            materialSampleUV,
            safeMin,
            safeMax
        );

    ivec2 albedoSize = textureSize(blockAtlas, 0);
    ivec2 albedoTexel = ivec2(
        clamp(
            floor(exactAlbedoUV * vec2(albedoSize)),
            vec2(0.0),
            vec2(albedoSize) - vec2(1.0)
        )
    );

    albedoColor =
        texelFetch(blockAtlas, albedoTexel, 0) *
        interpColor;
}
else
{
    albedoColor =
        textureLod(
            blockAtlas,
            materialSampleUV,
            0.0
        ) *
        interpColor;
}

#ifdef RT_DEBUG_ALBEDO
// 诊断：只记主射线那一跳的基础色与用到的 UV，避免被反射/GI 的命中覆盖。
if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
{
    g_debugAlbedo = albedoColor.rgb;
    g_debugUV = materialSampleUV;
}
#endif

vec2 pbrTexCoord =
    clamp(
        materialSampleUV,
        safeMin,
        safeMax
    );
// =====================================================
// LabPBR 1.3 Normal Texture (_n)
//
// R/G = tangent-space normal X/Y
// B   = material AO
// A   = height / displacement
// =====================================================

vec4 normalSample =
    vec4(0.5, 0.5, 1.0, 1.0);


if(!isWaterMaterial)
{
    // 2026-10-07（M4）：POM 面之外复用 base（POM 前）那次采样，省一次 normalAtlas 取纹素。
    //
    // 为什么零差异：两次采样的唯一差别是 UV —— base 用
    // basePbrTexCoord = clamp(texCoord)，这里用 pbrTexCoord = clamp(materialSampleUV)。
    // materialSampleUV 只在 (isPOMSurface && parallaxHit.valid) 时被
    // snapPOMSpriteUVToTexelCenter 改写（见上面 2672-2682），而 texCoord 自身的覆写
    // （texCoord = parallaxHit.uv）也只在 isPOMSurface 分支里发生 ⇒ **!isPOMSurface
    // 时两个 UV 逐位相同**，clamp 的参数与顺序也相同 ⇒ 采样结果必然一致。
    //
    // 判据只写 !isPOMSurface、不写 parallaxHit.valid：POM 面走"无效"分支时
    // texCoord 已经被 parallaxHit.uv 覆写过，而 base 那次用的是覆写**前**的 texCoord。
    normalSample =
        isPOMSurface
            ? textureLod(
                normalAtlas,
                pbrTexCoord,
                pbrLod
            )
            : baseNormalSample;
}
bool hasNormalData = hasPBRNormalData;


vec3 worldNormal =
    geoNormal;


// LabPBR stores only normal XY.
// Z must be reconstructed.
if (hasNormalData)
{
    vec2 normalXY =
        normalSample.rg * 2.0 - 1.0;

    if(
        isPOMEligibleMaterial
    )
    {
        float viewFacing =
            clamp(
                abs(
                    dot(
                        geoNormal,
                        -rayDirection
                    )
                ),
                0.0,
                1.0
            );

        float grazingNormalStrength =
            mix(
                0.125,
                1.0,
                smoothstep(
                    0.0,
                    1.0,
                    viewFacing
                )
            );

        normalXY *=
            grazingNormalStrength;
    }


    float normalZ =
        sqrt(
            max(
                1.0 -
                dot(
                    normalXY,
                    normalXY
                ),
                0.0
            )
        );


    vec3 tangentNormal =
        normalize(
            vec3(
                normalXY,
                normalZ
            )
        );


    worldNormal =
        normalize(
            tbn *
            tangentNormal
        );
}


#if !POM_TILE_SPRITE
// At a physical block edge, adjacent faces do not share a tangent frame.
// Suppress only the outermost-texel normal disagreement; retain the complete
// POM coordinate and world displacement calculated above.
//
// Disabled with POM_TILE_SPRITE: the wrapped march yields a real relief sample
// in the outermost texels, so this blend no longer hides a degenerate value --
// it only flattened the outer two texels into a visible band along every edge
// shared by two same-material blocks.
if(
    parallaxHit.valid &&
    isPOMSurface
)
{
    worldNormal =
        normalize(
            mix(
                geoNormal,
                worldNormal,
                clamp(parallaxHit.borderScale, 0.0, 1.0)
            )
        );
}
#endif

// B = AO visibility.
// 1.0 = unoccluded
// 0.0 = fully occluded
float materialAO =
    hasNormalData
        ? clamp(normalSample.b, 0.0, 1.0)
        : 1.0;


// A = reversed height/depth.
// LabPBR: 255 = zero depth
//         0   = 25% maximum depth
float heightDepth =
    hasNormalData
        ? (1.0 - clamp(normalSample.a, 0.0, 1.0)) * 0.25
        : 0.0;


// =====================================================
// LabPBR 1.3 Specular Texture (_s)
//
// R = perceptual smoothness
// G = F0 / metal code
// B = porosity / SSS
// A = emissive
// =====================================================

vec4 specularSample =
    vec4(0.0, 0.0, 0.0, 1.0);


if(!isWaterMaterial)
{
    specularSample =
        textureLod(
            specularAtlas,
            pbrTexCoord,
            pbrLod
        );
}
// =====================================================
// LabPBR emissive mask
//
// IMPORTANT:
// _s.a contains the special value 255 = non-emissive.
//
// Therefore alpha MUST NOT use filtered textureLod sampling.
// Linear interpolation around 255 would create values below
// 255 and falsely turn non-emissive texels into emitters.
//
// Use the exact texel corresponding to the already POM-wrapped
// and atlas-safe pbrTexCoord.
// =====================================================

ivec2 specularSize =
    textureSize(
        specularAtlas,
        0
    );


vec2 emissiveUV =
    clamp(
        pbrTexCoord,
        safeMin,
        safeMax
    );


ivec2 emissiveTexel =
    ivec2(
        clamp(
            floor(
                emissiveUV *
                vec2(specularSize)
            ),
            vec2(0.0),
            vec2(specularSize) -
            vec2(1.0)
        )
    );


float emissiveAlpha =
    1.0;


if(!isWaterMaterial)
{
    emissiveAlpha =
        texelFetch(
            specularAtlas,
            emissiveTexel,
            0
        ).a;
}
// R : perceptual smoothness -> linear roughness
float perceptualSmoothness =
    clamp(
        specularSample.r,
        0.0,
        1.0
    );


float roughness =
    pow(
        1.0 -
        perceptualSmoothness,
        2.0
    );


// G : preserve exact LabPBR F0 / metal encoding.
// Decoded in RayGen.
float f0Metal =
    clamp(
        specularSample.g,
        0.0,
        1.0
    );


// B : 0..64   = porosity
//     65..255 = subsurface scattering
int materialCode =
    int(
        round(
            clamp(
                specularSample.b,
                0.0,
                1.0
            ) * 255.0
        )
    );


float porosity = 0.0;
float subsurface = 0.0;


if (materialCode <= 64)
{
    porosity =
        float(materialCode) /
        64.0;
}
else
{
    subsurface =
        float(materialCode - 65) /
        190.0;
}


// A : emissive
// 254 = 100%
// 255 = ignored / no emissive
int emissiveCode =
    int(
        round(
            emissiveAlpha * 255.0
        )
    );

float emission =
    emissiveCode >= 255
        ? 0.0
        : clamp(
            float(emissiveCode) / 254.0,
            0.0,
            1.0
        );
payload.hitDistance = hitT;
payload.parallaxRayTravel =
    parallaxHit.valid
        ? parallaxHit.rayTravelWorld
        : 0.0;

// =====================================================
// Water interface side marker
//
// 原始 geoNormal 保留三角形正反面信息：
//
// materialId = 1.0
//     水面正面命中：Air -> Water
//
// materialId = 2.0
//     水面背面命中：Water -> Air
//
// 其他材质保持原 materialId。
//
// 注意：payload.normal 仍然保持 face-forward，
// 所以不会破坏现有普通材质光照。
// =====================================================
bool hitBackFace =
    dot(
        geoNormal,
        rayDirection
    ) > 0.0;

vec3 fixedNormal =
    worldNormal;

if(isWaterMaterial && abs(geoNormal.y) > 0.5)
{
    vec3 geometricHitPosition =
        rayOrigin + rayDirection * hitT;
    WaterWaveSurface waterWave =
        evaluateWaterWaveSurface(
            geometricHitPosition.xz,
            geometricHitPosition.y
        );
    fixedNormal = dot(waterWave.normal, geoNormal) < 0.0
        ? -waterWave.normal : waterWave.normal;
}

if(
    dot(
        fixedNormal,
        rayDirection
    ) > 0.0
)
{
    fixedNormal =
        -fixedNormal;
}

float pomSelfShadow =
    1.0;


if(
    parallaxHit.valid &&
    isPOMEligibleMaterial &&
    hasNormalData
)
{
    vec3 pomSunDirection =
        getTimeDrivenSkyLightDirection();


    if(
        getTimeDrivenSkyLightRadianceScale() > 0.0 &&
        dot(
            normalize(fixedNormal),
            pomSunDirection
        ) > 0.0
    )
    {
        pomSelfShadow =
            tracePOMSelfShadow(
                texCoord,
                parallaxHit.depth01,
                spriteMin,
                safeMin,
                safeMax,
                spriteSpan,
                tbn,
                pomSunDirection
            );
    }
}


#if !POM_TILE_SPRITE
// Both the traversal and the self-shadow march now tile with the sprite, so
// this edge attenuation is redundant and would only re-introduce the band
// along shared same-material edges.
if(
    parallaxHit.valid &&
    isPOMSurface
)
{
    pomSelfShadow =
        mix(
            1.0,
            pomSelfShadow,
            clamp(parallaxHit.borderScale, 0.0, 1.0)
        );
}
#endif


// =====================================================
// Translucent PBR/POM
//
// pomSelfShadow keeps its original POM meaning for every
// POM-eligible surface, including exact glass/stained glass.
//
// Glass alpha has its own payload field so it can no longer
// overwrite POM information.
// =====================================================

payload.pomSelfShadow =
    clamp(
        pomSelfShadow,
        0.0,
        1.0
    );

// 2026-10-05 高度场微遮蔽（#12）/微结构反射遮蔽（#44）：POM 命中点越深，
// 环境光/GI/间接反射越少（静态、不随光方向；直射已由上面的方向 march 负责）。
// 0.5 系数 = 满深度 texel 保留 50% 环境量；非 POM 面由各写点给 1.0。
//
// 2026-10-16 LabPBR `_n.B` 材质 AO 接线：贴图 AO 与 POM 高度场遮蔽是两个
// 独立遮蔽源（前者是包作者画在纹理里的缝隙/凹孔，后者是位移场几何），
// 乘性叠加不构成双重计账。无 `_n` 数据 / 水（默认采样 .b=1.0）时逐位旧档。
payload.pomMicroOcclusion =
    clamp(
        1.0 - parallaxHit.depth01 * 0.5,
        0.5,
        1.0
    ) *
    materialAO;


payload.glassAlpha =
    (
        materialId > 1.5 &&
        materialId < 2.5
    )
        ? clamp(
            albedoColor.a,
            0.0,
            1.0
        )
        : (rainPuddleCandidate > 0.5 ? 0.25 : 1.0);

payload.normal =
    normalize(fixedNormal);

payload.albedo =
    albedoColor.rgb;


/// =====================================================
// T1-2 exact glass payload classification
//
// Vertex material IDs:
//   0 = opaque
//   1 = water
//   2 = glass / stained glass
//
// Payload ID 2 is already reserved for Water -> Air,
// therefore glass is translated to payload ID 3.
// =====================================================

bool t12IsWater =
    materialId > 0.5 &&
    materialId < 1.5;


// 2026-10-06 地狱传送门（RT materialId 6.0，Java marker 24 → native 映射）。
// 必须划进"玻璃族"这一档：落到下面的"通用半透明体"会被当**折射介质**处理，
// 门面就会发糊、纹理还会被偏移（用户报"地狱门贴图很模糊、感觉错位了"）。
// 它自己那份自发光在下面单独给（玻璃族本身是清零的）。
const bool t12IsPortal =
    materialId > 5.5 &&
    materialId < 6.5;
bool t12IsGlass =
    (materialId > 1.5 &&
     materialId < 2.5) ||
    t12IsPortal;


payload.albedo =
    albedoColor.rgb;

// ===== 探针已迁至 rt_test_translucent.rchit（染色玻璃的真实入口）=====


if(t12IsGlass)
{
    // =====================================================
    // Glass camera-side classification
    //
    // 3 = every non-bottom glass surface -> straight transmission
    //
    // 4 = physical bottom face only
    //     -> retained as a distinct non-camera traversal marker;
    //        rgen uses the same straight camera-transmission path as ID 3.
    //
    // The distinct bottom-face marker preserves one-filter-per-block
    // handling for non-camera transport without making the visible
    // underside opaque.
    // =====================================================

    // Triangle winding is not a reliable camera-side medium test for
    // Minecraft translucent quads.  Treating every back-facing side as an
    // exit sent it through the opaque primary path and made that glass face
    // appear dark.  Preserve the bottom-face marker only for non-camera
    // traversal; the primary camera path is handled in rgen.
    bool glassBottomFace =
        geoNormal.y < -0.5;


    payload.state = makeHitState(
        getRayPurpose(payload.state), RT_HIT_TRANSLUCENT,
        RT_WATER_NONE, true, glassBottomFace,
        uint(getTransparentProfileFromRawMaterialId(materialId))
    );

    // 2026-10-07（用户要求）：**普通玻璃当空气** —— 与 rt_test_translucent.rchit 的同名
    // 判断逐字一致（那份是主相机玻璃的真实入口，这份是其余路径的兜底）。
    // 判据：顶点因子是原版四个灰面着色之一 ⇒ 没注入染料 ⇒ 普通玻璃。
    // 必须写在 makeHitState 之后（它会重建整个 state）。回退：rgen 侧
    // GI_CLEAR_GLASS_AS_AIR=0。
    //
    // ⚠ 2026-10-09：与 rt_test_translucent.rchit 的同名判断同步加 `materialId < 2.05`
    //   —— "当空气"只留给普通玻璃块/板；遮光玻璃 2.1 / 冰 2.2 / 粘液 2.3 / 蜂蜜 2.4
    //   恢复各自原本的电介质（折射 + 反射）观感。
    if(materialId < 2.05 && glassVertexFactorIsFaceShade(interpColor.rgb))
    {
        payload.state |= RT_STATE_CLEAR_GLASS_BIT;
    }
}
else if(t12IsWater)
{
    payload.state = makeHitState(
        getRayPurpose(payload.state), RT_HIT_WATER,
        hitBackFace ? RT_WATER_WATER_TO_AIR : RT_WATER_AIR_TO_WATER,
        false, false, 0u
    );
}
else
{
    payload.state = makeHitState(
        getRayPurpose(payload.state), RT_HIT_OPAQUE,
        RT_WATER_NONE, false, false, 0u
    );
}

// =====================================================
// 2026-10-05 宏观材质变化（#35）：低频值噪声（9 格格点，XZ + 高度偏移）
// 缓慢调制 albedo 亮度（±6%）与粗糙度（±0.08）—— 大面积墙面出现缓慢
// 变化的色差与粗糙度差，打破"一整面墙一个颜色"的均质感。
// 纯 ALU（8 hash + 双线性），无纹理读、无 trace。Y 参与第三轴避免上下
// 完全同图案。粗粒度噪声只动观感不动能量守恒（乘性 ±6%）。
// 回退：GI_MACRO_VARIATION 置 0 逐位旧档。
// =====================================================
#ifndef GI_MACRO_VARIATION
#define GI_MACRO_VARIATION 1
#endif
#if GI_MACRO_VARIATION
{
    vec3 macroHitPos =
        rayOrigin + rayDirection * hitT;
    vec2 macroP = macroHitPos.xz * (1.0 / 9.0) +
        vec2(macroHitPos.y * (1.0 / 23.0));
    vec2 macroCell = floor(macroP);
    vec2 macroF = fract(macroP);
    macroF = macroF * macroF * (3.0 - 2.0 * macroF);
    float macroHashX = macroCell.x * 127.1 + macroCell.y * 311.7;
    float macroN00 = fract(sin(macroHashX) * 43758.5453);
    float macroN10 = fract(sin(macroHashX + 127.1) * 43758.5453);
    float macroN01 = fract(sin(macroHashX + 311.7) * 43758.5453);
    float macroN11 = fract(sin(macroHashX + 438.8) * 43758.5453);
    float macroNoise = mix(
        mix(macroN00, macroN10, macroF.x),
        mix(macroN01, macroN11, macroF.x),
        macroF.y);
    albedoColor *= 1.0 + (macroNoise - 0.5) * 0.12;
    roughness = clamp(roughness + (macroNoise - 0.5) * 0.16, 0.0, 1.0);
}
#endif

payload.roughness =
    roughness;

payload.f0Metal =
    f0Metal;
// 地狱传送门的自发光。注意 t12IsGlass 现在**包含**传送门（它要玻璃族的锐利面），
// 所以这里必须先判 t12IsPortal，否则会走进"玻璃清零"那一支 ⇒ 门不发光。
payload.emission =
    t12IsPortal
        ? albedoColor.rgb * 4.0
        : (t12IsGlass
            ? vec3(0.0)
            : albedoColor.rgb * emission * 10.0);
}


#endif // MATERIAL_RESOLVE_GLSL
