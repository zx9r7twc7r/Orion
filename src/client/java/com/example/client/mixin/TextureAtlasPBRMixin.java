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


    private static final float
            POM_LOW_PERCENTILE = 0.01f;

    private static final float
            POM_HIGH_PERCENTILE = 0.99f;

    private static final int
            POM_MIN_ROBUST_ALPHA_SPAN = 4;

    private static final float
            POM_STRONG_RELIEF_ALPHA_SPAN = 52.0f;


    private static final int PBR_ATLAS_DOWNSCALE = 1;



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
        if (PBR_ATLAS_DOWNSCALE > 1) {
            return;
        }

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


    private static void copyBoxFilteredRgba(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            int srcWidth,
            int srcHeight
    ) {
        for (int py = 0; py < height; ++py) {
            final int y0 = py * srcHeight / height;
            final int y1 = Math.max(y0 + 1, (py + 1) * srcHeight / height);

            for (int px = 0; px < width; ++px) {
                final int x0 = px * srcWidth / width;
                final int x1 = Math.max(x0 + 1, (px + 1) * srcWidth / width);

                long sumR = 0L;
                long sumG = 0L;
                long sumB = 0L;
                long sumA = 0L;
                int count = 0;

                for (int sy = y0; sy < y1; ++sy) {
                    for (int sx = x0; sx < x1; ++sx) {
                        final long pixel = pixelAddress(source, sx, sy);
                        sumR += MemoryUtil.memGetByte(pixel) & 0xFF;
                        sumG += MemoryUtil.memGetByte(pixel + 1L) & 0xFF;
                        sumB += MemoryUtil.memGetByte(pixel + 2L) & 0xFF;
                        sumA += MemoryUtil.memGetByte(pixel + 3L) & 0xFF;
                        ++count;
                    }
                }

                if (count <= 0) {
                    continue;
                }

                final long destinationPixel =
                        pixelAddress(destination, dstX + px, dstY + py);

                MemoryUtil.memPutByte(destinationPixel, (byte) ((sumR + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 1L, (byte) ((sumG + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 2L, (byte) ((sumB + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 3L, (byte) ((sumA + count / 2L) / count));
            }
        }
    }


    private static void copyBoxFilteredNormalWithHeight(
            NativeImage source,
            NativeImage destination,
            int dstX,
            int dstY,
            int width,
            int height,
            int srcWidth,
            int srcHeight,
            int alphaP99,
            int robustSpan,
            float reliefStrength
    ) {
        for (int py = 0; py < height; ++py) {
            final int y0 = py * srcHeight / height;
            final int y1 = Math.max(y0 + 1, (py + 1) * srcHeight / height);

            for (int px = 0; px < width; ++px) {
                final int x0 = px * srcWidth / width;
                final int x1 = Math.max(x0 + 1, (px + 1) * srcWidth / width);

                long sumR = 0L;
                long sumG = 0L;
                long sumB = 0L;
                long sumA = 0L;
                int count = 0;

                for (int sy = y0; sy < y1; ++sy) {
                    for (int sx = x0; sx < x1; ++sx) {
                        final long pixel = pixelAddress(source, sx, sy);
                        sumR += MemoryUtil.memGetByte(pixel) & 0xFF;
                        sumG += MemoryUtil.memGetByte(pixel + 1L) & 0xFF;
                        sumB += MemoryUtil.memGetByte(pixel + 2L) & 0xFF;
                        sumA += MemoryUtil.memGetByte(pixel + 3L) & 0xFF;
                        ++count;
                    }
                }

                if (count <= 0 || robustSpan <= 0) {
                    continue;
                }

                final int averageAlpha =
                        (int) ((sumA + count / 2L) / count);

                float normalizedDepth =
                        (alphaP99 - averageAlpha) / (float) robustSpan;

                normalizedDepth = clamp01(normalizedDepth);

                final float finalDepth =
                        normalizedDepth * reliefStrength;

                final int remappedAlpha =
                        Math.round((1.0f - finalDepth) * 255.0f);

                final long destinationPixel =
                        pixelAddress(destination, dstX + px, dstY + py);

                MemoryUtil.memPutByte(destinationPixel, (byte) ((sumR + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 1L, (byte) ((sumG + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 2L, (byte) ((sumB + count / 2L) / count));
                MemoryUtil.memPutByte(destinationPixel + 3L, (byte) remappedAlpha);
            }
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
        if (PBR_ATLAS_DOWNSCALE > 1) {
            copyBoxFilteredRgba(
                    source,
                    destination,
                    dstX,
                    dstY,
                    width,
                    height,
                    source.getWidth(),
                    sourceFrameHeight(source)
            );

            return;
        }

        logResampledPbrTexture(
                spriteName,
                textureKind,
                source,
                width,
                height
        );

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

        int sampledSourceHeight =
                sourceFrameHeight(source);

        int sampleCount =
                source.getWidth() *
                        sampledSourceHeight;


        for (int py = 0; py < sampledSourceHeight; ++py) {
            for (int px = 0; px < source.getWidth(); ++px) {
                long sourcePixel =
                        pixelAddress(source, px, py);

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


        float reliefStrength =
                clamp01(
                        robustSpan /
                                POM_STRONG_RELIEF_ALPHA_SPAN
                );


        if (PBR_ATLAS_DOWNSCALE > 1) {
            copyBoxFilteredNormalWithHeight(
                    source,
                    destination,
                    dstX,
                    dstY,
                    width,
                    height,
                    source.getWidth(),
                    sampledSourceHeight,
                    alphaP99,
                    robustSpan,
                    reliefStrength
            );
        } else {

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


        if (!TextureAtlas.LOCATION_BLOCKS.equals(self.location())) {
            return;
        }

        EntityRtCapture.invalidateTextureCache();
        ParticleRtCapture.invalidateTextureCache();

        PBRAtlasStorage.bumpAtlasEpoch();


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

        final int sourceAtlasWidth = preparations.width();
        final int sourceAtlasHeight = preparations.height();

        int atlasWidth = Math.max(1, sourceAtlasWidth / PBR_ATLAS_DOWNSCALE);
        int atlasHeight = Math.max(1, sourceAtlasHeight / PBR_ATLAS_DOWNSCALE);

        if (PBR_ATLAS_DOWNSCALE > 1) {
            System.out.println(
                    "[PBR ATLAS] downscale=" + PBR_ATLAS_DOWNSCALE +
                            " size=" + atlasWidth + "x" + atlasHeight +
                            " (source " + sourceAtlasWidth + "x" + sourceAtlasHeight + ")"
            );
        }

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

        fillDefaultSpecularAtlas(
                PBRAtlasStorage.globalSpecularAtlas,
                atlasWidth,
                atlasHeight
        );


        final int[] spriteRects =
                new int[preparations.regions().size() * 4];
        int spriteRectCount = 0;

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

            if (spriteRectCount * 4 + 4 <= spriteRects.length) {
                spriteRects[spriteRectCount * 4] = atlasSpriteX;
                spriteRects[spriteRectCount * 4 + 1] = atlasSpriteY;
                spriteRects[spriteRectCount * 4 + 2] = atlasSpriteWidth;
                spriteRects[spriteRectCount * 4 + 3] = atlasSpriteHeight;
                ++spriteRectCount;
            }

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


        long normalPtr =
                PBRAtlasStorage.globalNormalAtlas.getPointer();

        long specularPtr =
                PBRAtlasStorage.globalSpecularAtlas.getPointer();


        NativeVulkan.uploadPBRAtlases(
                normalPtr,
                specularPtr,
                atlasWidth,
                atlasHeight,
                spriteRects,
                spriteRectCount
        );


        System.out.println(
                "[PBR Pipeline] Uploading Atlases to C++. NormalPtr: " +
                        normalPtr +
                        ", SpecularPtr: " +
                        specularPtr
        );

        PBRAtlasStorage.globalNormalAtlas.close();
        PBRAtlasStorage.globalNormalAtlas = null;

        PBRAtlasStorage.globalSpecularAtlas.close();
        PBRAtlasStorage.globalSpecularAtlas = null;
    }
}
