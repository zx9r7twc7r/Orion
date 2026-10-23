package com.example.client.mixin;

import com.example.client.access.OmmCutoutClassifier;
import com.example.client.access.PBRAtlasStorage;
import com.example.client.access.SpriteContentsExtension;
import com.example.client.EntityRtCapture;
import com.example.client.ParticleRtCapture;
import com.example.client.mixin.SpriteContentsAccessor;
import com.example.vulkan.NativeVulkan;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

@Mixin(TextureAtlas.class)
public class TextureAtlasPBRMixin {

    // Fully rough, non-metallic, non-emissive dielectric.
    // LabPBR RGBA = smoothness, F0/metal, porosity/SSS, emission.
    // Byte layout is explicitly RGBA (see copyNormalWithAutoPomHeight).
    private static void fillDefaultSpecularAtlas(
            NativeImage destination,
            int width,
            int height
    ) {
        for (int py = 0; py < height; ++py) {
            for (int px = 0; px < width; ++px) {
                long destinationPixel =
                        destination.getPointer() +
                                (
                                        (
                                                (long) py *
                                                        destination.getWidth()
                                        ) +
                                                px
                                ) *
                                        4L;

                MemoryUtil.memPutByte(destinationPixel, (byte) 0);
                MemoryUtil.memPutByte(destinationPixel + 1L, (byte) 10);
                MemoryUtil.memPutByte(destinationPixel + 2L, (byte) 0);
                MemoryUtil.memPutByte(destinationPixel + 3L, (byte) 255);
            }
        }
    }

    // =====================================================
    // Auto-POM height normalization
    //
    // LabPBR normal alpha:
    //   alpha = 255 -> depth 0
    //   smaller alpha -> deeper
    //
    // Use robust 1% / 99% percentiles instead of raw min/max,
    // so isolated bad pixels do not explode the whole sprite.
    //
    // The supplied 256x height-bearing reference has:
    //   P01 = 193
    //   P99 = 245
    //   robust span = 52 alpha levels
    //
    // Treat that as "strong relief" = full current POM depth.
    // =====================================================

    private static final float
            POM_LOW_PERCENTILE = 0.01f;

    private static final float
            POM_HIGH_PERCENTILE = 0.99f;

    private static final int
            POM_MIN_ROBUST_ALPHA_SPAN = 4;

    private static final float
            POM_STRONG_RELIEF_ALPHA_SPAN = 52.0f;


    private static int percentileFromHistogram(
            int[] histogram,
            int sampleCount,
            float percentile
    ) {
        if (sampleCount <= 0) {
            return 255;
        }

        int target =
                Math.round(
                        percentile *
                                (sampleCount - 1)
                );

        int cumulative = 0;

        for (int value = 0; value < 256; ++value) {
            cumulative += histogram[value];

            if (cumulative > target) {
                return value;
            }
        }

        return 255;
    }


    private static float clamp01(float value) {
        return Math.max(
                0.0f,
                Math.min(
                        1.0f,
                        value
                )
        );
    }


    // =====================================================
    // Animated `_n` / `_s` textures are stored as frameCount square frames
    // stacked vertically, so the image height is width * frameCount while the
    // stitched sprite rectangle covers exactly ONE frame.
    //
    // Resampling against the whole strip folded every animation frame into one
    // sprite: nearestSourceCoordinate() walked 4 source rows per destination
    // row (prismarine: source 128x512 -> target 128x128), so frame 0..3 were
    // interleaved line by line.  The normal vectors and the LabPBR height alpha
    // were therefore garbage, which showed up as striped/smeared POM relief.
    //
    // Only the first frame is baked: this atlas is uploaded once per resource
    // reload, so it cannot follow the animation anyway.
    // =====================================================
    private static int sourceFrameHeight(NativeImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        if (width > 0 && height > width && height % width == 0) {
            return width;
        }

        return height;
    }


