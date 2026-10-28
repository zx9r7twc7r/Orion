// =============================================================================
// Translucent (camera-side glass) material body.
//
// 2026-10-20: 从 rt_test_translucent.rchit::main() 抽出的共享体（染料色、薄片
// lobe、染色玻璃、传送门自发光、POM 浮雕）。Body below is the verbatim former
// body of that shader's main(), with the ray builtins replaced by parameters.
// 2026-10-23: 延迟材质整段删除后，这是玻璃片材质唯一的求值路径。
//
// Includer contract (same as material_resolve.glsl, plus):
//   * payload, sectionVertexAddressBuffer, Vertices/Vertex/VertexMotions,
//     camera `cam`, and the glass helpers (voxyTileIsFullyCovered,
//     glassVertexFactorIsFaceShade, ...) visible above this include;
//   * materialHitT materialRayOrigin materialRayDirection materialLodRole().
// =============================================================================
#ifndef MATERIAL_RESOLVE_GLASS_GLSL
#define MATERIAL_RESOLVE_GLASS_GLSL

void resolveHitMaterialGlass(vec3 rayOrigin, vec3 rayDirection, vec3 barycentrics)
{
    if(payload.hitDistance < 0.0)
    {
        return;
    }

    const uint instanceIndex =
        uint(payload.normal.x + 0.5);

    const uint primitiveIndex =
        uint(payload.normal.y + 0.5);

    const float hitT =
        payload.hitDistance;

    // 同 material_resolve.glsl：共享 helper 读这三个全局量，必须在这里写齐。
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
        return;
    }

    // 2026-10-21 地形 32B：按表项 .w 分派取顶点（真实步长 = .w & ~1u）。
    // 2026-10-22 索引化地形：.w 最低位 = "4 顶点/quad 索引布局"，三角形 t 的三个角
    //   为 t 偶 ⇒ 4q+0,4q+1,4q+2；t 奇 ⇒ 4q+0,4q+2,4q+3（q = t>>1）。
    //   与非索引展开逐角对应 ⇒ 重心坐标语义不变。说明详见 material_resolve.glsl。
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

    // 2026-10-21（延迟化收益点 #2，与 material_resolve.glsl 同一处）：运动矢量只有
    // 主相机射线会被消费；玻璃贯穿（PASSTHROUGH）等次级命中的值是零读者，
    // 不必付那 3 次 VertexMotions 采样。
    if(getRayPurpose(payload.state) == RT_PURPOSE_PRIMARY_SKY)
    {
        vec3 hitWorldOffset = vec3(0.0);

        if(sectionAddressEntry.z != 0u)
        {
            VertexMotions sectionMotions =
                VertexMotions(rawAddr + uint64_t(sectionAddressEntry.z));

            hitWorldOffset =
                readVertexMotion(sectionMotions, i0) * barycentrics.x +
                readVertexMotion(sectionMotions, i1) * barycentrics.y +
                readVertexMotion(sectionMotions, i2) * barycentrics.z;

            // 2026-10-22：与 material_resolve.glsl 同一处修正 —— 位移段为 0 表示
            // "没有配对到上一帧"（Java 侧写 0），此时加 Δcam 会凭空造出相机平移
            // 的 MV。只在确有位移数据时才换算。
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
float voxyLodMarker =
    v0.pad1 * barycentrics.x +
    v1.pad1 * barycentrics.y +
    v2.pad1 * barycentrics.z;

// ⭐ DH 水面几何走的就是这条 ALPHA 通道（VulkanCore 把它的 water 桶放进
// SECTION_AS_CLASS_ALPHA = 3<<60），所以 rt_test.rchit 里那份 DH 命中分支
// 必须在这里同样存在一份 —— 否则"远场水面有波浪"只在透射/折射类光线上成立。
// 识别约定与那边完全一致：DH_FAR 由角色表（binding 19）给出；pad1 只再表示
// 材质编号（-(3 + EDhApiBlockMaterial)，[-18, -3]），下面仍由它还原成 0..15。
const uint instanceRole = materialLodRole(instanceIndex);
bool isDhLod = instanceRole == LOD_ROLE_DH_FAR;
float dhLodMaterial =
    isDhLod
        ? clamp(round(-voxyLodMarker) - 3.0, 0.0, 15.0)
        : 0.0;
bool isVoxyLod = !isDhLod && voxyLodMarker < -0.5;
bool isVoxyEmissive = !isDhLod && voxyLodMarker < -1.5;
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
    // 2026-10-06 近水掉帧降本：与 rt_test.rchit 同一契约 —— RR-6.2 主水反射
    // guide 射线只消费 payload.hitDistance，完整波面/PBR 着色对它是纯浪费。
    // 返回几何稳定的最小 payload 后立即返回。
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

        return;
    }

    // Voxy LOD colours have already been resolved against the active resource pack. The provider
    // does not carry Minecraft atlas UVs, therefore this branch bypasses all atlas/POM reads.
    // P2/B: textured quads carry "mip + shade/256" in pad2 and sample the voxy
    // atlas at mip[lod]; pad2 < 0 falls back to pre-modulated vertex colour.
    if(isVoxyLod)
    {
        // LOD 水的标记是 3.0，不是 1.0 —— 1.0 是**近场水**的共用契约，会被
        // section 重建前的分桶挪进 split.water 从而继承真正的地形 WATER
        // hit group（rt_test.rahit 从未处理过 LOD 顶点，实测进世界即崩）。
        // 独立值的选择依据：materialId 只有 (0.5,1.5)=水、(1.5,2.5)=玻璃
        // 两档判断，(2.5,3.5) 全工程无人使用。详见 VulkanCore.cpp:29899 附近。
        bool isVoxyWater = materialId > 2.5 && materialId < 3.5;
        // ⭐ 只有**近水平**的水面才走水体折射通路（2026-09-25 实测取证）。
        // LOD 水面是画在"装水那一格的顶边界"上的（emitFace 传
        // y*cellW 且 +Y 面在 y+s ⇒ 面落在 cell 顶），粗 cell 的水位因此天生比细
        // cell 高 1~3 格 ⇒ 级别交界与岸线渐变处出现**竖直台阶**，而台阶侧面属于
        // 水本身。若把它也按水折射着色，视线会折进水体内、打到没有光照的内部竖直
        // 薄片 ⇒ 用户实测的"很明显的黑色分界面"。
        // 所以只用 |n.y| > 0.5 的面声明水体；竖直台阶按不透明的水面颜色着色 ——
        // 阶梯几何仍在（那是 LOD 量化的固有结果，只能改桥/buildMesh 才能消除），
        // 但它不再读成黑洞，而是读成梯田状的水。
        bool voxyWaterSurface = isVoxyWater && abs(geoNormal.y) > 0.5;
        float voxyMipShade =
            v0.pad2 * barycentrics.x +
            v1.pad2 * barycentrics.y +
            v2.pad2 * barycentrics.z;

        // Leaves, glass and ice share the bridge's translucent bucket.  Only
        // a fully covered tile is a real glass sheet; cutout foliage shades as
        // opaque, matching voxy's ModelFactory which forces LEAVES into the
        // solid layer.  The tile-average probe keeps the decision constant
        // across the whole quad.  Voxy lava also lands in this bucket (official
        // material 6, transparent) but is an emissive solid, never a glass
        // sheet, so the emissive marker vetoes the classification.
        bool isVoxyGlass =
            !isVoxyEmissive &&
            materialId > 1.5 && materialId < 2.5 &&
            (voxyMipShade < 0.0 ||
                voxyTileIsFullyCovered(
                    vec2(v0.u, v0.v),
                    vec2(v1.u, v1.v),
                    vec2(v2.u, v2.v)));

        // 树叶：C++ 解码器按桥的官方材质字节（1 = LEAVES）打上 4.0 标记，见
        // VulkanCore.cpp 的 appendVoxyLodQuads。上面那条 voxyTileIsFullyCovered
        // 只是"图集格有没有洞"的兜底猜测——图集格取不到时会直接把树叶判成玻璃
        // （2026-09-25 实机症状：远场只剩树干、树冠整片消失）。4.0 是真判据。
        // 4.0 不落在任何既有材质区间 ⇒ 自动不是水、不是玻璃、glassAlpha = 1.0。
        // 粗糙度 0.92 与 rt_test.rchit 的 DH 分支 dhIsLeaves 同源：远场 LOD 没有
        // 次表面通道，用高粗糙近似树叶的孔隙感。
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
        // 同步 alpha blend 源 alpha（voxy 玻璃也会进玻璃透射分支）。
        payload.glassBlendAlpha = payload.glassAlpha;
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
        return;
    }

    // =====================================================
    // DH FAR-FIELD LOD —— 远场 LOD 几何（ALPHA 桶）的专属命中着色器
    //
    // 与 rt_test.rchit 里的同名分支保持一致：远场几何写出与近场相同的 payload
    // 契约，从而走统一光照。"远场水面起浪"必须在这里也实现一遍，否则它只在
    // 折射/透射类光线上成立、而主视图仍是镜子。
    //
    // ⚠ 2026-09-25 更正（原注释写的是"DH 的水面 quad 被 VulkanCore 放进
    // 3<<60（ALPHA）桶"，与代码不符）：
    //   - voxy LOD 的水**确实**走这条分支：解码器刻意把它留在 alpha 桶
    //     （materialId = 1），所以下面的 isVoxyWater 会命中。这是设计意图。
    //   - DH LOD 的水**不走**这条分支：appendDhLodQuads 把它写进 water 分区，
    //     section 重建时又被登记成 SECTION_AS_CLASS_WATER（4<<60），最终落到
    //     WATER hit group（rt_test.rchit + rt_test.rahit）。所以本文件里下面
    //     那段 DH 分支在当前 C++ 下不可达，是死代码 —— 除非把 DH 的水也改成
    //     留在这条通路，否则别指望它生效。
    // =====================================================
    if(isDhLod)
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
        // ⭐ 按方块取纹理（见 dhLodAlbedo / dhFaceUv）：顶点字节 6-9 = (tileId, normalIndex)。
        float dhTileId =
            v0.u * barycentrics.x +
            v1.u * barycentrics.y +
            v2.u * barycentrics.z;
        float dhNormalIndex =
            v0.v * barycentrics.x +
            v1.v * barycentrics.y +
            v2.v * barycentrics.z;
        vec3 dhAlbedo = dhLodAlbedo(
            floor(dhTileId + 0.5),
            floor(dhNormalIndex + 0.5),
            dhHitPosition,
            geoNormal,
            interpColor.rgb
        );

        bool dhBackFace =
            dot(geoNormal, rayDirection) > 0.0;
        vec3 dhNormal = dhBackFace ? -geoNormal : geoNormal;

        // 水面：与近场共用同一个波浪函数（近场在 classify 分支里调它）⇒ 远近
        // 水面波峰同相，不会出现"近处有浪、远处是镜子"。只在近水平面起浪，
        // 水体侧壁保持几何法线。
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
        // DH 几何里没有玻璃：水面是唯一该有界面语义的东西，而它由
        // state = RT_HIT_WATER 表达，所以这里恒 1.0。
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
        return;
    }

    // v10 fixed-mip sampling (bridge selected mip = LOD ring level) with no
    // sRGB decode, no tint multiply and no leaves/glass split: superseded by
    // the analytical path above.  Disabled, kept as a future interface option.
