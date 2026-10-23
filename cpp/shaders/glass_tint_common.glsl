// =============================================================================
// glass_tint_common.glsl
//
// Single source of truth for the local-light stained-glass model, shared by
// rt_test.rgen (legacy VPTRIS direct lighting + secondary bounce) and
// rt_restir_final.rgen (ReSTIR-DI estimate / visibility pass).
//
// Why this file exists
// --------------------
// Both shaders used to carry their own copy of this model, held together by a
// hand-written "Must mirror ... exactly" comment.  They drifted: the extra
// absorption exponent was 0.0 in rt_test.rgen (the 2026-09-22 fix for the
// double-counted pane absorption) and 1.0 in rt_restir_final.rgen (the old
// value it was copied from).  The two pools of light behind coloured glass were
// therefore ~15x apart in energy, which is exactly the divergence the 09-22 fix
// was supposed to remove.  Every constant and every function the two paths must
// agree on now lives here, so that class of drift cannot come back.
//
// Contract with the including shader
// ----------------------------------
//   * `bool vptReadSourceVoxel(ivec3 worldBlock, out uint source)` is the
//     per-voxel page-table lookup.  Both shaders define it identically; the
//     prototype is declared below so this file can be included before that
//     definition (GLSL single-pass compilation).
//   * Nothing in this file touches a binding, a payload or a push constant
//     directly, so it is safe to include from the top of either shader.
//   * Judgement-experiment switch boxes (DBG_*) stay in the including shader;
//     they wrap these functions rather than living inside them.
//   * The per-pane *physical* model is the RT_PURPOSE_GLASS_VISIBILITY block in
//     rt_test.rahit: the Orion thin-sheet transmission of every traversed
//     pane times the dense-body factor of the tinted-glass / ice / slime / honey
//     profiles.  One Any-Hit shader serves the sun, AO, rain and local-light
//     visibility rays, so that half cannot fork.  What lives here is the
//     voxel-word / tint-shaping half -- the dye decode, the saturation boost and
//     the exposure guard -- which is what a shader needs when it is *not*
//     issuing the ray (the DDA arm) or when it has to shape the transmission the
//     ray handed back.
// =============================================================================

// Java writes this bit (SectionCompilerMixin: VLI_STAINED_GLASS_BIT = 1 << 24)
// on the same voxel word whose bits 9..23 carry the quantized RGB555 dye.
const uint VPT_STAINED_GLASS_BIT = 1u << 24u;

bool vptReadSourceVoxel(ivec3 worldBlock, out uint source);