    private static int nearestSourceCoordinate(
            int destinationCoordinate,
            int destinationSize,
            int sourceSize
    ) {
        long centeredDestinationCoordinate =
                (long) destinationCoordinate * 2L + 1L;

        return Math.min(
                sourceSize - 1,
                (int) (
                        centeredDestinationCoordinate *
                                sourceSize /
                                ((long) destinationSize * 2L)
                )
        );
    }


    private static long pixelAddress(
            NativeImage image,
            int x,
            int y
    ) {
        return image.getPointer() +
                (
                        (
                                (long) y *
                                        image.getWidth()
                        ) +
                                x
                ) *
                        4L;
    }


    private static void logResampledPbrTexture(
            String spriteName,
            String textureKind,
            NativeImage source,
            int targetWidth,
            int targetHeight
    ) {
        if (
                source.getWidth() != targetWidth ||
                        source.getHeight() != targetHeight
        ) {
            System.out.println(
                    "[PBR ATLAS RESAMPLE] sprite=" +
                            spriteName +
                            " texture=" +
                            textureKind +
                            " source=" +
                            source.getWidth() +
                            "x" +
                            source.getHeight() +
                            " target=" +
                            targetWidth +
                            "x" +
                            targetHeight +
                            " filter=nearest"
            );
        }
    }


    private static void copyResampledRgba(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            String spriteName,
            String textureKind
    ) {
        logResampledPbrTexture(
                spriteName,
                textureKind,
                source,
                width,
                height
        );

        // Animated source: read a single frame instead of folding the whole
        // stacked strip into one sprite (see sourceFrameHeight).
        int sampledSourceHeight =
                sourceFrameHeight(source);

        for (int py = 0; py < height; ++py) {
            int sourceY =
                    nearestSourceCoordinate(
                            py,
                            height,
                            sampledSourceHeight
                    );

            for (int px = 0; px < width; ++px) {
                int sourceX =
                        nearestSourceCoordinate(
                                px,
                                width,
                                source.getWidth()
                        );

                MemoryUtil.memCopy(
                        pixelAddress(source, sourceX, sourceY),
                        pixelAddress(
                                destination,
                                dstX + px,
                                dstY + py
                        ),
                        4L
                );
            }
        }
    }