#if 0
    if(isVoxyLod)
    {
        bool isVoxyWater = materialId > 2.5 && materialId < 3.5;
        bool isVoxyGlass = materialId > 1.5 && materialId < 2.5;
        vec3 fixedNormal = dot(geoNormal, rayDirection) > 0.0
            ? -geoNormal : geoNormal;
        float voxyMipShade =
            v0.pad2 * barycentrics.x +
            v1.pad2 * barycentrics.y +
            v2.pad2 * barycentrics.z;
        vec3 voxyAlbedo = interpColor.rgb;
        if(voxyMipShade >= 0.0)
        {
            float mip = floor(voxyMipShade);
            float shade = voxyMipShade - mip;
            voxyAlbedo = textureLod(voxyLodAtlas, texCoord, mip).rgb * shade;
        }
        payload.hitDistance = hitT;
        payload.parallaxRayTravel = 0.0;
        payload.pomSelfShadow = 1.0;
        payload.pomMicroOcclusion = 1.0;
        payload.normal = normalize(fixedNormal);
        payload.albedo = voxyAlbedo;
        payload.glassAlpha = isVoxyGlass ? clamp(interpColor.a, 0.06, 0.72) : 1.0;
        // 同步 alpha blend 源 alpha（voxy 玻璃也会进玻璃透射分支）。
        payload.glassBlendAlpha = payload.glassAlpha;
        // LOD 水是**稀疏代理边界**，不拥有闭合介质 —— 用水的界面着色
        // （低粗糙度 + 介质 IOR），但**不声明介质进出**（RT_WATER_NONE 而非
        // AIR_TO_WATER / WATER_TO_AIR）。参考注释原文："把细交接环当体积会让它
        // 在生成的底/侧面之间累积吸收、变成黑灰相交薄片"；而我们这边若声明介质，
        // 每个 LOD 格都会被当成一个独立水体积，逐格累积 Beer-Lambert 吸收。
        payload.state = isVoxyWater
            ? makeHitState(getRayPurpose(payload.state), RT_HIT_WATER,
                RT_WATER_NONE,
                false, false, 0u)
            : makeHitState(getRayPurpose(payload.state),
                isVoxyGlass ? RT_HIT_TRANSLUCENT : RT_HIT_OPAQUE,
                RT_WATER_NONE, isVoxyGlass, isVoxyGlass && geoNormal.y < -0.5, 0u);
        payload.roughness = isVoxyWater ? 0.08 : 0.78;
        payload.f0Metal = 0.0;
        payload.emission = isVoxyEmissive ? voxyAlbedo * 4.0 : vec3(0.0);
        return;
    }