// =============================================================================
// The pane's chromatic transmission filter -- the pigment applied exactly ONCE
//
// Two independent sources carry the same pigment for a stained pane:
//
//   * the block sprite, which vanilla already paints in the dye colour
//     (red_stained_glass.png averages (153, 51, 51)),
//   * the exact DyeColor firework RGB that the Java/native side writes into the
//     vertex colour (kStainedGlassDyeRgbById, red = (179, 49, 44)), because the
//     LOD arm and this file's own voxel DDA have no sprite and still need to
//     know the dye.
//
// The vertex factor of a translucent quad is normally Minecraft's grey face
// shade, which is NOT a pigment and must keep multiplying (the near-field model
// is sprite x vertex everywhere else).  Minecraft bakes exactly four such
// shades -- up 1.0, north/south 0.8, east/west 0.6, down 0.5 -- and they are
// grey to the bit, so "grey and outside that set" identifies the injected dye
// exactly.  That distinction matters for the four greyscale dyes: white
// (0xF0F0F0), light gray, gray (0x434343) and black (0x1E1B1B) are grey too, and
// if they were classified as shades their panes would stay 1.5x-3.4x too dark.
//
// The vertex factor this has to classify was measured, not assumed: native dumps
// of clear panes (marker 2, the one case with no dye injected) show the untouched
// factor is exactly (0.498039,0.498039,0.498039), (0.6,0.6,0.6) or (1,1,1) --
// 127/255, 153/255 and 255/255, i.e. those same grey face shades and nothing
// else.  Ambient occlusion is NOT in the vertex colour on this version
// (BlockModelLighter folds it into the light coords), so the four-shade test
// below is exact rather than approximate, and a clear pane keeps its sprite
// pattern instead of being flattened by the min().
//
// Multiplying the two counts that pigment twice.  Measured against the vanilla
// sprites and the DyeColor table the transmitted luminance comes out 1.0x too
// dark for white, 2.3x for red, 2.8x for blue, 3.4x for gray and 9.2x for
// black: the darker the dye the worse, because the product squares the
// absorption of the two minor channels (blue fell from (0.20,0.30,0.70) to
// (0.029,0.057,0.400)).  Both visible symptoms follow from that one number --
// the pane itself reads as "very dark", and its transmission drops to the same
// order as the 4% uncoloured mirror reflection the same pane adds, so what is
// seen through it is mostly the scene's own reflection and the tint disappears.
//
// The voxel DDA arm reads the dye once (glassDecodeVoxelAlbedo) and the Java
// voxel word carries that same single dye, so the double count also left the
// arms of one pane at odds with each other.  The sprite supplies the spatial
// pattern and the injected dye supplies the exact hue; neither may be counted
// twice, hence the per-channel min.
// =============================================================================
bool glassVertexFactorIsFaceShade(vec3 factor)
{
    float grey = factor.r;
    if(abs(factor.g - grey) > (1.0 / 255.0) ||
        abs(factor.b - grey) > (1.0 / 255.0))
    {
        return false;
    }

    return abs(grey - 1.0) < 0.01 ||
        abs(grey - 0.8) < 0.01 ||
        abs(grey - 0.6) < 0.01 ||
        abs(grey - 0.5) < 0.01;
}

vec3 glassPaneTransmissionColor(vec3 spriteRgb, vec3 vertexRgb)
{
    vec3 sprite = clamp(spriteRgb, vec3(0.0), vec3(1.0));
    vec3 vertex = clamp(vertexRgb, vec3(0.0), vec3(1.0));

    if(glassVertexFactorIsFaceShade(vertex))
    {
        return sprite * vertex;
    }

    return min(sprite, vertex);
}

// =============================================================================
// Voxel word -> dye colour
//
// VPT-1.3 source voxel fields.  For an exact stained pane Java OVERRIDES the
// RGB555 with the same vanilla DyeColor firework RGB that native writes into the
// vertex colour -- SectionCompilerMixin quantizes DyeColor.getFireworkColor()
// into r5/g5/b5 and raises VLI_STAINED_GLASS_BIT on the same word -- so this
// decode returns the dye exactly ONCE, matching the Any-Hit pane filter above.
// Every other block keeps the per-voxel albedo Java accumulated from the block
// atlas at that quad's centre UV.  (The earlier note here claimed the RGB555 was
// always an atlas sprite average; that is true only for the non-dye case.)
// =============================================================================
vec3 glassDecodeVoxelAlbedo(uint source)
{
    return vec3(
        float((source >> 9u) & 31u),
        float((source >> 14u) & 31u),
        float((source >> 19u) & 31u)
    ) * (1.0 / 31.0);
}

// =============================================================================
// Peak-preserving saturation boost of the stained-glass RGB filter
//
// 3.0 matches the confirmed rt_vpt_irradiance.comp bake filter: the maximum
// channel is kept and only the channel differences are amplified, so dye
// concentration rises without changing local-light energy or range.
// =============================================================================
const float STAINED_GLASS_TRANSMISSION_SATURATION = 3.0;

vec3 vptSaturateGlassTransmission(vec3 transmission)
{
    float peak = max(transmission.r, max(transmission.g, transmission.b));
    return clamp(
        vec3(peak) +
            (transmission - vec3(peak)) *
            STAINED_GLASS_TRANSMISSION_SATURATION,
        vec3(0.0),
        vec3(peak)
    );
}