    // VPT-1.2/1.3: preserve the exact numerical space used by the current
    // UNORM block atlas.  This is deliberately not an sRGB conversion: the
    // closest-hit shader samples the same UNORM values today.
    private static void fillSpriteAverageAlbedo(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height
    ) {
        if (source == null || width <= 0 || height <= 0) {
            return;
        }

        long sumR = 0L;
        long sumG = 0L;
        long sumB = 0L;
        long sumA = 0L;

        for (int y = 0; y < source.getHeight(); ++y) {
            for (int x = 0; x < source.getWidth(); ++x) {
                long pixel = pixelAddress(source, x, y);
                int alpha = MemoryUtil.memGetByte(pixel + 3L) & 0xFF;
                sumR += (long) (MemoryUtil.memGetByte(pixel) & 0xFF) * alpha;
                sumG += (long) (MemoryUtil.memGetByte(pixel + 1L) & 0xFF) * alpha;
                sumB += (long) (MemoryUtil.memGetByte(pixel + 2L) & 0xFF) * alpha;
                sumA += alpha;
            }
        }

        int r = sumA == 0L ? 0 : (int) ((sumR + sumA / 2L) / sumA);
        int g = sumA == 0L ? 0 : (int) ((sumG + sumA / 2L) / sumA);
        int b = sumA == 0L ? 0 : (int) ((sumB + sumA / 2L) / sumA);

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                long pixel = pixelAddress(destination, dstX + x, dstY + y);
                MemoryUtil.memPutByte(pixel, (byte) r);
                MemoryUtil.memPutByte(pixel + 1L, (byte) g);
                MemoryUtil.memPutByte(pixel + 2L, (byte) b);
                MemoryUtil.memPutByte(pixel + 3L, (byte) 255);
            }
        }
    }


    private static void copyNormalWithAutoPomHeight(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            String spriteName
    ) {
        int[] alphaHistogram =
                new int[256];

        // Animated `_n`: only the first frame is baked into the atlas (see
        // sourceFrameHeight), so the height percentiles must come from that
        // same frame.  Keeping the whole strip here mixed frames that are
        // never sampled together.
        int sampledSourceHeight =
                sourceFrameHeight(source);

        int sampleCount =
                source.getWidth() *
                        sampledSourceHeight;


        for (int py = 0; py < sampledSourceHeight; ++py) {
            for (int px = 0; px < source.getWidth(); ++px) {
                long sourcePixel =
                        pixelAddress(source, px, py);

                // NativeImage.Format.RGBA is physically stored as:
                // byte 0 = R
                // byte 1 = G
                // byte 2 = B
                // byte 3 = A
                int alpha =
                        MemoryUtil.memGetByte(
                                sourcePixel + 3L
                        ) &
                                0xFF;

                ++alphaHistogram[alpha];
            }
        }


        int alphaP01 =
                percentileFromHistogram(
                        alphaHistogram,
                        sampleCount,
                        POM_LOW_PERCENTILE
                );

        int alphaP99 =
                percentileFromHistogram(
                        alphaHistogram,
                        sampleCount,
                        POM_HIGH_PERCENTILE
                );

        int robustSpan =
                alphaP99 -
                        alphaP01;


        // No meaningful height signal:
        // preserve the original normal image exactly.
        if (robustSpan < POM_MIN_ROBUST_ALPHA_SPAN) {
            copyResampledRgba(
                    source,
                    destination,
                    dstX,
                    dstY,
                    width,
                    height,
                    spriteName,
                    "_n"
            );

            return;
        }

        logResampledPbrTexture(
                spriteName,
                "_n",
                source,
                width,
                height
        );


        // Automatic material-specific relief.
        //
        // 52 alpha levels (the supplied strong 256x reference)
        // reaches the full existing POM depth.
        //
        // Smaller-span materials receive proportionally less
        // relief; larger-span materials clamp at full depth.
        float reliefStrength =
                clamp01(
                        robustSpan /
                                POM_STRONG_RELIEF_ALPHA_SPAN
                );


        for (int py = 0; py < height; ++py) {
            int sourceY =
                    nearestSourceCoordinate(
                            py,
                            height,
                            sampledSourceHeight
                    );

            for (int px = 0; px < width; ++px) {
                int sourceX =
                        nearestSourceCoordinate(
                                px,
                                width,
                                source.getWidth()
                        );

                long sourcePixel =
                        pixelAddress(
                                source,
                                sourceX,
                                sourceY
                        );

                int alpha =
                        MemoryUtil.memGetByte(
                                sourcePixel + 3L
                        ) &
                                0xFF;


                // alphaP99 = top / zero-depth reference.
                // alphaP01 = deep reference.
                //
                // At alphaP99 -> normalizedDepth = 0
                // At alphaP01 -> normalizedDepth = 1
                float normalizedDepth =
                        (
                                alphaP99 -
                                        alpha
                        ) /
                                (float) robustSpan;


                normalizedDepth =
                        clamp01(
                                normalizedDepth
                        );


                float finalDepth =
                        normalizedDepth *
                                reliefStrength;


                int remappedAlpha =
                        Math.round(
                                (
                                        1.0f -
                                                finalDepth
                                ) *
                                        255.0f
                        );


                long destinationPixel =
                        pixelAddress(
                                destination,
                                dstX + px,
                                dstY + py
                        );


                // Preserve RGB normal data bit-for-bit.
                // Copy the complete RGBA texel first, then replace only A.
                MemoryUtil.memCopy(
                        sourcePixel,
                        destinationPixel,
                        4L
                );


                MemoryUtil.memPutByte(
                        destinationPixel + 3L,
                        (byte) remappedAlpha
                );
            }
        }


        System.out.println(
                "[POM AUTO HEIGHT] sprite=" +
                        spriteName +
                        " size=" +
                        width +
                        "x" +
                        height +
                        " p01=" +
                        alphaP01 +
                        " p99=" +
                        alphaP99 +
                        " span=" +
                        robustSpan +
                        " relief=" +
                        String.format(
                                java.util.Locale.ROOT,
                                "%.3f",
                                reliefStrength
                        )
        );
    }


    @Inject(method = "upload", at = @At("HEAD"))
    private void onUploadPBR(
            SpriteLoader.Preparations preparations,
            CallbackInfo ci
    ) {
        TextureAtlas self =
                (TextureAtlas) (Object) this;

        // =====================================================
        // PBR Atlas must follow ONLY Minecraft's block atlas.
        //
        // Other atlases (items / particles / etc.) must never
        // overwrite the RT block Normal / Specular atlases.
        // =====================================================

        if (!TextureAtlas.LOCATION_BLOCKS.equals(self.location())) {
            return;
        }

        // The old block atlas and all resource-backed entity/particle views
        // are about to be replaced. Do this before any old NativeImage is
        // closed, so a reused Vulkan handle cannot suppress its re-publication.
        EntityRtCapture.invalidateTextureCache();
        ParticleRtCapture.invalidateTextureCache();

        // Same reason as those two, for the block atlas view itself: the bind
        // hook keeps the previous numeric handle to avoid re-pushing on every
        // terrain draw, and a reused handle value would read as "unchanged".
        PBRAtlasStorage.bumpAtlasEpoch();


        // Cache entries retain references to atlas regions, so invalidate
        // them before closing/replacing the backing NativeImages.
        OmmCutoutClassifier.clearCache();

        if (PBRAtlasStorage.globalNormalAtlas != null) {
            PBRAtlasStorage.globalNormalAtlas.close();
        }
        if (PBRAtlasStorage.globalSpecularAtlas != null) {
            PBRAtlasStorage.globalSpecularAtlas.close();
        }
        if (PBRAtlasStorage.globalVptAlbedoAtlas != null) {
            PBRAtlasStorage.globalVptAlbedoAtlas.close();
        }

        int atlasWidth = preparations.width();
        int atlasHeight = preparations.height();

        PBRAtlasStorage.globalNormalAtlas =
                new NativeImage(
                        NativeImage.Format.RGBA,
                        atlasWidth,
                        atlasHeight,
                        true
                );

        PBRAtlasStorage.globalSpecularAtlas =
                new NativeImage(
                        NativeImage.Format.RGBA,
                        atlasWidth,
                        atlasHeight,
                        true
                );

        PBRAtlasStorage.globalVptAlbedoAtlas =
                new NativeImage(
                        NativeImage.Format.RGBA,
                        atlasWidth,
                        atlasHeight,
                        true
                );

        // Never leave atlas regions uninitialized. A sprite without a valid
        // _s texture therefore has the confirmed default dielectric material:
        // RGBA = (0, 10, 0, 255).
        fillDefaultSpecularAtlas(
                PBRAtlasStorage.globalSpecularAtlas,
                atlasWidth,
                atlasHeight
        );


        final List<PBRAtlasStorage.OmmSpriteRegion> ommSpriteRegions =
                new ArrayList<>(preparations.regions().size());

        for (var entry : preparations.regions().entrySet()) {
            String spriteName =
                    entry.getKey().toString();

            TextureAtlasSprite sprite =
                    entry.getValue();

            SpriteContentsExtension ext =
                    (SpriteContentsExtension)
                            sprite.contents();

            NativeImage normalImg =
                    ext.fabric_getNormalImage();

            NativeImage specularImg =
                    ext.fabric_getSpecularImage();

            NativeImage diffuseImg =
                    ((SpriteContentsAccessor) (Object) sprite.contents())
                            .getOriginalImage();

            int atlasSpriteX =
                    Math.max(
                            0,
                            Math.min(
                                    atlasWidth - 1,
                                    Math.round(
                                            sprite.getU0() * atlasWidth
                                    )
                            )
                    );

            int atlasSpriteY =
                    Math.max(
                            0,
                            Math.min(
                                    atlasHeight - 1,
                                    Math.round(
                                            sprite.getV0() * atlasHeight
                                    )
                            )
                    );

            int atlasSpriteWidth =
                    Math.max(
                            1,
                            Math.min(
                                    atlasWidth - atlasSpriteX,
                                    Math.round(
                                            (sprite.getU1() - sprite.getU0()) *
                                                    atlasWidth
                                    )
                            )
                    );

            int atlasSpriteHeight =
                    Math.max(
                            1,
                            Math.min(
                                    atlasHeight - atlasSpriteY,
                                    Math.round(
                                            (sprite.getV1() - sprite.getV0()) *
                                                    atlasHeight
                                    )
                            )
                    );

            // The OMM classifier reads this exact source alpha and only
            // emits a known state when all three UV corners belong to this
            // physical atlas rectangle.  Dynamic sprites remain unknown.
            if (diffuseImg != null) {
                ommSpriteRegions.add(
                        new PBRAtlasStorage.OmmSpriteRegion(
                                sprite.getU0(),
                                sprite.getV0(),
                                sprite.getU1(),
                                sprite.getV1(),
                                diffuseImg,
                                sprite.isAnimated(),
                                PBRAtlasStorage.computeSeeThrough(diffuseImg)
                        )
                );
            }

            fillSpriteAverageAlbedo(
                    diffuseImg,
                    PBRAtlasStorage.globalVptAlbedoAtlas,
                    atlasSpriteX,
                    atlasSpriteY,
                    atlasSpriteWidth,
                    atlasSpriteHeight
            );


            if (normalImg != null) {
                // =================================================
                // AUTO material-level POM:
                //
                // Analyze each sprite independently and bake the
                // remapped LabPBR height directly into the alpha of
                // the RT normal atlas.
                //
                // RGB normal data stays unchanged.
                // No new GPU texture / descriptor / JNI argument.
                // =================================================

                copyNormalWithAutoPomHeight(
                        normalImg,
                        PBRAtlasStorage.globalNormalAtlas,
                        atlasSpriteX,
                        atlasSpriteY,
                        atlasSpriteWidth,
                        atlasSpriteHeight,
                        spriteName
                );
            }


            if (specularImg != null) {
                copyResampledRgba(
                        specularImg,
                        PBRAtlasStorage.globalSpecularAtlas,
                        atlasSpriteX,
                        atlasSpriteY,
                        atlasSpriteWidth,
                        atlasSpriteHeight,
                        spriteName,
                        "_s"
                );
            }
        }

        PBRAtlasStorage.setOmmSpriteRegions(ommSpriteRegions);


        // === 核心内存透传防线 ===
        // 提取堆外内存的绝对地址（Pointer），下发至 C++ 层进行 Vulkan Image 的显存重组
        long normalPtr =
                PBRAtlasStorage.globalNormalAtlas.getPointer();

        long specularPtr =
                PBRAtlasStorage.globalSpecularAtlas.getPointer();


        NativeVulkan.uploadPBRAtlases(
                normalPtr,
                specularPtr,
                atlasWidth,
                atlasHeight
        );


        // 物理日志防线：确认指针是否成功下放
        System.out.println(
                "[PBR Pipeline] Uploading Atlases to C++. NormalPtr: " +
                        normalPtr +
                        ", SpecularPtr: " +
                        specularPtr
        );
    }
}