#endif

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
const bool isWaterMaterial = false;

// -6 is emitted by rgen only while the transport segment is inside water.
// Keep the real triangle plane and base colour, but bypass POM for the
// underwater receiver so separate quad triangles cannot create solar-angle
// dependent lighting artifacts.
const bool isWaterMediumTransport = false;

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
            0.0
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


// This shader is reached only through group3, the non-water translucent
// partition.  Glass and stained glass use the existing POM path to create a
// shaded surface layer; it still does not change ray topology or refraction.
bool isGlassSurface =
    materialId >= 1.5 &&
    materialId < 2.5;

// =====================================================
// 玻璃顶点标志场（Java: SectionCompilerMixin 的 GLASS_*，native 抬进 pad2）
//
//   bits 0..3 = CTM 邻接掩码：1 = sprite u-min 边 / 2 = u-max 边 /
//               4 = v-min 边 / 8 = v-max 边
//   bit 4     = 平窗档：这块面的主人是**原版普通玻璃**（2026-10-12 起含玻璃板，
//               Java: SectionCompilerMixin.isFlatGlassOwner）。命中就退成"贴图 +
//               背后直接透视"：不做 POM 位移、不采法线贴图、不吃图集 AO
//               （2026-10-08 用户要的效果，10-12 明确扩到普通玻璃板）。
//               染色玻璃/染色板（marker 3..18）、遮光玻璃、冰/粘液/蜂蜜都不带
//               这一位，浮雕与受光观感保持原样。
//
// voxy / DH LOD 的 pad2 是 mip+shade 编码（见上面 1443 / 1683 行），必须先排除；
// 实体的 pad2 是纹理槽 1..16，靠 isGlassSurface 一并挡掉。
// =====================================================
int glassVertexFlags =
    (isVoxyLod || isDhLod)
        ? 0
        : int(
              v0.pad2 * barycentrics.x +
              v1.pad2 * barycentrics.y +
              v2.pad2 * barycentrics.z +
              0.5);