// =============================================================================
// LOCAL-LIGHT stained-glass tint strengthening
//
// The visibility path is a plain multiplicative transmittance, so a bright
// local emitter washes its own colour out: the dominant channel is pushed past
// the tone curve and the illuminated pool reads as white even through saturated
// stained glass.  This boost re-saturates the transmittance while restoring its
// luminance, so a coloured pool keeps the exact brightness it had and only
// gains colour.
//
// Scope is deliberately local-light only.  Sun / sky shadow transmittance is
// accumulated by the separate RT_PURPOSE_GLASS_VISIBILITY block in main() and
// is never passed through this function.
// =============================================================================
const float LOCAL_GLASS_TINT_SATURATION = 1.55;
const vec3 LOCAL_GLASS_TINT_LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 strengthenLocalGlassTint(
    vec3 transmittance
)
{
    vec3 t =
        clamp(transmittance, vec3(0.0), vec3(1.0));

    float peak =
        max(t.r, max(t.g, t.b));

    // Achromatic (clear glass) transmittance is already its own boost result.
    if(peak <= 1.0e-4)
    {
        return t;
    }

    vec3 chroma =
        clamp(
            mix(
                vec3(1.0),
                t / peak,
                LOCAL_GLASS_TINT_SATURATION
            ),
            vec3(0.0),
            vec3(1.0)
        );

    // Restore the original luminance so the boost never brightens or darkens
    // the pool, it only moves the hue.  The final clamp keeps a saturated
    // channel inside the valid transmittance range.
    float targetLuminance =
        dot(t, LOCAL_GLASS_TINT_LUMA);

    float chromaLuminance =
        dot(chroma, LOCAL_GLASS_TINT_LUMA);

    return
        clamp(
            chroma *
            (targetLuminance / max(chromaLuminance, 1.0e-4)),
            vec3(0.0),
            vec3(1.0)
        );
}

// =============================================================================
// Hue-preserving exposure guard for the local-light glass tint
//
// The local-light terms are HDR by construction.  rr_sdr_post.comp ends with a
// bare pow(color, 1/2.2) and has no tone curve, so it clamps every channel on
// its own: a red-glass sample of (2.3, 0.6, 0.6) has its strongest channel
// pinned at 1.0 and washes out to white -- the channel ratios, i.e. the glass
// colour, collapse exactly at the clip point.  That is why the local-light pool
// behind stained glass reads white and gets whiter the closer (hence brighter)
// the receiver is, while the sun paths, whose energy stays below 1.0, never
// clip and look correct.
//
// Re-saturating the *transmittance* cannot recover that: the product is already
// out of range by the time the sink sees it.  Folding the peak back with ONE
// shared scalar keeps every channel ratio - and therefore the hue - identical,
// and values at or below 1.0 are returned untouched, so mid-tones and overall
// exposure do not move and the sun paths are not affected at all.
// =============================================================================
const float LOCAL_GLASS_TINT_NEUTRAL_SPREAD = 0.08;

// Extra absorption exponent applied ON TOP of strengthenLocalGlassTint's own
// luminance restoration.  1.0 double-counts the pane's absorption and leaves
// the coloured pool at ~1/15 the energy of the white pool, which is exactly
// what made the tint look invisible.  0.0 keeps the two paths at the same order
// of magnitude; raise it toward 1.0 only if a darker, more physical pool is
// wanted.  Measured 2026-09-22: this is the single knob that decided whether
// the two consumers of this model agreed, so it deliberately lives in exactly
// one place.
const float LOCAL_GLASS_TINT_ABSORPTION = 0.0;

vec3 glassTintExposureFold(vec3 value)
{
    float peak = max(value.r, max(value.g, value.b));
    if(peak <= 1.0)
    {
        return value;
    }
    return value * (1.0 / peak);
}