const bool isFlatGlassWindow =
    isGlassSurface &&
    (glassVertexFlags & 16) != 0;

// =====================================================
// 2026-10-28：玻璃**默认不做 POM 位移**
//
// 现象：除正对面外的所有玻璃面都有"固定形状的块状噪点"，正对面干净。
//
// 机理（逐条可查）：
//   · traceParallax 的 UV 偏移
//       (viewDirTS.xy / stableViewZ) * spriteSpan * pomDepthScale * grazingFade
//     只在 viewZ < 0.10 时才衰减（POM_GRAZING_MIN_Z 0.04 / FULL_Z 0.10）。
//     玻璃的邻面 viewZ 在 0.1~0.5 ⇒ 仍是**全强度**：viewZ=0.2 时
//     |xy/viewZ| ≈ 4.9，乘 spriteSpan 与 pomDepthScale 就是半个 sprite 宽。
//   · 高度场是 texelFetch 的**单纹素**（sampleLabPBRDepthInSprite），
//     越界立刻 break、保留"最后一个有效 texel"（isPOMSpriteUVInBounds）。
//     ⇒ 相邻像素停在不同 texel 上，块大小 = 一个 texel 的屏幕投影，
//        而且完全确定性 ⇒ 逐帧不闪的固定图案。
//   · 正对面 viewDirTS.xy ≈ 0 ⇒ 偏移 ≈ 0 ⇒ 不 march ⇒ 干净。
//
// 所以这不是"噪声"，是 POM 在玻璃上的越界切块。
// 玻璃表面的浮雕信息由法线贴图继续提供（正常采样，不受本开关影响）。
//
// ORION_GLASS_POM=1 恢复浮雕（此时走下面的掠射角衰减缓解越界）。
// =====================================================
#ifndef ORION_GLASS_POM
#define ORION_GLASS_POM 0
#endif

bool isPOMEligibleMaterial =
    ORION_GLASS_POM != 0 &&
    isGlassSurface &&
    !isFlatGlassWindow;

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

    // =====================================================
    // 2026-10-28：POM 掠射角衰减（只在 ORION_GLASS_POM=1 时生效）
    //
    // 越界块的根因是偏移量随 1/viewZ 爆炸。这里按 |N·V| 把 UV 与三个深度量
    // 连续插值回"平贴图"，让掠射面不再有硬 break。正视区（> 0.55）恒 1
    // ⇒ 浮雕保留原样。
    //
    // ⚠ 不动共享的 traceParallax：它同时服务不透明体（石头/木头浮雕），
    //   那里掠射强视差是对的，全局削平是错的。
    // =====================================================
#ifndef ORION_GLASS_POM_GRAZING_FADE
#define ORION_GLASS_POM_GRAZING_FADE 1
#endif
#ifndef ORION_GLASS_POM_GRAZING_FADE_LO
#define ORION_GLASS_POM_GRAZING_FADE_LO 0.15
#endif
#ifndef ORION_GLASS_POM_GRAZING_FADE_HI
#define ORION_GLASS_POM_GRAZING_FADE_HI 0.55
#endif

#if ORION_GLASS_POM_GRAZING_FADE
    {
        const float glassPomFacing =
            clamp(abs(dot(normalize(geoNormal), normalize(rayDirection))), 0.0, 1.0);
        const float glassPomStrength =
            smoothstep(
                ORION_GLASS_POM_GRAZING_FADE_LO,
                ORION_GLASS_POM_GRAZING_FADE_HI,
                glassPomFacing);

        if(glassPomStrength < 1.0)
        {
            texCoord =
                mix(basePbrTexCoord, parallaxHit.uv, glassPomStrength);
            parallaxHit.depth01 *= glassPomStrength;
            parallaxHit.depthWorld *= glassPomStrength;
            parallaxHit.rayTravelWorld *= glassPomStrength;
        }
    }
#endif
}


// =====================================================
// 玻璃连接纹理（CTM）—— 边框抑制
//
// 原版玻璃 sprite 的最外一圈 texel 是不透明的"框"。每个方块各画一圈，
// 相邻玻璃之间就多出一条亮线，整片玻璃看起来是格子。资源包的 OptiFine
// CTM 图集（optifine/ctm/**_glass*）做的正是"把与邻居相接的那条边的最外
// 圈去掉"，但那张图集不在 RT 的 blockAtlas 里（blockAtlas 是原版 blocks.png，
// MC 本身忽略 optifine/ctm）。
//
// 这里用同一套语义、不碰图集：Java 在截面编译时按方块邻居算出四条 sprite
// 边哪些与玻璃相接，掩码经顶点 pad2 传进来；命中的边就把采样坐标向内挪
// 一个 texel —— 相当于采到边框内侧的像素。区域外框按原样保留，只有区块
// 之间接缝消失。
//
// 位（对齐 SectionCompilerMixin 的 GLASS_CONNECT_MASK_*）：
//   1 = sprite u-min 边   2 = u-max 边   4 = v-min 边   8 = v-max 边
// pad2 为 0（非玻璃族、voxy / DH LOD、实体）时整段跳过。bit 4 是上面那段
// 的平窗标志，这里按 & 15 取邻接掩码，因此平窗玻璃照样能去掉相邻边框。
// =====================================================
if(
    isGlassSurface &&
    !isVoxyLod &&
    !isDhLod
)
{
    int glassConnectMask =
        glassVertexFlags &
        15;

    if(glassConnectMask != 0)
    {
        vec2 glassSpriteTexels =
            spriteSpan *
            atlasSize;

        vec2 glassOneTexel =
            1.0 /
            max(glassSpriteTexels, vec2(1.0));

        vec2 glassLocalUv =
            (texCoord - spriteMin) /
            spriteSpan;

        if((glassConnectMask & 1) != 0 && glassLocalUv.x < glassOneTexel.x)
        {
            glassLocalUv.x += glassOneTexel.x;
        }

        if((glassConnectMask & 2) != 0 && glassLocalUv.x > 1.0 - glassOneTexel.x)
        {
            glassLocalUv.x -= glassOneTexel.x;
        }

        if((glassConnectMask & 4) != 0 && glassLocalUv.y < glassOneTexel.y)
        {
            glassLocalUv.y += glassOneTexel.y;
        }

        if((glassConnectMask & 8) != 0 && glassLocalUv.y > 1.0 - glassOneTexel.y)
        {
            glassLocalUv.y -= glassOneTexel.y;
        }

        texCoord =
            spriteMin +
            glassLocalUv *
            spriteSpan;
    }
}