// The tinted pool *before* the exposure fold.  Kept separate so a readout can
// inspect the unclipped product (see DBG_GLASS_PEAK_READOUT in rt_test.rgen).
vec3 glassStainedPoolRadiance(vec3 radiance, vec3 transmittance)
{
    vec3 paneTransit =
        clamp(transmittance, vec3(0.0), vec3(1.0));
    vec3 tint =
        strengthenLocalGlassTint(paneTransit);

    float tintPeak = max(tint.r, max(tint.g, tint.b));
    float tintFloor = min(tint.r, min(tint.g, tint.b));
    if(tintPeak - tintFloor <= LOCAL_GLASS_TINT_NEUTRAL_SPREAD)
    {
        return max(radiance, vec3(0.0)) * tint;
    }

    // A stained pane absorbs energy as well as rotating hue.  Without this the
    // hue guard alone leaves a bright local emitter's pool at, or above, the
    // white point - which is exactly the wash-out this filter exists to stop,
    // because the sink clips per channel and the ratios collapse.
    //
    // 2026-09-22 fix: strengthenLocalGlassTint() already calibrated the tint
    // back to the original transmittance luminance, i.e. "how much energy the
    // pane ate" is already inside `tint`.  Multiplying a second absorption on
    // top of it counted the same absorption twice and dropped the coloured pool
    // to ~1/15 of the white pool, which buried the colour.  The two paths are
    // level again because this exponent is 0.0 (see the constant above).
    return
        max(radiance, vec3(0.0)) *
        tint *
        pow(
            max(dot(paneTransit, LOCAL_GLASS_TINT_LUMA), 0.0),
            LOCAL_GLASS_TINT_ABSORPTION
        );
}

// Full local-light stained-glass filter: tint, absorption and the exposure
// guard applied together.  Callers that need the pre-fold value should use
// glassStainedPoolRadiance() instead.
vec3 glassApplyStainedTint(vec3 radiance, vec3 transmittance)
{
    return glassTintExposureFold(
        glassStainedPoolRadiance(radiance, transmittance)
    );
}

// The march below calls vptReadSourceVoxel(), which only the raygen-family
// shaders define.  Hit shaders include this file (for the pane filter above)
// with GLASS_TINT_NO_DDA defined, so the march and its unresolved callee stay
// out of their translation unit entirely.
#ifndef GLASS_TINT_NO_DDA