// =====================================================
// IMPORTANT:
// From this point onward ALL material textures use
// the SAME displaced texCoord.
// =====================================================

// The exact-fetch probe removed the reported water-receiver blur/misalignment.
// Apply that confirmed sampling rule only to opaque receivers reached through
// water or generic glass.  Normal primary/reflection/GI paths retain their
// prior filtering.
//
// 2026-10-09（用户报"普通玻璃块和玻璃板的贴图像被放大、糊成一团（纹素被插值
// 开），uv 有问题"）：把这条**精确取样**口径用到**清玻璃自己的表面**上。
//
// 机理：命中着色器里没有导数可用（隐式 LOD 未定义，见 rmiss 的品红幕布教训），
// 所以这里一律写死 textureLod(..., 0.0)；而原版 block atlas 的放大过滤是
// NEAREST、这条路径实际走的是线性（就是上面那句 "water-receiver blur" 的同
// 一个来源）⇒ 16×16 的玻璃贴图被双线性插值糊开。玻璃的花纹正好是"一像素边框
// + 全透明心"，被插值后边框变成一圈半透明渐变、纹理像被放大过 —— 只有它一眼
// 看得出来，所以用户报的是玻璃。texelFetch 取最近纹素 = 原版那种锐利的像素
// 观感；alpha 同时回到 0/1 二值，rgen 的 mix(背景, 贴图, glassAlpha) 因此不再
// 有半透明糊边（连接纹理的向内挪一纹素也还在，见上面那段 CTM）。
//
// ⚠ 2026-10-09 用户二次确认："不是只处理普通玻璃吗，你怎么把其他彩色玻璃也
//   变成原版了" ⇒ 精确取样**只给清玻璃**，判据复用 glass_tint_common.glsl 的
//   单一真源，不另立规则：
//     · 清玻璃块 / 清玻璃板（marker 2）：顶点色是原版四种灰面明暗之一
//       （0.498/0.6/0.8/1.0）⇒ 命中 —— 这正是用户要修的两个；
//     · 染色玻璃 / 染色板（marker 3..18）：native 把 DyeColor 精确写进顶点色
//       ⇒ glassVertexFactorIsFaceShade 为假 ⇒ **保持原来的过滤采样**（用户
//       明确要求不动它）。四种灰染料（白/淡灰/灰/黑）虽然也是灰色，但都不在
//       上面那四档里 ⇒ 同样走过滤（该判据的注释里已逐位论证过）。
//     · 遮光玻璃 2.1 / 冰 2.2 / 粘液 2.3 / 蜂蜜 2.4 由 materialId < 2.05 排除；
//       传送门 6.0 本来就不在 isGlassSurface 内。
bool useExactTransparentReceiverAlbedo =
    !isWaterMaterial &&
    (
        isWaterMediumTransport ||
        isTransparentReceiverTransport ||
        (
            isGlassSurface &&
            materialId < 2.05 &&
            glassVertexFactorIsFaceShade(interpColor.rgb)
        )
    );

vec4 albedoColor;

// The sprite alone, before the vertex factor is folded in.  On a translucent
// quad the vertex factor is either Minecraft's grey face shade (which must keep
// multiplying -- that is the near-field model) or the exact DyeColor that
// native wrote over it for a stained pane.  The glass transmission filter has
// to tell those two apart, hence the separate sample; see
// glassPaneTransmissionColor() in glass_tint_common.glsl.
vec4 atlasSpriteSample;

if(useExactTransparentReceiverAlbedo)
{
    vec2 exactAlbedoUV =
        clamp(
            texCoord,
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

    atlasSpriteSample =
        texelFetch(blockAtlas, albedoTexel, 0);
}
else
{
    atlasSpriteSample =
        textureLod(
            blockAtlas,
            texCoord,
            0.0
        );
}

albedoColor =
    atlasSpriteSample *
    interpColor;

vec2 pbrTexCoord =
    clamp(
        texCoord,
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
    normalSample =
        textureLod(
            normalAtlas,
            pbrTexCoord,
            0.0
        );
}
// 平窗档把整条 LabPBR 通道当成不存在：法线不偏、AO 不乘、高度场清零，
// 只剩 sprite 本身。下面 materialAO / heightDepth / pomSelfShadow 都读这个
// 布尔量，所以一处退档就够。
bool hasNormalData =
    hasPBRNormalData &&
    !isFlatGlassWindow;


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
            0.0
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

#if WATER_FIELD_DEBUG
    // =====================================================
    // 水交互场调试可视化（临时诊断用，正常发布置 0）：
    //   R = 场高度 h        (0.5 + h*0.5, 饱和截断)
    //   G = 梯度 x          (0.5 + gx*0.5)
    //   B = 梯度 y          (0.5 + gy*0.5)
    // simulationValid == 0 时显示暗灰 (0.08,0.08,0.1)。
    // 预期正常图案：玩家附近平滑同心环，远场平静。
    // 若出现噪点/棋盘/规则格纹 => 交互场本身损坏。
    // =====================================================
    {
        float dbgSurfaceY = cam.waterSimSurfaceAndOrigin.x;
        float dbgMatch = 1.0 - smoothstep(
            0.65, 1.50, abs(geometricHitPosition.y - dbgSurfaceY)
        );
        float dbgTexel = cam.waterSimSurfaceAndOrigin.w;
        vec2 dbgUV = (geometricHitPosition.xz - cam.waterSimSurfaceAndOrigin.yz) /
            (dbgTexel * 512.0);
        float dbgValid = dbgMatch *
            float(cam.waterSimState.y != 0u) *
            smoothstep(0.0, 0.025, dbgUV.x) *
            smoothstep(0.0, 0.025, dbgUV.y) *
            (1.0 - smoothstep(0.975, 1.0, dbgUV.x)) *
            (1.0 - smoothstep(0.975, 1.0, dbgUV.y));
        vec3 dbgColor = vec3(0.08, 0.08, 0.10);
        if(dbgValid > 0.0 && dbgTexel > 0.0)
        {
            float dbgLayer = float(cam.waterSimState.x);
            vec2 dbgStep = vec2(1.0 / 512.0);
            float dbgH = texture(
                waterSimulationState, vec3(dbgUV, dbgLayer)).r;
            float dbgHL = texture(
                waterSimulationState, vec3(dbgUV - vec2(dbgStep.x, 0.0), dbgLayer)).r;
            float dbgHR = texture(
                waterSimulationState, vec3(dbgUV + vec2(dbgStep.x, 0.0), dbgLayer)).r;
            float dbgHD = texture(
                waterSimulationState, vec3(dbgUV - vec2(0.0, dbgStep.y), dbgLayer)).r;
            float dbgHU = texture(
                waterSimulationState, vec3(dbgUV + vec2(0.0, dbgStep.y), dbgLayer)).r;
            vec2 dbgGrad = vec2(dbgHR - dbgHL, dbgHU - dbgHD) *
                (0.19 / max(dbgTexel, 1.0e-4));
            dbgColor = clamp(vec3(
                0.5 + dbgH * 0.5,
                0.5 + dbgGrad.x * 0.5,
                0.5 + dbgGrad.y * 0.5), 0.0, 1.0);
        }
        payload.hitDistance = hitT;
        payload.parallaxRayTravel = 0.0;
        payload.pomSelfShadow = 1.0;
        payload.pomMicroOcclusion = 1.0;
        payload.normal = vec3(0.0, 1.0, 0.0);
        payload.albedo = dbgColor;
        payload.glassAlpha = 1.0;
        payload.state = makeHitState(
            getRayPurpose(payload.state), RT_HIT_OPAQUE,
            RT_WATER_NONE, false, false, 0u);
        payload.roughness = 1.0;
        payload.f0Metal = 0.0;
        payload.emission = vec3(0.0);
        return;
    }
#endif
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
payload.pomMicroOcclusion =
    clamp(
        1.0 - parallaxHit.depth01 * 0.5,
        0.5,
        1.0
    );


// 2026-10-08 定位改动（玻璃模糊 / 连接纹理失效）：
// rgen 侧 isClearGlassHit() 走的是 mix(背景, 玻璃贴图, glassAlpha)，要求这里的
// alpha 是**每纹素自己的 alpha**（边框/条纹 ≈1，中间透明处 = 0）。原写法取的是
// albedoColor.a = 精灵.a × 顶点色.a —— 一旦顶点色 alpha ≠ 1，整块玻璃的 alpha 被
// 整体缩放，中间本该全透的地方被抬起 ⇒ 表现就是"糊一层半透明膜 + 边框/连接纹理消失"。
// 这里先只取精灵 alpha 做对照：
//   · 玻璃立刻清晰、连接纹理回来 ⇒ 断点确认是"乘了顶点色 alpha"，再按语义定最终口径；
//   · 没有变化 ⇒ 断点在 POM/UV 那一侧，本行改回 albedoColor.a 即可（逐位回到原状）。
// 2026-10-26：a 的权威来源是 albedoColor.a = 贴图 texel alpha x 顶点色 alpha，
// 与 Remix-MC 的 ATLAS_TRANSLUCENT_BLEND = textureTimesVertexColor() 逐字一致
// （_ref/rtx-remix-minecraft/.../texture/BlockTextureManager.java:64）。
// 旧写法只取 atlasSpriteSample.a，漏了顶点色 alpha。
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
        : 1.0;

// alpha blend 的源 alpha：无条件 texture x vertex（见 hit_payload.glsl 的说明）。
// 实测染色玻璃贴图 alpha = 102/255 = 0.400；这里不再受顶点 materialId 判定影响。
payload.glassBlendAlpha =
    clamp(albedoColor.a, 0.0, 1.0);

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

const bool t12IsWater = false;


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


if(t12IsGlass)
{
    // The pane's transmission filter, with the pigment applied exactly ONCE.
    //
    // albedoColor above is sprite x vertex, and on a stained pane BOTH factors
    // carry the same dye: vanilla already paints the sprite in it, and native
    // writes the exact DyeColor over the vertex colour.  Multiplying the two
    // tinted the pane twice -- measured against the vanilla sprites and the
    // DyeColor table that is 1.0x too dark for white, 2.3x for red, 2.8x for
    // blue, 3.4x for gray and 9.2x for black, driving the two minor channels of
    // a dark dye to ~0.  That is the "coloured glass looks almost black and
    // barely tinted" symptom, and it also drops the transmission into the same
    // order as the pane's own 4% uncoloured mirror reflection, which is why the
    // scene behind it then reads as an untinted reflection.
    //
    // The voxel DDA arm of the same model (glassDecodeVoxelAlbedo) and the Java
    // voxel word both read the dye once, so this also puts every arm of one pane
    // back onto one filter.  See glassPaneTransmissionColor() in
    // glass_tint_common.glsl for why the vertex factor can be classified as
    // exactly "a DyeColor" or "one of Minecraft's four grey face shades".
    //
    // The pane's own surface radiance does not read this field as a diffuse
    // albedo (the camera glass path consumes it only as the tint and the RR
    // guide pins rrDiffuseAlbedo to 0 for a pane), so overriding it here changes
    // the filter and nothing else.
    payload.albedo =
        glassPaneTransmissionColor(
            atlasSpriteSample.rgb,
            interpColor.rgb
        );

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

    // 2026-10-07（用户要求）：**普通玻璃当空气**。
    //
    // 判据用玻璃染色那套的现成分界：顶点因子是原版四个灰面着色之一 ⇒ 没有注入染料
    // ⇒ 普通玻璃。glass_tint_common.glsl:67-74 的实测注释正是拿 marker 2 的 clear
    // pane 量的（(0.498,0.498,0.498) / (0.6,0.6,0.6) / (1,1,1)）；染色玻璃注入的是
    // DyeColor firework RGB —— 连四个灰色染料（白/浅灰/灰/黑）都落在那四个灰**之外**，
    // 所以不会误判（同文件 63-65 行）。
    //
    // 必须写在 makeHitState 之后：它把 state 整个重建，先置位会被抹掉。
    // 消费者见 rgen 的 isClearGlassHit() / tracePrimaryGenericTransparentRadiance。
    // 回退：rgen 侧 GI_CLEAR_GLASS_AS_AIR=0（本文件这一位留着也无害）。
    //
    // ⚠ 2026-10-09 用户："除了普通玻璃以外**全量**恢复成原来有折射有反射的样子"。
    //   光靠"顶点色是灰面明暗"不够 —— 下面这些的顶点色同样是灰面明暗、却没有注入
    //   染料，于是被一起判成"普通玻璃当空气"：
    //     · 染色玻璃**块**（2026-10-09 前 Java 侧给它们写的是 marker 2，native 因此
    //       没注入染料；已由 BlockModelLighterMixin 修为 3+dyeId）
    //     · 遮光玻璃 2.1 / 冰 2.2 / 粘液 2.3 / 蜂蜜 2.4
    //   ⇒ 加 `materialId < 2.05` 把后一档挡在外面，"当空气"只留给普通玻璃块/板。
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

#endif // MATERIAL_RESOLVE_GLASS_GLSL