// =============================================================================
// VXL-2 voxel DDA transmission
//
// The tint-only arm of the local-light connection: the fallback selected by
// -DLOCAL_DI_FINAL_VISIBILITY_RAY=0 in rt_restir_final.rgen, and still the live
// path for rt_test.rgen's glass-receiver RIS.
//
// Why it exists: the first hardware visibility attempt on the ReSTIR side
// returned constant zero.  The cause is worth stating precisely, because it was
// *not* the sentinel.  The ray seeds payload.hitDistance to 1.0 and relies on
// the miss shader to set it to -1.0 -- rt_test.rmiss does exactly that, and the
// sun / AO / rain visibility rays have always read the field that way.  What was
// broken was the wiring: that call selected SBT record offset 5 in a hit region
// holding four records, miss index 1 in a one-record miss region, and payload
// location 1, which only rt_local_visibility.rahit / _translucent.rahit /
// .rmiss ever write -- and none of the three is loaded into the pipeline.  The
// seed therefore survived every trace, `>= 0.0` was unconditionally true, and
// the caller returned vec3(0.0) on every pixel.  The whole tint pipeline
// (strengthenLocalGlassTint, glassApplyStainedTint, the exposure guard) never
// ran, and the only local light left on a receiver was the emitter's own canned
// self-glow, which is fixed-colour and never filtered: the visible "light
// through coloured glass is still white".
//
// This march's own advantage is that it reads the dye straight out of the voxel
// word: Java writes RGB555 into bits 9..23 and raises VPT_STAINED_GLASS_BIT
// (bit 24) on the same word, and marks the pane VLI_VOXEL_TRANSLUCENT (class 2),
// so `(source & 3u) == 1u` is false and the pane never occludes -- it only
// multiplies the filter.
//
// Its limitation is the mirror image: it accumulates the dye filter *only*.  It
// performs NO occlusion test, so an opaque wall between the receiver and the
// selected emitter does not stop the term, and it knows nothing about the
// dense-body profiles (tinted glass / ice / slime / honey), which live in the
// vertex material ID rather than in the voxel word.  Those two are exactly what
// traceLocalLightVisibility() gets from the shared Any-Hit contract, which is
// why rt_restir_final.rgen prefers it and this march is kept as the ablation arm.
//
// Only stained panes matter, so a missing page-table entry is skipped like air
// rather than terminating the march: an unloaded Section must never cut the term
// (the hard Section-grid seams this project already removed elsewhere).  The
// march starts at the receiver origin and stops 0.03 short of the emitter voxel
// entry, matching the ray interval the visibility ray uses.
// =============================================================================
vec3 glassTraceTransmissionToEmitter(
    vec3 origin,
    vec3 target,
    float targetVoxelEntryDistance
)
{
    vec3 segment = target - origin;
    float distanceToTarget = length(segment);
    if(distanceToTarget <= 0.03)
    {
        return vec3(1.0);
    }

    // The selected light is represented by a voxel but sampled as a virtual
    // area emitter.  Stop just before entering that source voxel so its own
    // opaque mesh cannot shadow its emitted sample.
    float rayMaxDistance = min(
        distanceToTarget - 0.03,
        targetVoxelEntryDistance - 0.03
    );
    if(rayMaxDistance <= 0.02)
    {
        return vec3(1.0);
    }

    vec3 direction = segment / distanceToTarget;
    ivec3 cell = ivec3(floor(origin));
    ivec3 step = ivec3(sign(direction));
    vec3 invAbs = 1.0 / max(abs(direction), vec3(1.0e-6));
    vec3 boundary = vec3(cell) + vec3(max(step, ivec3(0)));
    vec3 tMax = (boundary - origin) / direction;
    if(step.x == 0) tMax.x = 1.0e30;
    if(step.y == 0) tMax.y = 1.0e30;
    if(step.z == 0) tMax.z = 1.0e30;

    int maxTraversal = min(
        96,
        int(ceil(distanceToTarget * dot(abs(direction), vec3(1.0)))) + 2
    );
    vec3 glassTransmission = vec3(1.0);

    for(int traversal = 0; traversal < maxTraversal; ++traversal)
    {
        int axis = tMax.x < tMax.y
            ? (tMax.x < tMax.z ? 0 : 2)
            : (tMax.y < tMax.z ? 1 : 2);
        float travel = axis == 0 ? tMax.x : (axis == 1 ? tMax.y : tMax.z);
        if(travel >= rayMaxDistance)
        {
            break;
        }

        if(axis == 0)
        {
            cell.x += step.x;
            tMax.x += invAbs.x;
        }
        else if(axis == 1)
        {
            cell.y += step.y;
            tMax.y += invAbs.y;
        }
        else
        {
            cell.z += step.z;
            tMax.z += invAbs.z;
        }

        uint source;
        if(!vptReadSourceVoxel(cell, source))
        {
            continue;
        }

        if((source & VPT_STAINED_GLASS_BIT) != 0u)
        {
            glassTransmission *=
                max(glassDecodeVoxelAlbedo(source), vec3(1.0 / 31.0));
        }
    }

    return clamp(
        vptSaturateGlassTransmission(glassTransmission),
        vec3(0.0),
        vec3(1.0)
    );
}

#endif // GLASS_TINT_NO_DDA
